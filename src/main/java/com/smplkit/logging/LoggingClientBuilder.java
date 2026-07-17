package com.smplkit.logging;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;
import com.smplkit.internal.Debug;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.logging.ApiClient;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for a standalone {@link LoggingClient}.
 *
 * <p>Resolution order matches {@link com.smplkit.SmplClientBuilder}. A standalone
 * client owns its own logging transport and, on {@link LoggingClient#install()},
 * its own {@link com.smplkit.SharedWebSocket} opened against the app service (the
 * WebSocket gateway lives on the app service, like flags).</p>
 */
public final class LoggingClientBuilder {

    private String profile;
    private String apiKey;
    private String environment;
    private String service;
    private String baseUrl;
    private String baseDomain;
    private String scheme;
    private Boolean debug;
    private boolean streaming = true;
    private Map<String, String> extraHeaders = Map.of();
    private Duration timeout = Duration.ofSeconds(30);

    LoggingClientBuilder() {}

    /**
     * Named {@code ~/.smplkit} profile section to read credentials from.
     *
     * @param profile the profile name
     * @return this builder
     */
    public LoggingClientBuilder profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        return this;
    }

    /**
     * API key. When omitted, it is resolved from the environment or
     * {@code ~/.smplkit}.
     *
     * @param apiKey the API key
     * @return this builder
     */
    public LoggingClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Deployment environment used to resolve runtime levels and to scope
     * discovery declarations. When omitted, it is resolved from
     * {@code SMPLKIT_ENVIRONMENT} or {@code ~/.smplkit}.
     *
     * @param environment the environment name
     * @return this builder
     */
    public LoggingClientBuilder environment(String environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        return this;
    }

    /**
     * Name of the service this client runs in, used to attribute discovered
     * loggers. When omitted, it is resolved from {@code SMPLKIT_SERVICE} or
     * {@code ~/.smplkit}.
     *
     * @param service the service name
     * @return this builder
     */
    public LoggingClientBuilder service(String service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        return this;
    }

    /**
     * Full logging-service base URL. Usually resolved from {@code baseDomain}/{@code scheme}.
     *
     * @param baseUrl the logging-service base URL
     * @return this builder
     */
    public LoggingClientBuilder baseUrl(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        return this;
    }

    /**
     * Base domain for API requests (default {@code "smplkit.com"}).
     *
     * @param baseDomain the base domain
     * @return this builder
     */
    public LoggingClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    /**
     * URL scheme (default {@code "https"}).
     *
     * @param scheme the URL scheme
     * @return this builder
     */
    public LoggingClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    /**
     * Enable SDK debug logging.
     *
     * @param debug {@code true} to enable debug logging
     * @return this builder
     */
    public LoggingClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /**
     * Stream live logger-level updates (default {@code true}):
     * {@link LoggingClient#install()} opens a WebSocket for real-time change
     * events.
     *
     * <p>Pass {@code false} for the stateless live surface:
     * {@code install()} still discovers loggers and fetches and applies
     * levels once (blocking), but no WebSocket or background threads are
     * created; discovery flushes run inline and {@code refresh()} re-fetches
     * on demand — the right shape for serverless and edge runtimes, whose
     * short-lived instances cannot hold open connections.</p>
     *
     * @param streaming whether to stream live level updates
     * @return this builder
     */
    public LoggingClientBuilder streaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    /**
     * Extra headers attached to every request.
     *
     * @param extraHeaders the headers to attach
     * @return this builder
     */
    public LoggingClientBuilder extraHeaders(Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders != null ? Map.copyOf(extraHeaders) : Map.of();
        return this;
    }

    /**
     * Per-request read timeout.
     *
     * @param timeout the read timeout
     * @return this builder
     */
    public LoggingClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    public LoggingClient build() {
        ResolvedClientConfig cfg = ConfigResolver.resolveClient(
                profile, apiKey, baseDomain, scheme, environment, service, debug);
        if (cfg.debug) {
            Debug.enable();
        }
        String loggingBaseUrl = baseUrl != null
                ? baseUrl
                : ConfigResolver.serviceUrl(cfg.scheme, "logging", cfg.baseDomain);
        // The WebSocket gateway lives on the app service (like flags).
        String appBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "app", cfg.baseDomain);

        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(loggingBaseUrl);
        apiClient.setRequestInterceptor(HttpClients.compositeInterceptor(cfg.apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);

        LoggersApi loggersApi = new LoggersApi(apiClient);
        LogGroupsApi logGroupsApi = new LogGroupsApi(apiClient);

        // A standalone client opens its own WebSocket lazily on install(); give it
        // its own HTTP client for the upgrade handshake.
        HttpClient wsHttpClient = HttpClients.builder().build();

        LoggingClient client = LoggingClient.standalone(
                loggersApi, logGroupsApi, wsHttpClient, cfg.apiKey, appBaseUrl, streaming);
        client.setEnvironment(cfg.environment);
        client.setService(cfg.service);
        return client;
    }
}
