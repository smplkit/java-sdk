package com.smplkit;

import com.smplkit.config.ConfigClient;
import com.smplkit.flags.FlagsClient;
import com.smplkit.flags.SharedWebSocket;
import com.smplkit.internal.generated.config.ApiClient;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.flags.api.FlagsApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Top-level entry point for the smplkit Java SDK.
 *
 * <p>Use the {@link #builder()} method to construct an instance:</p>
 * <pre>{@code
 * try (SmplClient client = SmplClient.builder()
 *         .apiKey("sk_api_...")
 *         .environment("production")
 *         .service("my-service")
 *         .build()) {
 *     client.connect();
 *     boolean enabled = myFlag.get();
 * }
 * }</pre>
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources.</p>
 */
public final class SmplClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("smplkit");
    private static final String CONFIG_BASE_URL = "https://config.smplkit.com";
    private static final String FLAGS_BASE_URL = "https://flags.smplkit.com";
    private static final String APP_BASE_URL = "https://app.smplkit.com";

    private ConfigClient config;
    private FlagsClient flags;
    private final SharedWebSocket sharedWs;
    private final HttpClient httpClient;
    private final String environment;
    private final String service;
    private final String apiKey;
    private final Duration timeout;
    private volatile boolean connected;

    /**
     * Creates a new SmplClient. Package-private; use {@link #builder()}.
     */
    SmplClient(String apiKey, String environment, String service, Duration timeout) {
        this.apiKey = apiKey;
        this.environment = environment;
        this.service = service;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        this.sharedWs = new SharedWebSocket(httpClient, APP_BASE_URL, apiKey);
        this.config = buildConfigClient(httpClient, apiKey, timeout);
        this.flags = buildFlagsClient(httpClient, apiKey, timeout, sharedWs);
        this.flags.setParentService(service);
    }

    /**
     * Package-private constructor for testing with a custom HttpClient.
     */
    SmplClient(HttpClient httpClient, String apiKey, String environment, String service, Duration timeout) {
        this.apiKey = apiKey;
        this.environment = environment;
        this.service = service;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.sharedWs = new SharedWebSocket(httpClient, APP_BASE_URL, apiKey);
        this.config = buildConfigClient(httpClient, apiKey, timeout);
        this.flags = buildFlagsClient(httpClient, apiKey, timeout, sharedWs);
        this.flags.setParentService(service);
    }

    /**
     * Package-private constructor for testing with injectable sub-clients.
     */
    SmplClient(HttpClient httpClient, String apiKey, String environment, String service,
               Duration timeout, FlagsClient flags, ConfigClient config) {
        this.apiKey = apiKey;
        this.environment = environment;
        this.service = service;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.sharedWs = new SharedWebSocket(httpClient, APP_BASE_URL, apiKey);
        this.config = config;
        this.flags = flags;
        this.flags.setParentService(service);
    }

    /**
     * Connects to the smplkit platform.
     *
     * <p>Registers the service context (if configured), fetches flag definitions,
     * and starts listening for real-time updates via WebSocket. This method is
     * idempotent — calling it multiple times has no additional effect.</p>
     */
    public void connect() {
        if (connected) return;

        // Register service context (fire-and-forget)
        if (service != null) {
            registerServiceContext();
        }

        // Connect flags runtime
        flags.connectInternal(environment);

        // Connect config runtime
        config.connectInternal(environment);

        // Connect shared WebSocket
        sharedWs.ensureConnected(Duration.ofSeconds(10));

        connected = true;
    }

    private void registerServiceContext() {
        try {
            Map<String, Object> context = Map.of(
                    "type", "service",
                    "key", service,
                    "attributes", Map.of("name", service));
            Map<String, Object> body = Map.of("contexts", List.of(context));

            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(APP_BASE_URL + "/api/v1/contexts/bulk"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to register service context", e);
        }
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
     */
    public ConfigClient config() {
        return config;
    }

    /**
     * Returns the Flags service client.
     */
    public FlagsClient flags() {
        return flags;
    }

    /**
     * Returns the configured environment.
     */
    public String environment() {
        return environment;
    }

    /**
     * Returns the configured service name, or null if not set.
     */
    public String service() {
        return service;
    }

    /**
     * Returns whether the client is connected.
     */
    public boolean isConnected() {
        return connected;
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
     * Creates a new {@link SmplClient} with the given API key and environment.
     *
     * @param apiKey      the API key
     * @param environment the target environment
     * @return a new client
     */
    public static SmplClient create(String apiKey, String environment) {
        return builder().apiKey(apiKey).environment(environment).build();
    }

    /**
     * Returns a new builder for constructing {@link SmplClient} instances.
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
