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
 * — no service registration, no metrics thread, no WebSocket, no logger discovery.</p>
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

    // Internals — exposed at package-public visibility so SmplClient
    // (a different package) can share HTTP transports without re-creating them.
    public final ApiClient appApiClient;
    public final ConfigClient configRuntime;
    public final FlagsClient flagsRuntime;
    public final LoggingClient loggingRuntime;
    public final HttpClient httpClient;
    public final ContextRegistrationBuffer contextBuffer;
    public final String apiKey;
    public final String baseDomain;
    public final String scheme;
    public final Duration timeout;

    private SmplManagementClient(ResolvedManagementConfig cfg, Duration timeout) {
        this.apiKey = cfg.apiKey;
        this.baseDomain = cfg.baseDomain;
        this.scheme = cfg.scheme;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.contextBuffer = new ContextRegistrationBuffer();

        String appBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "app", cfg.baseDomain);
        String configBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "config", cfg.baseDomain);
        String flagsBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "flags", cfg.baseDomain);
        String loggingBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "logging", cfg.baseDomain);

        this.appApiClient = buildAppApiClient(appBaseUrl, cfg.apiKey, timeout);
        this.environments = new EnvironmentsClient(appApiClient);
        this.contextTypes = new ContextTypesClient(appApiClient);
        this.contexts = new ContextsClient(appApiClient, contextBuffer);
        this.accountSettings = new AccountSettingsClient(appApiClient, appBaseUrl, cfg.apiKey);

        // Config: build a runtime ConfigClient (no start) and wrap with mgmt.config = .management()
        com.smplkit.internal.generated.config.ApiClient configApiClient =
                new com.smplkit.internal.generated.config.ApiClient();
        configApiClient.updateBaseUri(configBaseUrl);
        configApiClient.setRequestInterceptor(authInterceptor(cfg.apiKey));
        configApiClient.setReadTimeout(timeout);
        ConfigsApi configsApi = new ConfigsApi(configApiClient);
        this.configRuntime = new ConfigClient(configsApi, httpClient, cfg.apiKey);
        this.config = configRuntime.management();

        // Flags: same — build a runtime FlagsClient (no start) then wrap with .management()
        com.smplkit.internal.generated.flags.ApiClient flagsApiClient =
                new com.smplkit.internal.generated.flags.ApiClient();
        flagsApiClient.updateBaseUri(flagsBaseUrl);
        flagsApiClient.setRequestInterceptor(authInterceptor(cfg.apiKey));
        flagsApiClient.setReadTimeout(timeout);
        FlagsApi flagsApi = new FlagsApi(flagsApiClient);

        com.smplkit.internal.generated.app.ApiException unused;  // anchor for compiler
        com.smplkit.internal.generated.app.ApiClient sharedAppApiClient = appApiClient;
        com.smplkit.internal.generated.app.api.ContextsApi contextsApiForFlags =
                new com.smplkit.internal.generated.app.api.ContextsApi(sharedAppApiClient);

        this.flagsRuntime = new FlagsClient(flagsApi, contextsApiForFlags,
                httpClient, cfg.apiKey, flagsBaseUrl, appBaseUrl, timeout);
        this.flags = flagsRuntime.management();

        // Logging: build a runtime LoggingClient that's never start()ed
        com.smplkit.internal.generated.logging.ApiClient loggingApiClient =
                new com.smplkit.internal.generated.logging.ApiClient();
        loggingApiClient.updateBaseUri(loggingBaseUrl);
        loggingApiClient.setRequestInterceptor(authInterceptor(cfg.apiKey));
        loggingApiClient.setReadTimeout(timeout);
        LoggersApi loggersApi = new LoggersApi(loggingApiClient);
        LogGroupsApi logGroupsApi = new LogGroupsApi(loggingApiClient);
        this.loggingRuntime = new LoggingClient(loggersApi, logGroupsApi, httpClient, cfg.apiKey);
        this.loggers = new LoggersClient(loggingRuntime);
        this.logGroups = new LogGroupsClient(loggingRuntime);
    }

    /** Build an ApiClient for the app service. */
    static ApiClient buildAppApiClient(String baseUrl, String apiKey, Duration timeout) {
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
     * standard sources (env vars, {@code ~/.smplkit}).
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

    /** Internal: build from an already-resolved management config. */
    public static SmplManagementClient fromResolved(ResolvedManagementConfig cfg, Duration timeout) {
        return new SmplManagementClient(cfg, timeout);
    }

    @Override
    public void close() {
        // No persistent resources beyond the shared HttpClient (managed by JDK).
        // Future: drain any pending registration buffers if we add a periodic flush here.
    }
}
