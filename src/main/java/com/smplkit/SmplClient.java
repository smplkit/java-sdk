package com.smplkit;

import com.smplkit.config.ConfigClient;
import com.smplkit.flags.FlagsClient;
import com.smplkit.flags.SharedWebSocket;
import com.smplkit.internal.generated.config.ApiClient;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.flags.api.FlagsApi;

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
 *     client.flags().connect("production");
 * }
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources.</p>
 */
public final class SmplClient implements AutoCloseable {

    private static final String CONFIG_BASE_URL = "https://config.smplkit.com";
    private static final String FLAGS_BASE_URL = "https://flags.smplkit.com";
    private static final String APP_BASE_URL = "https://app.smplkit.com";

    private final ConfigClient config;
    private final FlagsClient flags;
    private final SharedWebSocket sharedWs;
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
        this.sharedWs = new SharedWebSocket(httpClient, APP_BASE_URL, apiKey);
        this.config = buildConfigClient(httpClient, apiKey, timeout);
        this.flags = buildFlagsClient(httpClient, apiKey, timeout, sharedWs);
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
        this.sharedWs = new SharedWebSocket(httpClient, APP_BASE_URL, apiKey);
        this.config = buildConfigClient(httpClient, apiKey, timeout);
        this.flags = buildFlagsClient(httpClient, apiKey, timeout, sharedWs);
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

    private static FlagsClient buildFlagsClient(HttpClient httpClient, String apiKey,
                                                 Duration timeout, SharedWebSocket sharedWs) {
        com.smplkit.internal.generated.flags.ApiClient flagsApiClient =
                new com.smplkit.internal.generated.flags.ApiClient();
        flagsApiClient.updateBaseUri(FLAGS_BASE_URL);
        flagsApiClient.setRequestInterceptor(authInterceptor(apiKey));
        flagsApiClient.setReadTimeout(timeout);
        FlagsApi flagsApi = new FlagsApi(flagsApiClient);
        FlagsClient client = new FlagsClient(flagsApi, httpClient, apiKey,
                FLAGS_BASE_URL, APP_BASE_URL, timeout);
        client.setSharedWs(sharedWs);
        return client;
    }

    /** Package-private for testing: builds the auth header interceptor. */
    static java.util.function.Consumer<java.net.http.HttpRequest.Builder> authInterceptor(String apiKey) {
        return builder -> builder.header("Authorization", "Bearer " + apiKey);
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
     * Returns the Flags service client.
     *
     * @return the flags client
     */
    public FlagsClient flags() {
        return flags;
    }

    /**
     * Creates a new {@link SmplClient} with automatic API key resolution.
     * The API key is resolved from the {@code SMPLKIT_API_KEY} environment
     * variable or the {@code ~/.smplkit} configuration file.
     *
     * @return a new client
     * @throws com.smplkit.errors.SmplException if no API key can be resolved
     */
    public static SmplClient create() {
        return builder().build();
    }

    /**
     * Creates a new {@link SmplClient} with the given API key.
     *
     * @param apiKey the API key
     * @return a new client
     */
    public static SmplClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
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
     * Closes the underlying resources including the shared WebSocket.
     */
    @Override
    public void close() {
        sharedWs.close();
    }
}
