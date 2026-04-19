package com.smplkit;

import com.smplkit.config.ConfigClient;
import com.smplkit.flags.FlagsClient;
import com.smplkit.logging.LoggingClient;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.model.ContextBulkItem;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.config.ApiClient;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;

import com.smplkit.internal.Debug;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Top-level entry point for the smplkit Java SDK.
 *
 * <p>Use the {@link #builder()} method to construct an instance:</p>
 * <pre>{@code
 * try (SmplClient client = SmplClient.builder()
 *         .apiKey("sk_api_...")
 *         .environment("production")
 *         .service("my-service")
 *         .build()) {
 *     Flag<Boolean> flag = client.flags().booleanFlag("my-flag", false);
 *     boolean enabled = flag.get();
 * }
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources.</p>
 */
public final class SmplClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("smplkit");

    /** Default base domain used when none is specified via the builder. */
    static final String DEFAULT_BASE_DOMAIN = "smplkit.com";
    /** Default URL scheme used when none is specified via the builder. */
    static final String DEFAULT_SCHEME = "https";

    /** Computes a service base URL from scheme, subdomain, and base domain. */
    static String serviceUrl(String scheme, String subdomain, String baseDomain) {
        return ConfigResolver.serviceUrl(scheme, subdomain, baseDomain);
    }

    private ConfigClient config;
    private FlagsClient flags;
    private LoggingClient logging;
    private final SharedWebSocket sharedWs;
    private final HttpClient httpClient;
    private final ContextsApi contextsApi;
    private final String environment;
    private final String service;
    private final String apiKey;
    private final Duration timeout;
    private final MetricsReporter metrics;
    private volatile boolean serviceContextRegistered;

    /**
     * Creates a new SmplClient from resolved config. Package-private; use {@link #builder()}.
     */
    SmplClient(ConfigResolver.ResolvedConfig resolvedConfig, Duration timeout) {
        this.apiKey = resolvedConfig.apiKey;
        this.environment = resolvedConfig.environment;
        this.service = resolvedConfig.service;
        this.timeout = timeout;

        String configBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "config", resolvedConfig.baseDomain);
        String flagsBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "flags", resolvedConfig.baseDomain);
        String appBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "app", resolvedConfig.baseDomain);
        String loggingBaseUrl = ConfigResolver.serviceUrl(resolvedConfig.scheme, "logging", resolvedConfig.baseDomain);

        if (resolvedConfig.debug) {
            Debug.enable();
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        this.metrics = resolvedConfig.disableTelemetry ? null
                : new MetricsReporter(httpClient, appBaseUrl, apiKey, environment, service);
        this.sharedWs = new SharedWebSocket(httpClient, appBaseUrl, apiKey, metrics);
        this.contextsApi = buildContextsApi(appBaseUrl, apiKey, timeout);
        this.config = buildConfigClient(httpClient, apiKey, timeout, configBaseUrl);
        this.config.setEnvironment(environment);
        this.config.setMetrics(metrics);
        this.config.setSharedWs(this.sharedWs);
        this.flags = buildFlagsClient(httpClient, apiKey, timeout, sharedWs, environment, service,
                flagsBaseUrl, appBaseUrl);
        this.flags.setMetrics(metrics);
        this.logging = buildLoggingClient(httpClient, apiKey, timeout, environment, service,
                loggingBaseUrl);
        this.logging.setMetrics(metrics);
        this.logging.setSharedWs(this.sharedWs);
        String maskedKey = apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey + "...";
        Debug.log("lifecycle", "SmplClient created (api_key=" + maskedKey + ", environment=" + environment + ", service=" + service + ")");
    }

    /**
     * Package-private constructor for testing with a custom HttpClient.
     */
    SmplClient(HttpClient httpClient, String apiKey, String environment, String service, Duration timeout) {
        this.apiKey = apiKey;
        this.environment = environment;
        this.service = service;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.metrics = null;
        String appBaseUrl = serviceUrl(DEFAULT_SCHEME, "app", DEFAULT_BASE_DOMAIN);
        this.sharedWs = new SharedWebSocket(httpClient, appBaseUrl, apiKey);
        this.contextsApi = buildContextsApi(appBaseUrl, apiKey, timeout);
        this.config = buildConfigClient(httpClient, apiKey, timeout,
                serviceUrl(DEFAULT_SCHEME, "config", DEFAULT_BASE_DOMAIN));
        this.config.setEnvironment(environment);
        this.flags = buildFlagsClient(httpClient, apiKey, timeout, sharedWs, environment, service,
                serviceUrl(DEFAULT_SCHEME, "flags", DEFAULT_BASE_DOMAIN), appBaseUrl);
        this.logging = buildLoggingClient(httpClient, apiKey, timeout, environment, service,
                serviceUrl(DEFAULT_SCHEME, "logging", DEFAULT_BASE_DOMAIN));
    }

    /**
     * Package-private constructor for testing with injectable sub-clients.
     */
    SmplClient(HttpClient httpClient, String apiKey, String environment, String service,
               Duration timeout, FlagsClient flags, ConfigClient config) {
        this.apiKey = apiKey;
        this.environment = environment;
        this.service = service;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.metrics = null;
        String appBaseUrl = serviceUrl(DEFAULT_SCHEME, "app", DEFAULT_BASE_DOMAIN);
        this.sharedWs = new SharedWebSocket(httpClient, appBaseUrl, apiKey);
        this.contextsApi = buildContextsApi(appBaseUrl, apiKey, timeout);
        this.config = config;
        this.flags = flags;
        this.flags.setParentService(service);
        this.flags.setEnvironment(environment);
        this.logging = buildLoggingClient(httpClient, apiKey, timeout, environment, service,
                serviceUrl(DEFAULT_SCHEME, "logging", DEFAULT_BASE_DOMAIN));
    }

    /**
     * Package-private constructor for testing with injectable sub-clients and contextsApi.
     */
    SmplClient(HttpClient httpClient, String apiKey, String environment, String service,
               Duration timeout, FlagsClient flags, ConfigClient config, ContextsApi contextsApi) {
        this.apiKey = apiKey;
        this.environment = environment;
        this.service = service;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.metrics = null;
        String appBaseUrl = serviceUrl(DEFAULT_SCHEME, "app", DEFAULT_BASE_DOMAIN);
        this.sharedWs = new SharedWebSocket(httpClient, appBaseUrl, apiKey);
        this.contextsApi = contextsApi;
        this.config = config;
        this.flags = flags;
        this.flags.setParentService(service);
        this.flags.setEnvironment(environment);
        this.logging = buildLoggingClient(httpClient, apiKey, timeout, environment, service,
                serviceUrl(DEFAULT_SCHEME, "logging", DEFAULT_BASE_DOMAIN));

        // Synchronous registration for testability (contextsApi is injected)
        registerServiceContext();
    }

    private void registerServiceContext() {
        try {
            ContextBulkItem envItem = new ContextBulkItem()
                    .type("environment")
                    .key(environment);
            ContextBulkItem svcItem = new ContextBulkItem()
                    .type("service")
                    .key(service)
                    .attributes(Map.of("name", service));
            ContextBulkRegister reqBody = new ContextBulkRegister()
                    .contexts(List.of(envItem, svcItem));
            contextsApi.bulkRegisterContexts(reqBody);
        } catch (Exception e) {
            LOG.warning("Failed to register service context: " + e.getMessage());
            Debug.log("lifecycle", "Failed to register service context: " + e);
        }
    }

    private static ConfigClient buildConfigClient(HttpClient httpClient, String apiKey,
                                                   Duration timeout, String configBaseUrl) {
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(configBaseUrl);
        apiClient.setRequestInterceptor(
                builder -> builder.header("Authorization", "Bearer " + apiKey));
        apiClient.setReadTimeout(timeout);
        ConfigsApi configsApi = new ConfigsApi(apiClient);
        return new ConfigClient(configsApi, httpClient, apiKey);
    }

    private static FlagsClient buildFlagsClient(HttpClient httpClient, String apiKey,
                                                 Duration timeout, SharedWebSocket sharedWs,
                                                 String environment, String service,
                                                 String flagsBaseUrl, String appBaseUrl) {
        com.smplkit.internal.generated.flags.ApiClient flagsApiClient =
                new com.smplkit.internal.generated.flags.ApiClient();
        flagsApiClient.updateBaseUri(flagsBaseUrl);
        flagsApiClient.setRequestInterceptor(authInterceptor(apiKey));
        flagsApiClient.setReadTimeout(timeout);
        FlagsApi flagsApi = new FlagsApi(flagsApiClient);

        com.smplkit.internal.generated.app.ApiClient appApiClient =
                new com.smplkit.internal.generated.app.ApiClient();
        appApiClient.updateBaseUri(appBaseUrl);
        appApiClient.setRequestInterceptor(authInterceptor(apiKey));
        appApiClient.setReadTimeout(timeout);
        ContextsApi contextsApi = new ContextsApi(appApiClient);

        FlagsClient client = new FlagsClient(flagsApi, contextsApi,
                httpClient, apiKey, flagsBaseUrl, appBaseUrl, timeout);
        client.setSharedWs(sharedWs);
        client.setParentService(service);
        client.setEnvironment(environment);
        return client;
    }

    private static ContextsApi buildContextsApi(String baseUrl, String apiKey, Duration timeout) {
        com.smplkit.internal.generated.app.ApiClient appApiClient =
                new com.smplkit.internal.generated.app.ApiClient();
        appApiClient.updateBaseUri(baseUrl);
        appApiClient.setRequestInterceptor(authInterceptor(apiKey));
        appApiClient.setReadTimeout(timeout);
        return new ContextsApi(appApiClient);
    }

    /** Builds the auth header interceptor. */
    static java.util.function.Consumer<java.net.http.HttpRequest.Builder> authInterceptor(String apiKey) {
        return builder -> builder.header("Authorization", "Bearer " + apiKey);
    }

    private static LoggingClient buildLoggingClient(HttpClient httpClient, String apiKey,
                                                    Duration timeout, String environment,
                                                    String service, String loggingBaseUrl) {
        com.smplkit.internal.generated.logging.ApiClient loggingApiClient =
                new com.smplkit.internal.generated.logging.ApiClient();
        loggingApiClient.updateBaseUri(loggingBaseUrl);
        loggingApiClient.setRequestInterceptor(authInterceptor(apiKey));
        loggingApiClient.setReadTimeout(timeout);
        LoggersApi loggersApi = new LoggersApi(loggingApiClient);
        LogGroupsApi logGroupsApi = new LogGroupsApi(loggingApiClient);
        LoggingClient client = new LoggingClient(loggersApi, logGroupsApi, httpClient, apiKey);
        client.setEnvironment(environment);
        client.setService(service);
        return client;
    }

    /** Returns the config client. */
    public ConfigClient config() {
        ensureServiceContextRegistered();
        return config;
    }

    /** Returns the flags client. */
    public FlagsClient flags() {
        ensureServiceContextRegistered();
        return flags;
    }

    /** Returns the logging client. */
    public LoggingClient logging() {
        ensureServiceContextRegistered();
        return logging;
    }

    private void ensureServiceContextRegistered() {
        if (serviceContextRegistered) return;
        serviceContextRegistered = true;
        Thread bgThread = new Thread(this::registerServiceContext, "smplkit-svc-ctx");
        bgThread.setDaemon(true);
        bgThread.start();
    }

    /** Returns the configured environment. */
    public String environment() {
        return environment;
    }

    /** Returns the configured service name. */
    public String service() {
        return service;
    }

    /**
     * Creates a new {@link SmplClient} with automatic resolution.
     */
    public static SmplClient create() {
        return builder().build();
    }

    /**
     * Creates a new {@link SmplClient} with the given API key, environment, and service.
     */
    public static SmplClient create(String apiKey, String environment, String service) {
        return builder().apiKey(apiKey).environment(environment).service(service).build();
    }

    /**
     * Returns a new builder for constructing {@link SmplClient} instances.
     */
    public static SmplClientBuilder builder() {
        return new SmplClientBuilder();
    }

    /**
     * Releases all resources held by this client.
     */
    @Override
    public void close() {
        Debug.log("lifecycle", "SmplClient.close() called");
        if (logging != null) {
            logging.close();
        }
        sharedWs.close();
        if (metrics != null) {
            metrics.close();
        }
    }
}
