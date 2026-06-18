package com.smplkit;

import com.smplkit.account.AccountClient;
import com.smplkit.audit.AuditClient;
import com.smplkit.config.ConfigClient;
import com.smplkit.flags.FlagsClient;
import com.smplkit.jobs.JobsClient;
import com.smplkit.logging.LoggingClient;
import com.smplkit.platform.PlatformClient;
import com.smplkit.internal.ContextRegistrationBuffer;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.model.ContextBulkItem;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.config.ApiClient;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.Debug;
import com.smplkit.internal.HttpClients;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

/**
 * Synchronous entry point for the smplkit SDK.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (SmplClient client = SmplClient.builder()
 *         .environment("production")
 *         .service("my-svc")
 *         .build()) {
 *     Flag<Boolean> checkoutV2 = client.flags.booleanFlag("checkout-v2", false);
 *     if (checkoutV2.get()) { ... }
 * }
 * }</pre>
 *
 * <p>All parameters are optional. When omitted, the SDK resolves each one in
 * precedence order, lowest to highest: built-in defaults, then the
 * {@code ~/.smplkit} configuration file, then {@code SMPLKIT_*} environment
 * variables, then the explicit builder arguments (a value supplied at a higher
 * level overrides the lower ones).</p>
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources.</p>
 */
