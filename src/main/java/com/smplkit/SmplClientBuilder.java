package com.smplkit;

import java.time.Duration;
import java.util.Objects;

/**
 * Builder for constructing {@link SmplClient} instances.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * SmplClient client = SmplClient.builder()
 *     .apiKey("sk_api_...")
 *     .environment("production")
 *     .service("my-service")
 *     .build();
 * }</pre>
 */
public final class SmplClientBuilder {

    private String profile;
    private String apiKey;
    private String baseDomain;
    private String scheme;
    private String environment;
    private String service;
    private Duration timeout = Duration.ofSeconds(30);
    private Boolean debug;
    private Boolean disableTelemetry;

    SmplClientBuilder() {
        // Package-private: use SmplClient.builder()
    }

    /**
     * Sets the configuration profile to use from {@code ~/.smplkit}.
     *
     * <p>If not set, falls back to the {@code SMPLKIT_PROFILE} environment variable,
     * then to {@code "default"}.</p>
     *
     * @param profile the profile name
     * @return this builder
     */
    public SmplClientBuilder profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        return this;
    }

    /**
     * Sets the API key for authentication.
     *
     * @param apiKey the API key
     * @return this builder
     */
    public SmplClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Overrides the base domain for all service URLs. Defaults to {@code "smplkit.com"}.
     *
     * <p>Service URLs are computed as {@code {scheme}://{service}.{baseDomain}}.
     * Use this to point the SDK at a self-hosted or staging deployment.</p>
     *
     * @param baseDomain the base domain (e.g. {@code "smplkit.com"})
     * @return this builder
     */
    public SmplClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    /**
     * Overrides the URL scheme for all service URLs. Defaults to {@code "https"}.
     *
     * @param scheme the URL scheme (e.g. {@code "http"} or {@code "https"})
     * @return this builder
     */
    public SmplClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    /**
     * Sets the target environment (e.g. "production", "staging").
     *
     * <p>If not set, falls back to the {@code SMPLKIT_ENVIRONMENT} environment variable,
     * then to the config file. Required — build() will throw if no environment can be resolved.</p>
     *
     * @param environment the environment name
     * @return this builder
     */
    public SmplClientBuilder environment(String environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        return this;
    }

    /**
     * Sets the service name.
     *
     * <p>If not set, falls back to the {@code SMPLKIT_SERVICE} environment variable,
     * then to the config file. Required — build() will throw if no service can be resolved.</p>
     *
     * @param service the service name
     * @return this builder
     */
    public SmplClientBuilder service(String service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        return this;
    }

    /**
     * Sets the request timeout. Defaults to 30 seconds.
     *
     * @param timeout the timeout duration
     * @return this builder
     */
    public SmplClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    /**
     * Enables SDK debug output. When true, verbose diagnostics are written to stderr.
     *
     * @param debug true to enable debug output
     * @return this builder
     */
    public SmplClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /**
     * Disables SDK telemetry reporting. When true, no usage metrics are sent.
     *
     * @param disable true to disable telemetry
     * @return this builder
     */
    public SmplClientBuilder disableTelemetry(boolean disable) {
        this.disableTelemetry = disable;
        return this;
    }

    /**
     * Builds and returns a new {@link SmplClient}.
     *
     * <p>Resolution order (4-step):</p>
     * <ol>
     *   <li>SDK hardcoded defaults</li>
     *   <li>Configuration file ({@code ~/.smplkit}): [common] + selected profile</li>
     *   <li>Environment variables ({@code SMPLKIT_*})</li>
     *   <li>Builder arguments ({@link #apiKey}, {@link #environment}, etc.)</li>
     * </ol>
     *
     * @return the configured client
     * @throws com.smplkit.errors.SmplError if environment, service, or API key cannot be resolved
     */
    public SmplClient build() {
        ConfigResolver.ResolvedConfig config = ConfigResolver.resolve(
                profile, apiKey, baseDomain, scheme, environment, service,
                debug, disableTelemetry);
        return new SmplClient(config, timeout);
    }
}
