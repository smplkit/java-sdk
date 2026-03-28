package com.smplkit;

import com.smplkit.config.ConfigClient;
import com.smplkit.internal.Auth;
import com.smplkit.internal.Transport;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Top-level entry point for the smplkit Java SDK.
 *
 * <p>Use the {@link #builder()} method to construct an instance:</p>
 * <pre>{@code
 * try (SmplkitClient client = SmplkitClient.builder()
 *         .apiKey("sk_api_...")
 *         .build()) {
 *     Config cfg = client.config().get("my-config-id");
 * }
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources.</p>
 */
public final class SmplkitClient implements AutoCloseable {

    private final ConfigClient config;
    private final HttpClient httpClient;
    private final Transport transport;

    /**
     * Creates a new SmplkitClient. Package-private; use {@link #builder()}.
     *
     * @param apiKey  the API key
     * @param timeout the request timeout
     */
    SmplkitClient(String apiKey, Duration timeout) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        Auth auth = new Auth(apiKey);
        this.transport = new Transport(httpClient, auth, timeout);
        this.config = new ConfigClient(transport);
    }

    /**
     * Package-private constructor for testing with a custom HttpClient.
     *
     * @param httpClient the HTTP client to use
     * @param apiKey     the API key
     * @param timeout    the request timeout
     */
    SmplkitClient(HttpClient httpClient, String apiKey, Duration timeout) {
        this.httpClient = httpClient;
        Auth auth = new Auth(apiKey);
        this.transport = new Transport(httpClient, auth, timeout);
        this.config = new ConfigClient(transport);
    }

    /**
     * Returns the Config service client.
     *
     * @return the config client
     */
    public ConfigClient config() {
        return config;
    }

    /**
     * Returns a new builder for constructing {@link SmplkitClient} instances.
     *
     * @return a new builder
     */
    public static SmplkitClientBuilder builder() {
        return new SmplkitClientBuilder();
    }

    /**
     * Closes the underlying HTTP client resources.
     */
    @Override
    public void close() {
        // java.net.http.HttpClient does not require explicit closing in JDK 17,
        // but we implement AutoCloseable for forward compatibility and resource management.
    }

    /**
     * Returns the underlying transport. Package-private for testing.
     *
     * @return the transport
     */
    Transport transport() {
        return transport;
    }
}
