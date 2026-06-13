package com.smplkit.config;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.config.ApiClient;
import com.smplkit.internal.generated.config.api.ConfigsApi;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for a standalone {@link ConfigClient}.
 *
 * <p>Resolves credentials via {@link ConfigResolver#resolveClient}, builds
 * and owns its own config transport, and opens its own {@link com.smplkit.SharedWebSocket}
 * lazily on first live use. Mirrors {@link com.smplkit.SmplClientBuilder} but
 * scoped to the config service.</p>
 */
public final class ConfigClientBuilder {

    private String profile;
    private String apiKey;
    private String baseUrl;
    private String baseDomain;
    private String scheme;
    private String environment;
    private Duration timeout = Duration.ofSeconds(30);
    private Boolean debug;
    private Map<String, String> extraHeaders;

    ConfigClientBuilder() {
        // Package-private: use ConfigClient.builder()
    }

    /**
     * Sets the configuration profile to use from {@code ~/.smplkit}.
     *
     * @param profile the named {@code ~/.smplkit} profile section
     * @return this builder
     */
    public ConfigClientBuilder profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        return this;
    }

    /**
     * Sets the API key for authentication.
     *
     * @param apiKey the API key to authenticate with
     * @return this builder
     */
    public ConfigClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Sets the full config-service base URL. Usually resolved from
     * {@code baseDomain}/{@code scheme}; supplied directly only when pointing
     * at a non-standard endpoint.
     *
     * @param baseUrl the full config-service base URL
     * @return this builder
     */
    public ConfigClientBuilder baseUrl(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        return this;
    }

    /**
     * Overrides the base domain for all service URLs. Defaults to {@code "smplkit.com"}.
     *
     * @param baseDomain the base domain for API requests
     * @return this builder
     */
    public ConfigClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    /**
     * Overrides the URL scheme for all service URLs. Defaults to {@code "https"}.
     *
     * @param scheme the URL scheme (e.g. {@code "https"})
     * @return this builder
     */
    public ConfigClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    /**
     * Sets the deployment environment used to resolve runtime config values and
     * to scope discovery declarations.
     *
     * @param environment the deployment environment name
     * @return this builder
     */
    public ConfigClientBuilder environment(String environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        return this;
    }

    /**
     * Sets the request timeout. Defaults to 30 seconds.
     *
     * @param timeout the request timeout
     * @return this builder
     */
    public ConfigClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    /**
     * Enables SDK debug output.
     *
     * @param debug {@code true} to enable SDK debug logging
     * @return this builder
     */
    public ConfigClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /**
     * Adds additional HTTP headers sent on every request made by this client.
     *
     * @param extraHeaders headers attached to every request
     * @return this builder
     */
    public ConfigClientBuilder extraHeaders(Map<String, String> extraHeaders) {
        Objects.requireNonNull(extraHeaders, "extraHeaders must not be null");
        this.extraHeaders = Collections.unmodifiableMap(new HashMap<>(extraHeaders));
        return this;
    }

    /**
     * Builds and returns a standalone {@link ConfigClient}.
     *
     * @return a new standalone {@link ConfigClient} owning its own transport
     */
    public ConfigClient build() {
        ResolvedClientConfig cfg = ConfigResolver.resolveClient(
                profile, apiKey, baseDomain, scheme, debug);

        String configBaseUrl = baseUrl != null
                ? baseUrl
                : ConfigResolver.serviceUrl(cfg.scheme, "config", cfg.baseDomain);
        String appBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "app", cfg.baseDomain);

        HttpClient httpClient = HttpClients.http11(timeout);

        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(configBaseUrl);
        apiClient.setRequestInterceptor(HttpClients.compositeInterceptor(cfg.apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);
        ConfigsApi configsApi = new ConfigsApi(apiClient);

        return new ConfigClient(configsApi, httpClient, cfg.apiKey, appBaseUrl, environment, null);
    }
}