public final class SmplClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("smplkit");

    /** Default base domain used when none is specified via the builder. */
    static final String DEFAULT_BASE_DOMAIN = "smplkit.com";
    /** Default URL scheme used when none is specified via the builder. */
    static final String DEFAULT_SCHEME = "https";

    /**
     * Periodic flush of all sub-client registration buffers (contexts, flags,
     * loggers, config). Threshold flushes still fire immediately when buffers
     * fill up; this timer is the liveness guarantee for the tail.
     */
    private static final long PERIODIC_FLUSH_INTERVAL_MS = 60_000L;

    /** Computes a service base URL from scheme, subdomain, and base domain. */
    static String serviceUrl(String scheme, String subdomain, String baseDomain) {
        return ConfigResolver.serviceUrl(scheme, subdomain, baseDomain);
    }

    /** Platform's cross-cutting CRUD on one client (environments, services, contexts, contextTypes). */
    public final PlatformClient platform;
    /** Account-level settings on one client. */
    public final AccountClient account;
    /** Config's full surface on one client. */
    public final ConfigClient config;
    /** Flags' full surface on one client. */
    public final FlagsClient flags;
    /** Logging's full surface on one client. */
    public final LoggingClient logging;
    /** Audit's full surface on one client. */
    public final AuditClient audit;
    /** Jobs' full surface on one client. */
    public final JobsClient jobs;

    private final SharedWebSocket sharedWs;
    private final HttpClient httpClient;
    private final ContextsApi contextsApi;
    private final String environment;
    private final String service;
    private final String apiKey;
    private final Duration timeout;
    private final MetricsReporter metrics;

    // Deferred background machinery (see ensureStarted): no threads, no
    // background network at construction. An audit-only or jobs-only customer
    // pays zero threads and zero network until the first config/flags/logging
    // operation, set_context, or wait_until_ready.
    private volatile boolean closed;
    private volatile boolean started;
    private final Object startLock = new Object();
    private Timer flushTimer;

    /**
     * Creates a new SmplClient from resolved config. Package-private; use {@link #builder()}.
     */
    SmplClient(ConfigResolver.ResolvedConfig resolvedConfig, Duration timeout,
               Map<String, String> extraHeaders) {
        this(resolvedConfig, timeout, extraHeaders, HttpClients.http11(timeout), null);
    }

    /**
     * Package-private constructor that injects the HTTP transport (and optionally
     * the app {@link ContextsApi} used for service-context registration). Unit
     * tests use this and the convenience seams below to drive the client with a
     * mock transport so no real socket is ever opened.
     */
    SmplClient(ConfigResolver.ResolvedConfig resolvedConfig, Duration timeout,
               Map<String, String> extraHeaders, HttpClient httpClient,
               ContextsApi injectedContextsApi) {
        this.apiKey = resolvedConfig.apiKey;
        this.environment = resolvedConfig.environment;
        this.service = resolvedConfig.service;
        this.timeout = timeout;

        String configBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "config", resolvedConfig.baseDomain);
        String flagsBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "flags", resolvedConfig.baseDomain);
        String appBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "app", resolvedConfig.baseDomain);
        String loggingBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "logging", resolvedConfig.baseDomain);
        String auditBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "audit", resolvedConfig.baseDomain);
        String jobsBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "jobs", resolvedConfig.baseDomain);

        if (resolvedConfig.debug) {
            Debug.enable();
        }

        this.httpClient = httpClient;
        this.metrics = resolvedConfig.disableTelemetry ? null
                : new MetricsReporter(httpClient, appBaseUrl, apiKey, environment, service);
        this.sharedWs = new SharedWebSocket(httpClient, appBaseUrl, apiKey, metrics);

        // Build the per-service HTTP transports + the context-registration
        // buffer. Side-effect-free: each transport connects lazily on first
        // call. platform owns the buffer; config/flags/logging borrow their
        // transports from here.
        com.smplkit.internal.generated.app.ApiClient appApiClient = buildAppApiClient(appBaseUrl, apiKey, extraHeaders, timeout);
        this.contextsApi = injectedContextsApi != null ? injectedContextsApi : new ContextsApi(appApiClient);
        ContextRegistrationBuffer contextBuffer = new ContextRegistrationBuffer();

        // Platform's cross-cutting CRUD on one client; wired into this parent
        // so it borrows the shared app transport, and shares the
        // context-registration buffer. Built BEFORE flags so the contexts
        // seam below is available.
        this.platform = PlatformClient.wired(appApiClient, contextBuffer);
        // Account-level settings on one client; built from the app url + api
        // key (the settings sub-client uses HTTP directly).
        this.account = new AccountClient(apiKey, appBaseUrl, extraHeaders);
        // Config's full surface on one client; wired into this parent so it
        // borrows the shared config transport and WebSocket.
        this.config = buildConfigClient(httpClient, apiKey, extraHeaders, timeout, configBaseUrl);
        this.config.setEnvironment(environment);
        this.config.setService(service);
        this.config.setMetrics(metrics);
        this.config.setSharedWs(this.sharedWs);
        this.config.setEnsureStarted(this::ensureStarted);
        // Flags' full surface on one client; wired into this parent so it
        // borrows the shared flags transport and WebSocket. The context
        // buffer is the seam for evaluation-context registration, shared with
        // client.platform.contexts.
        this.flags = buildFlagsClient(httpClient, apiKey, extraHeaders, timeout, sharedWs,
                environment, service, flagsBaseUrl, appBaseUrl);
        this.flags.setMetrics(metrics);
        this.flags.setContextBuffer(contextBuffer);
        this.flags.setEnsureStarted(this::ensureStarted);
        // Logging's full surface on one client; the two management sub-clients
        // live at client.logging.loggers / client.logging.logGroups.
        this.logging = buildLoggingClient(httpClient, apiKey, extraHeaders, timeout, environment,
                service, loggingBaseUrl);
        this.logging.setMetrics(metrics);
        this.logging.setSharedWs(this.sharedWs);
        this.logging.setEnsureStarted(this::ensureStarted);
        // Audit's full surface on one client; this runtime instance carries
        // the configured environment as X-Smplkit-Environment and owns its
        // own transport (closed in close()).
        this.audit = new AuditClient(httpClient, apiKey, extraHeaders, timeout, auditBaseUrl, environment);
        // Jobs has no runtime/management split — reuse the shared jobs
        // transport so client.jobs is one-stop. The configured environment
        // defaults the one-off birth header, the run-now header, and the runs
        // read filter.
        this.jobs = new JobsClient(apiKey, extraHeaders, timeout, jobsBaseUrl, environment);

        this.closed = false;
        this.started = false;

        String maskedKey = apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey + "...";
        Debug.log("lifecycle", "SmplClient created (api_key=" + maskedKey + ", environment=" + environment + ", service=" + service + ")");
    }

    /**
     * Package-private test seam — builds a client with the default domain/scheme,
     * telemetry disabled, and the supplied transport injected. Used by unit tests
     * to exercise the client without opening a real socket.
     */
    SmplClient(HttpClient httpClient, String apiKey, String environment, String service, Duration timeout) {
        this(httpClient, apiKey, environment, service, timeout, null);
    }

    /**
     * Package-private test seam — as above, but also injects the app
     * {@link ContextsApi} so a test can drive service-context registration
     * against a mock without opening a real socket.
     */
    SmplClient(HttpClient httpClient, String apiKey, String environment, String service,
               Duration timeout, ContextsApi injectedContextsApi) {
        this(new ConfigResolver.ResolvedConfig(apiKey, DEFAULT_BASE_DOMAIN, DEFAULT_SCHEME,
                        environment, service, false, true),
                timeout, java.util.Collections.emptyMap(), httpClient, injectedContextsApi);
    }

    /**
     * Start the deferred background machinery exactly once.
     *
     * <p>Idempotent and thread-safe (lock + flag); a no-op after {@link #close()}.
     * Triggered by the first config/flags/logging operation, {@link #setContext},
     * {@link #waitUntilReady}, or WebSocket open — never at construction.</p>
     */
    void ensureStarted() {
        synchronized (startLock) {
            if (started || closed) {
                return;
            }
            started = true;
        }
        schedulePeriodicFlush();
        Thread initThread = new Thread(this::registerServiceContext, "smplkit-svc-ctx");
        initThread.setDaemon(true);
        initThread.start();
    }

    /** Tick the periodic registration-buffer flush. Self-rescheduling. */
    private void schedulePeriodicFlush() {
        this.flushTimer = new Timer("smplkit-periodic-flush", true);
        flushTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                try {
                    platform.contexts.flush();
                    flags.flush();
                    logging.loggers.flush();
                    config.flush();
                } catch (Exception exc) {
                    LOG.warning("Periodic registration flush failed: " + exc);
                    Debug.log("registration", String.valueOf(exc));
                }
            }
        }, PERIODIC_FLUSH_INTERVAL_MS, PERIODIC_FLUSH_INTERVAL_MS);
    }

    /** Drain every registration buffer one last time on close. */
    private void finalFlush() {
        try { platform.contexts.flush(); } catch (Exception e) { Debug.log("registration", String.valueOf(e)); }
        try { flags.flush(); } catch (Exception e) { Debug.log("registration", String.valueOf(e)); }
        try { logging.loggers.flush(); } catch (Exception e) { Debug.log("registration", String.valueOf(e)); }
        try { config.flush(); } catch (Exception e) { Debug.log("registration", String.valueOf(e)); }
    }

    /**
     * Register the environment and/or service as context instances.
     *
     * <p>Only the values that are set are registered; if neither environment
     * nor service was provided the POST is skipped entirely (an audit/jobs-only
     * customer has nothing to register).</p>
     */
    private void registerServiceContext() {
        try {
            List<ContextBulkItem> items = new ArrayList<>();
            if (environment != null) {
                items.add(new ContextBulkItem().type("environment").key(environment));
            }
            if (service != null) {
                items.add(new ContextBulkItem().type("service").key(service)
                        .attributes(Map.of("name", service)));
            }
            if (items.isEmpty()) {
                return;
            }
            ContextBulkRegister reqBody = new ContextBulkRegister().contexts(items);
            contextsApi.bulkRegisterContexts(reqBody);
        } catch (Exception e) {
            LOG.warning("Failed to register service context: " + e.getMessage());
            Debug.log("lifecycle", "Failed to register service context: " + e);
        }
    }

    private static ConfigClient buildConfigClient(HttpClient httpClient, String apiKey,
                                                   Map<String, String> extraHeaders,
                                                   Duration timeout, String configBaseUrl) {
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(configBaseUrl);
        apiClient.setRequestInterceptor(HttpClients.compositeInterceptor(apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);
        ConfigsApi configsApi = new ConfigsApi(apiClient);
        return new ConfigClient(configsApi, httpClient, apiKey);
    }

    private static FlagsClient buildFlagsClient(HttpClient httpClient, String apiKey,
                                                 Map<String, String> extraHeaders,
                                                 Duration timeout, SharedWebSocket sharedWs,
                                                 String environment, String service,
                                                 String flagsBaseUrl, String appBaseUrl) {
        com.smplkit.internal.generated.flags.ApiClient flagsApiClient =
                new com.smplkit.internal.generated.flags.ApiClient();
        flagsApiClient.setHttpClientBuilder(HttpClients.builder());
        flagsApiClient.updateBaseUri(flagsBaseUrl);
        flagsApiClient.setRequestInterceptor(HttpClients.compositeInterceptor(apiKey, extraHeaders));
        flagsApiClient.setReadTimeout(timeout);
        FlagsApi flagsApi = new FlagsApi(flagsApiClient);

        ContextsApi contextsApi = new ContextsApi(buildAppApiClient(appBaseUrl, apiKey, extraHeaders, timeout));

        FlagsClient client = new FlagsClient(flagsApi, contextsApi,
                httpClient, apiKey, flagsBaseUrl, appBaseUrl, timeout);
        client.setSharedWs(sharedWs);
        client.setParentService(service);
        client.setEnvironment(environment);
        return client;
    }

    private static com.smplkit.internal.generated.app.ApiClient buildAppApiClient(
            String baseUrl, String apiKey, Map<String, String> extraHeaders, Duration timeout) {
        com.smplkit.internal.generated.app.ApiClient appApiClient =
                new com.smplkit.internal.generated.app.ApiClient();
        appApiClient.setHttpClientBuilder(HttpClients.builder());
        appApiClient.updateBaseUri(baseUrl);
        appApiClient.setRequestInterceptor(HttpClients.compositeInterceptor(apiKey, extraHeaders));
        appApiClient.setReadTimeout(timeout);
        return appApiClient;
    }

    private static LoggingClient buildLoggingClient(HttpClient httpClient, String apiKey,
                                                    Map<String, String> extraHeaders,
                                                    Duration timeout, String environment,
                                                    String service, String loggingBaseUrl) {
        com.smplkit.internal.generated.logging.ApiClient loggingApiClient =
                new com.smplkit.internal.generated.logging.ApiClient();
        loggingApiClient.setHttpClientBuilder(HttpClients.builder());
        loggingApiClient.updateBaseUri(loggingBaseUrl);
        loggingApiClient.setRequestInterceptor(HttpClients.compositeInterceptor(apiKey, extraHeaders));
        loggingApiClient.setReadTimeout(timeout);
        LoggersApi loggersApi = new LoggersApi(loggingApiClient);
        LogGroupsApi logGroupsApi = new LogGroupsApi(loggingApiClient);
        LoggingClient client = new LoggingClient(loggersApi, logGroupsApi, httpClient, apiKey);
        client.setEnvironment(environment);
        client.setService(service);
        return client;
    }

    /** Default deadline for {@link #waitUntilReady(Duration)}. */
    private static final Duration DEFAULT_WAIT_UNTIL_READY = Duration.ofSeconds(10);

    /**
     * Optionally pre-warm the SDK and block until the live socket is up.
     *
     * <p>Eagerly opens the live-updates WebSocket and waits for the handshake
     * to complete. After this returns, any {@code onChange} listeners receive
     * every server event from this point forward — including events triggered
     * by writes the caller fires immediately afterward.</p>
     *
     * <p>Optional: config and flags connect lazily on first live use, so this
     * is purely a pre-warm / WebSocket-ready barrier. Logging integration is
     * <em>not</em> connected here — call {@code client.logging.install()}
     * separately if you want it (it installs adapters and hooks into your
     * application's logger, which should be opt-in).</p>
     *
     * @param timeout the maximum time to wait for the live-updates WebSocket
     *     handshake before giving up
     * @throws com.smplkit.errors.TimeoutError if the WebSocket fails to
     *     connect within {@code timeout}
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void waitUntilReady(Duration timeout) throws InterruptedException {
        ensureWs();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!"connected".equals(sharedWs.connectionStatus())) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new com.smplkit.errors.TimeoutError(
                        "Live-updates websocket did not connect within " + timeout
                                + " (status: " + sharedWs.connectionStatus() + ")",
                        null);
            }
            Thread.sleep(50);
        }
    }

    /**
     * Pre-warms the SDK and blocks until the live socket is up, using the
     * default 10-second timeout. Equivalent to
     * {@code waitUntilReady(Duration.ofSeconds(10))}.
     *
     * @throws com.smplkit.errors.TimeoutError if the WebSocket fails to
     *     connect within the default timeout
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void waitUntilReady() throws InterruptedException {
        waitUntilReady(DEFAULT_WAIT_UNTIL_READY);
    }

    /** Lazily start the deferred machinery and the shared WebSocket. */
    private SharedWebSocket ensureWs() {
        ensureStarted();
        sharedWs.start();
        return sharedWs;
    }

    /** Test-only accessor for the shared WebSocket. Used to flip status
     * to "connected" without opening a real network connection. */
    SharedWebSocket sharedWsForTesting() {
        return sharedWs;
    }

    /**
     * Returns the configured environment.
     *
     * @return the environment this client connects to (e.g. {@code "production"}),
     *     or {@code null} if none was resolved
     */
    public String environment() {
        return environment;
    }

    /**
     * Returns the configured service name.
     *
     * @return the service name this client identifies as (e.g.
     *     {@code "user-service"}), or {@code null} if none was resolved
     */
    public String service() {
        return service;
    }

    /** Per-thread evaluation context, mirrors Python's contextvars-backed implementation. */
    private static final ThreadLocal<List<Context>> CURRENT_CONTEXT =
            ThreadLocal.withInitial(java.util.Collections::emptyList);

    /**
     * Stash {@code contexts} as the current request's evaluation context.
     *
     * <p>Typical use is from middleware — set the context once at request entry
     * and every {@code flag.get()} (and other context-sensitive evaluations)
     * inside that request automatically picks it up. {@link ThreadLocal}
     * provides per-thread isolation so concurrent requests don't
     * cross-contaminate.</p>
     *
     * <p>Each unique {@code (type, key)} is also registered with the platform,
     * deduplicated via an LRU and sent in the background. An empty list clears
     * any registration step.</p>
     *
     * <p>Two usage shapes:</p>
     * <pre>{@code
     * // Fire-and-forget (typical middleware) — ignore the return value
     * client.setContext(List.of(new Context("user", "u-1"),
     *                           new Context("account", "a-1")));
     *
     * // Scoped block (e.g. impersonation or one-off override)
     * try (ContextScope scope = client.setContext(
     *         List.of(new Context("user", "impersonated")))) {
     *     // ...
     * }
     * // the previous context is restored here
     * }</pre>
     *
     * @param contexts the contexts to make active for the current thread (e.g.
     *     the request's user and account); an empty list clears any
     *     registration step
     * @return a {@link ContextScope} that may be ignored for fire-and-forget use,
     *     or closed (most naturally via try-with-resources) to restore the
     *     context that was active before this call
     */
    public ContextScope setContext(List<Context> contexts) {
        ensureStarted();
        List<Context> previous = CURRENT_CONTEXT.get();
        if (contexts != null && !contexts.isEmpty()) {
            platform.contexts.register(contexts);
        }
        CURRENT_CONTEXT.set(contexts != null ? contexts : java.util.Collections.emptyList());
        flags.setContextProvider(CURRENT_CONTEXT::get);
        return new ContextScope(CURRENT_CONTEXT, previous);
    }

    /** Clears the current thread's evaluation context, so subsequent
     * evaluations on this thread carry no context until the next
     * {@link #setContext}. */
    public void clearContext() {
        CURRENT_CONTEXT.remove();
    }

    /** Test-only accessor for the current thread's evaluation context. Lets
     * tests assert that {@link ContextScope#close()} restores the prior value. */
    List<Context> currentContextForTesting() {
        return CURRENT_CONTEXT.get();
    }

    /**
     * Creates a new {@link SmplClient}, resolving all settings from the
     * {@code ~/.smplkit} configuration file and {@code SMPLKIT_*} environment
     * variables.
     *
     * @return a new client
     * @throws com.smplkit.errors.SmplError if the environment, service, or API
     *     key cannot be resolved
     */
    public static SmplClient create() {
        return builder().build();
    }

    /**
     * Creates a new {@link SmplClient} with the given API key, environment, and
     * service. Any setting left {@code null} is resolved from the
     * {@code ~/.smplkit} configuration file and {@code SMPLKIT_*} environment
     * variables.
     *
     * @param apiKey the API key for authenticating with the smplkit platform
     * @param environment the environment to connect to (e.g. {@code "production"})
     * @param service the service name (e.g. {@code "user-service"})
     * @return a new client
     * @throws com.smplkit.errors.SmplError if the environment, service, or API
     *     key cannot be resolved
     */
    public static SmplClient create(String apiKey, String environment, String service) {
        return builder().apiKey(apiKey).environment(environment).service(service).build();
    }

    /**
     * Returns a new builder for constructing {@link SmplClient} instances.
     *
     * @return a new builder
     */
    public static SmplClientBuilder builder() {
        return new SmplClientBuilder();
    }

    /** Releases all resources held by this client. */
    @Override
    public void close() {
        Debug.log("lifecycle", "SmplClient.close() called");
        closed = true;
        if (flushTimer != null) {
            flushTimer.cancel();
            flushTimer = null;
        }
        finalFlush();
        if (metrics != null) {
            metrics.close();
        }
        logging.close();
        flags.close();
        audit.close();
        config.close();
        sharedWs.close();
    }
}
