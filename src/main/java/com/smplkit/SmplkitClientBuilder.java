package com.smplkit;

import java.time.Duration;
import java.util.Objects;

/**
 * Builder for constructing {@link SmplkitClient} instances.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * SmplkitClient client = SmplkitClient.builder()
 *     .apiKey("sk_api_...")
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 * }</pre>
 */
public final class SmplkitClientBuilder {

    private String apiKey;
    private Duration timeout = Duration.ofSeconds(30);

    SmplkitClientBuilder() {
        // Package-private: use SmplkitClient.builder()
    }

    /**
     * Sets the API key for authentication.
     *
     * @param apiKey the bearer token (API key)
     * @return this builder
     */
    public SmplkitClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Sets the request timeout. Defaults to 30 seconds.
     *
     * @param timeout the timeout duration
     * @return this builder
     */
    public SmplkitClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    /**
     * Builds and returns a new {@link SmplkitClient}.
     *
     * @return the configured client
     * @throws NullPointerException if apiKey has not been set
     */
    public SmplkitClient build() {
        Objects.requireNonNull(apiKey, "apiKey must be set before calling build()");
        return new SmplkitClient(apiKey, timeout);
    }
}
