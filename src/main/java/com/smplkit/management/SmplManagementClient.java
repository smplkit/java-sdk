package com.smplkit.management;

import com.smplkit.ConfigResolver;
import com.smplkit.ConfigResolver.ResolvedManagementConfig;
import com.smplkit.config.ConfigClient;
import com.smplkit.config.ConfigManagement;
import com.smplkit.flags.FlagsClient;
import com.smplkit.flags.FlagsManagement;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.logging.LogGroupsClient;
import com.smplkit.logging.LoggersClient;
import com.smplkit.logging.LoggingClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Top-level management client for the smplkit SDK.
 *
 * <p>Mirrors the Python SDK's {@code SmplManagementClient}: pure CRUD across
 * the eight management namespaces, with <strong>zero side effects on construction</strong>
 * — no service registration, no metrics thread, no WebSocket, no logger discovery,
 * no outbound HTTP traffic.</p>
 *
 * <pre>{@code
 * try (SmplManagementClient mgmt = SmplManagementClient.create()) {
 *     for (com.smplkit.management.Environment env : mgmt.environments.list()) {
 *         System.out.println(env.getId());
 *     }
 * }
 * }</pre>
 *
 * <p>The eight namespaces:</p>
 * <ul>
 *   <li>{@link #contexts} — context-entity CRUD</li>
 *   <li>{@link #contextTypes} — context-type schemas</li>
 *   <li>{@link #environments} — environment CRUD</li>
 *   <li>{@link #accountSettings} — account-level settings</li>
 *   <li>{@link #config} — config CRUD (singular, mirrors Python {@code mgmt.config})</li>
 *   <li>{@link #flags} — flag CRUD</li>
 *   <li>{@link #loggers} — single-logger CRUD</li>
 *   <li>{@link #logGroups} — log-group CRUD</li>
 * </ul>
 *
 * <p><strong>Architectural note:</strong> {@code SmplManagementClient} is a peer of
 * {@link com.smplkit.SmplClient}, not its owner. Each constructs its own minimal
 * infrastructure. Where {@code SmplClient} composes a {@code SmplManagementClient}
 * (exposed as {@code client.manage()}), it does so by passing in shared infrastructure
 * (HttpClient, registration buffer) via a package-private factory — neither owns the
 * other's transports.</p>
 */
public final class SmplManagementClient implements AutoCloseable {

    /** Context entity CRUD ({@code mgmt.contexts}). */
    public final ContextsClient contexts;
    /** Context-type schemas ({@code mgmt.context_types}). */
    public final ContextTypesClient contextTypes;
    /** Environment CRUD ({@code mgmt.environments}). */
    public final EnvironmentsClient environments;
    /** Account-level settings ({@code mgmt.account_settings}). */
    public final AccountSettingsClient accountSettings;
    /** Config CRUD ({@code mgmt.config} — singular, matches Python). */
    public final ConfigManagement config;
    /** Flag CRUD ({@code mgmt.flags}). */
    public final FlagsManagement flags;
    /** Single-logger CRUD ({@code mgmt.loggers}). */
    public final LoggersClient loggers;
    /** Log-group CRUD ({@code mgmt.log_groups}). */
    public final LogGroupsClient logGroups;

    // --- Internal state (encapsulated; not part of the public contract) ---

    /** JDK HttpClient — may be shared with a parent SmplClient via {@link #sharedWith}. */
    private final HttpClient httpClient;
    /** Context registration buffer — may be shared with a parent SmplClient. */
    private final ContextRegistrationBuffer contextBuffer;
    /** Resolved configuration: api key, base domain, scheme, debug flag, timeout. */
    private final String apiKey;
    private final String baseDomain;
    private final String scheme;
    private final Duration timeout;

    /**
     * Headless runtime sub-clients used solely to expose their {@code .management()}
     * surface (or to back the new {@link LoggersClient}/{@link LogGroupsClient}).
     * Never started; never used for runtime traffic.
     */
    private final ConfigClient configHeadless;
    private final FlagsClient flagsHeadless;
    private final LoggingClient loggingHeadless;

    private SmplManagementClient(ResolvedManagementConfig cfg, Duration timeout,
                                  HttpClient httpClient,
                                  ContextRegistrationBuffer contextBuffer) {
        this.apiKey = cfg.apiKey;
        this.baseDomain = cfg.baseDomain;
        this.scheme = cfg.scheme;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.contextBuffer = contextBuffer;

        String appBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "app", cfg.baseDomain);
        String configBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "config", cfg.baseDomain);
        String flagsBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "flags", cfg.baseDomain);
        String loggingBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "logging", cfg.baseDomain);

        // Build generated API clients privately (one per service).
        ApiClient appApiClient = buildAppApiClient(appBaseUrl, cfg.apiKey, timeout);

        // Direct namespaces on app service
        this.environments = new EnvironmentsClient(appApiClient);
        this.contextTypes = new ContextTypesClient(appApiClient);
        this.contexts = new ContextsClient(appApiClient, contextBuffer);
        this.accountSettings = new AccountSettingsClient(appApiClient, appBaseUrl, cfg.apiKey);

        // Headless config client — only ever used for its management() surface.
        com.smplkit.internal.generated.config.ApiClient configApiClient =
                new com.smplkit.internal.generated.config.ApiClient();
        configApiClient.updateBaseUri(configBaseUrl);
        configApiClient.setRequestInterceptor(authInterceptor(cfg.apiKey));
        configApiClient.setReadTimeout(timeout);
        ConfigsApi configsApi = new ConfigsApi(configApiClient);
        this.configHeadless = new ConfigClient(configsApi, httpClient, cfg.apiKey);
        this.config = configHeadless.management();

        // Headless flags client — only ever used for its management() surface.
        com.smplkit.internal.generated.flags.ApiClient flagsApiClient =
                new com.smplkit.internal.generated.flags.ApiClient();
        flagsApiClient.updateBaseUri(flagsBaseUrl);
        flagsApiClient.setRequestInterceptor(authInterceptor(cfg.apiKey));
        flagsApiClient.setReadTimeout(timeout);
        FlagsApi flagsApi = new FlagsApi(flagsApiClient);
        com.smplkit.internal.generated.app.api.ContextsApi contextsApiForFlags =
                new com.smplkit.internal.generated.app.api.ContextsApi(appApiClient);
        this.flagsHeadless = new FlagsClient(flagsApi, contextsApiForFlags,
                httpClient, cfg.apiKey, flagsBaseUrl, appBaseUrl, timeout);
        this.flags = flagsHeadless.management();

        // Headless logging client — backs LoggersClient and LogGroupsClient.
        com.smplkit.internal.generated.logging.ApiClient loggingApiClient =
                new com.smplkit.internal.generated.logging.ApiClient();
        loggingApiClient.updateBaseUri(loggingBaseUrl);
        loggingApiClient.setRequestInterceptor(authInterceptor(cfg.apiKey));
        loggingApiClient.setReadTimeout(timeout);
        LoggersApi loggersApi = new LoggersApi(loggingApiClient);
        LogGroupsApi logGroupsApi = new LogGroupsApi(loggingApiClient);
        this.loggingHeadless = new LoggingClient(loggersApi, logGroupsApi, httpClient, cfg.apiKey);
        this.loggers = new LoggersClient(loggingHeadless);
        this.logGroups = new LogGroupsClient(loggingHeadless);
    }

    /** Build an ApiClient for the app service. */
    public static ApiClient buildAppApiClient(String baseUrl, String apiKey, Duration timeout) {
        ApiClient client = new ApiClient();
        client.updateBaseUri(baseUrl);
        client.setRequestInterceptor(authInterceptor(apiKey));
        if (timeout != null) client.setReadTimeout(timeout);
        return client;
    }

    private static java.util.function.Consumer<java.net.http.HttpRequest.Builder> authInterceptor(String apiKey) {
        return builder -> builder.header("Authorization", "Bearer " + apiKey);
    }

    /**
     * Construct a {@link SmplManagementClient} resolving credentials from the
     * standard sources (env vars, {@code ~/.smplkit}). Owns its own HttpClient.
     */
    public static SmplManagementClient create() {
        return builder().build();
    }

    /** Construct a {@link SmplManagementClient} with the given API key. */
    public static SmplManagementClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** Returns a builder for {@link SmplManagementClient}. */
    public static SmplManagementClientBuilder builder() {
        return new SmplManagementClientBuilder();
    }

    /**
     * Internal: build from an already-resolved management config. The returned
     * client owns its own {@link HttpClient} and {@link ContextRegistrationBuffer}.
     */
    public static SmplManagementClient fromResolved(ResolvedManagementConfig cfg, Duration timeout) {
        return new SmplManagementClient(cfg, timeout,
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                new ContextRegistrationBuffer());
    }

    /**
     * Package-private factory used by {@link com.smplkit.SmplClient} to share
     * an {@link HttpClient} and {@link ContextRegistrationBuffer} with the parent
     * runtime client. Mirrors the Python SDK's {@code _from_resolved} hook.
     */
    public static SmplManagementClient sharedWith(
            ResolvedManagementConfig cfg, Duration timeout,
            HttpClient httpClient, ContextRegistrationBuffer contextBuffer) {
        return new SmplManagementClient(cfg, timeout, httpClient, contextBuffer);
    }

    /**
     * Returns the resolved API key. Informational — exposed because the customer
     * provided it; not a hatch into internal infrastructure.
     */
    public String apiKey() { return apiKey; }
    /** Returns the configured base domain. */
    public String baseDomain() { return baseDomain; }
    /** Returns the configured URL scheme. */
    public String scheme() { return scheme; }
    /** Returns the configured request timeout. */
    public Duration timeout() { return timeout; }

    @Override
    public void close() {
        // No persistent resources beyond the shared HttpClient (managed by JDK).
        // Future: drain pending registration buffers if a periodic flush is added here.
    }
}
