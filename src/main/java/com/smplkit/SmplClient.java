package com.smplkit;

import com.smplkit.config.ConfigClient;
import com.smplkit.internal.generated.config.ApiClient;
import com.smplkit.internal.generated.config.api.ConfigsApi;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Top-level entry point for the smplkit Java SDK.
 *
 * <p>Use the {@link #builder()} method to construct an instance:</p>
 * <pre>{@code
 * try (SmplClient client = SmplClient.builder()
 *         .apiKey("sk_api_...")
 *         .build()) {
 *     Config cfg = client.config().get("my-config-id");
 * }
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources.</p>
 */
public final class SmplClient implements AutoCloseable {

    private static final String CONFIG_BASE_URL = "https://config.smplkit.com";

    private final ConfigClient config;
    private final HttpClient httpClient;

    /**
     * Creates a new SmplClient. Package-private; use {@link #builder()}.
     *
     * @param apiKey  the API key
     * @param timeout the request timeout
     */
    SmplClient(String apiKey, Duration timeout) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        this.config = buildConfigClient(httpClient, apiKey, timeout);
    }

    /**
     * Package-private constructor for testing with a custom HttpClient.
     *
     * @param httpClient the HTTP client to use
     * @param apiKey     the API key
     * @param timeout    the request timeout
     */
    SmplClient(HttpClient httpClient, String apiKey, Duration timeout) {
        this.httpClient = httpClient;
        this.config = buildConfigClient(httpClient, apiKey, timeout);
    }

    private static ConfigClient buildConfigClient(HttpClient httpClient, String apiKey, Duration timeout) {
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(CONFIG_BASE_URL);
        apiClient.setRequestInterceptor(
                builder -> builder.header("Authorization", "Bearer " + apiKey));
        apiClient.setReadTimeout(timeout);
        ConfigsApi configsApi = new ConfigsApi(apiClient);
        return new ConfigClient(configsApi, httpClient, apiKey);
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
     * Returns a new builder for constructing {@link SmplClient} instances.
     *
     * @return a new builder
     */
    public static SmplClientBuilder builder() {
        return new SmplClientBuilder();
    }

    /**
     * Closes the underlying HTTP client resources.
     */
    @Override
    public void close() {
        // java.net.http.HttpClient does not require explicit closing in JDK 17,
        // but we implement AutoCloseable for forward compatibility and resource management.
    }
}
