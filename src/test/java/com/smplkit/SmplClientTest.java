package com.smplkit;

import com.smplkit.config.ConfigClient;
import com.smplkit.errors.SmplException;
import com.smplkit.flags.FlagsClient;
import com.smplkit.logging.LoggingClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SmplClient} and {@link SmplClientBuilder}.
 */
class SmplClientTest {

    @Test
    void builderConstructsClientWithDefaults() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
            assertNotNull(client.config());
            assertEquals("test", client.environment());
            assertEquals("test-service", client.service());
        }
    }

    @Test
    void builderConstructsClientWithCustomOptions() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("staging")
                .service("test-service")
                .timeout(Duration.ofSeconds(60))
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
            assertNotNull(client.config());
        }
    }

    @Test
    void builderConstructsClientWithService() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("production")
                .service("my-service")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
            assertEquals("production", client.environment());
            assertEquals("my-service", client.service());
        }
    }

    @Test
    void configReturnsConfigClient() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            ConfigClient config = client.config();
            assertNotNull(config);
            assertSame(config, client.config(), "config() should return the same instance");
        }
    }

    @Test
    void flagsReturnsFlagsClient() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            FlagsClient flags = client.flags();
            assertNotNull(flags);
            assertSame(flags, client.flags(), "flags() should return the same instance");
        }
    }

    @Test
    void loggingReturnsLoggingClient() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            LoggingClient logging = client.logging();
            assertNotNull(logging);
            assertSame(logging, client.logging(), "logging() should return the same instance");
        }
    }

    @Test
    void builderWithoutEnvironment_throwsSmplException() {
        SmplClientBuilder builder = SmplClient.builder().apiKey("test-key").service("test-service");
        assertThrows(SmplException.class, () -> builder.resolveEnvironment(null));
    }

    @Test
    void builderWithEnvironmentEnvVar_resolvesFromEnvVar() {
        SmplClientBuilder builder = SmplClient.builder().apiKey("test-key").service("test-service");
        assertEquals("from-env", builder.resolveEnvironment("from-env"));
    }

    @Test
    void builderWithExplicitEnvironment_overridesEnvVar() {
        SmplClientBuilder builder = SmplClient.builder()
                .apiKey("test-key")
                .environment("explicit");
        assertEquals("explicit", builder.resolveEnvironment("from-env"));
    }

    @Test
    void builderWithServiceEnvVar_resolvesFromEnvVar() {
        SmplClientBuilder builder = SmplClient.builder();
        assertEquals("svc-from-env", builder.resolveService("svc-from-env"));
    }

    @Test
    void builderWithExplicitService_overridesEnvVar() {
        SmplClientBuilder builder = SmplClient.builder().service("explicit-svc");
        assertEquals("explicit-svc", builder.resolveService("from-env"));
    }

    @Test
    void builderWithNoService_throwsSmplException() {
        SmplClientBuilder builder = SmplClient.builder();
        SmplException ex = assertThrows(SmplException.class, () -> builder.resolveService(null));
        assertTrue(ex.getMessage().contains("No service provided"));
        assertTrue(ex.getMessage().contains(".service()"));
        assertTrue(ex.getMessage().contains("SMPLKIT_SERVICE"));
    }

    @Test
    void builderWithoutApiKeyOrEnv_throwsSmplException() {
        String envKey = System.getenv("SMPLKIT_API_KEY");
        boolean hasConfigFile = java.nio.file.Files.exists(
                java.nio.file.Paths.get(System.getProperty("user.home"), ".smplkit"));
        if (envKey == null || envKey.isEmpty()) {
            if (!hasConfigFile) {
                SmplClientBuilder builder = SmplClient.builder().environment("test").service("test-service");
                assertThrows(SmplException.class, builder::build);
            }
        }
    }

    @Test
    void builderRejectsNullApiKey() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().apiKey(null));
    }

    @Test
    void builderRejectsNullEnvironment() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().environment(null));
    }

    @Test
    void builderRejectsNullService() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().service(null));
    }

    @Test
    void builderRejectsNullTimeout() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().timeout(null));
    }

    @Test
    void createNoArg_coversStaticFactory() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_api_test_create");
        setEnv("SMPLKIT_ENVIRONMENT", "test-env");
        setEnv("SMPLKIT_SERVICE", "test-service");
        try {
            SmplClient result = SmplClient.create();
            assertNotNull(result);
            assertEquals("test-env", result.environment());
            assertEquals("test-service", result.service());
            result.close();
        } finally {
            clearEnv("SMPLKIT_API_KEY");
            clearEnv("SMPLKIT_ENVIRONMENT");
            clearEnv("SMPLKIT_SERVICE");
        }
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(env)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearEnv(String key) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(env)).remove(key);
    }

    @Test
    void clientIsAutoCloseable() {
        SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build();
        assertDoesNotThrow(client::close);
        // Should be safe to close twice
        assertDoesNotThrow(client::close);
    }

    @Test
    void authInterceptor_addsAuthorizationHeader() {
        java.util.function.Consumer<java.net.http.HttpRequest.Builder> interceptor =
                SmplClient.authInterceptor("sk_test_123");

        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://example.com"));
        interceptor.accept(builder);
        java.net.http.HttpRequest request = builder.build();

        assertTrue(request.headers().map().containsKey("Authorization"));
        assertEquals("Bearer sk_test_123",
                request.headers().firstValue("Authorization").orElse(""));
    }

    @Test
    void packagePrivateConstructorWithHttpClient_createsClient() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SmplClient client = new SmplClient(httpClient, "test-key", "test", "test-service",
                Duration.ofSeconds(30));
        assertNotNull(client);
        assertNotNull(client.config());
        assertEquals("test", client.environment());
        assertEquals("test-service", client.service());
    }

    @Test
    void packagePrivateConstructorWithInjectableSubClients_createsClient() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

        com.smplkit.internal.generated.flags.ApiClient flagsApiClient =
                new com.smplkit.internal.generated.flags.ApiClient();
        flagsApiClient.updateBaseUri("https://flags.smplkit.com");
        flagsApiClient.setRequestInterceptor(SmplClient.authInterceptor("test-key"));
        flagsApiClient.setReadTimeout(Duration.ofSeconds(5));
        com.smplkit.internal.generated.flags.api.FlagsApi flagsApi =
                new com.smplkit.internal.generated.flags.api.FlagsApi(flagsApiClient);

        com.smplkit.flags.FlagsClient flagsClient = new com.smplkit.flags.FlagsClient(
                flagsApi, null, httpClient, "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));

        com.smplkit.internal.generated.config.ApiClient configApiClient =
                new com.smplkit.internal.generated.config.ApiClient();
        configApiClient.updateBaseUri("https://config.smplkit.com");
        configApiClient.setRequestInterceptor(
                b -> b.header("Authorization", "Bearer test-key"));
        configApiClient.setReadTimeout(Duration.ofSeconds(5));
        com.smplkit.internal.generated.config.api.ConfigsApi configsApi =
                new com.smplkit.internal.generated.config.api.ConfigsApi(configApiClient);
        com.smplkit.config.ConfigClient configClient =
                new com.smplkit.config.ConfigClient(configsApi, httpClient, "test-key");

        SmplClient client = new SmplClient(httpClient, "test-key", "test", "test-service",
                Duration.ofSeconds(5), flagsClient, configClient);
        assertNotNull(client);
        assertNotNull(client.config());
        assertNotNull(client.flags());
        assertEquals("test", client.environment());
        assertEquals("test-service", client.service());
        client.close();
    }

    @Test
    void createWithApiKeyEnvironmentAndService() {
        try (SmplClient client = SmplClient.create("test-key", "test", "test-service")) {
            assertNotNull(client);
            assertEquals("test", client.environment());
            assertEquals("test-service", client.service());
        }
    }

    // ---- baseDomain / scheme tests ----

    @Test
    void serviceUrl_defaultDomain() {
        assertEquals("https://config.smplkit.com",
                SmplClient.serviceUrl("https", "config", "smplkit.com"));
        assertEquals("https://flags.smplkit.com",
                SmplClient.serviceUrl("https", "flags", "smplkit.com"));
        assertEquals("https://app.smplkit.com",
                SmplClient.serviceUrl("https", "app", "smplkit.com"));
        assertEquals("https://logging.smplkit.com",
                SmplClient.serviceUrl("https", "logging", "smplkit.com"));
    }

    @Test
    void serviceUrl_customDomainAndScheme() {
        assertEquals("http://config.internal.example.com",
                SmplClient.serviceUrl("http", "config", "internal.example.com"));
    }

    @Test
    void builderDefaultsToSmplkitDotCom() {
        // Defaults: baseDomain=smplkit.com, scheme=https — just verify the builder accepts them
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void builderAcceptsCustomBaseDomain() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .baseDomain("internal.example.com")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void builderAcceptsCustomScheme() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .scheme("http")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void builderRejectsNullBaseDomain() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().baseDomain(null));
    }

    @Test
    void builderRejectsNullScheme() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().scheme(null));
    }

    @Test
    void defaultConstantsMatchExpectedValues() {
        assertEquals("smplkit.com", SmplClient.DEFAULT_BASE_DOMAIN);
        assertEquals("https", SmplClient.DEFAULT_SCHEME);
    }
}
