package com.smplkit;

import com.smplkit.errors.SmplException;

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

    private String apiKey;
    private String environment;
    private String service;
    private Duration timeout = Duration.ofSeconds(30);

    SmplClientBuilder() {
        // Package-private: use SmplClient.builder()
    }

    /**
     * Sets the API key for authentication.
     *
     * @param apiKey the bearer token (API key)
     * @return this builder
     */
    public SmplClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Sets the target environment (e.g. "production", "staging").
     *
     * <p>If not set, falls back to the {@code SMPLKIT_ENVIRONMENT} environment variable.
     * Required — build() will throw if no environment can be resolved.</p>
     *
     * @param environment the environment name
     * @return this builder
     */
    public SmplClientBuilder environment(String environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        return this;
    }

    /**
     * Sets the service name for automatic service context injection.
     *
     * <p>If not set, falls back to the {@code SMPLKIT_SERVICE} environment variable.
     * Optional — null is valid (no service context will be injected).</p>
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
     * Builds and returns a new {@link SmplClient}.
     *
     * <p>If no API key was set via {@link #apiKey(String)}, the builder resolves
     * it from the {@code SMPLKIT_API_KEY} environment variable or the
     * {@code ~/.smplkit} configuration file.</p>
     *
     * <p>Environment is required: resolved from {@link #environment(String)} or
     * the {@code SMPLKIT_ENVIRONMENT} environment variable.</p>
     *
     * @return the configured client
     * @throws SmplException if no API key or environment can be resolved
     */
    public SmplClient build() {
        String resolvedKey = ApiKeyResolver.resolve(apiKey);
        String resolvedEnvironment = resolveEnvironment();
        String resolvedService = resolveService();
        return new SmplClient(resolvedKey, resolvedEnvironment, resolvedService, timeout);
    }

    private String resolveEnvironment() {
        return resolveEnvironment(System.getenv("SMPLKIT_ENVIRONMENT"));
    }

    /** Package-private for testing. */
    String resolveEnvironment(String envVar) {
        if (environment != null && !environment.isEmpty()) {
            return environment;
        }
        if (envVar != null && !envVar.isEmpty()) {
            return envVar;
        }
        throw new SmplException(
                "No environment provided. Set one of:\n" +
                "  1. Call .environment() on the builder\n" +
                "  2. Set the SMPLKIT_ENVIRONMENT environment variable",
                0, null);
    }

    private String resolveService() {
        return resolveService(System.getenv("SMPLKIT_SERVICE"));
    }

    /** Package-private for testing. */
    String resolveService(String envVar) {
        if (service != null && !service.isEmpty()) {
            return service;
        }
        if (envVar != null && !envVar.isEmpty()) {
            return envVar;
        }
        return null;
    }
}
