package com.smplkit;

import com.smplkit.config.ConfigClient;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotConnectedException;
import com.smplkit.flags.FlagsClient;
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
                .build()) {
            assertNotNull(client);
            assertNotNull(client.config());
            assertEquals("test", client.environment());
            assertNull(client.service());
        }
    }

    @Test
    void builderConstructsClientWithCustomOptions() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("staging")
                .timeout(Duration.ofSeconds(60))
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
                .build()) {
            FlagsClient flags = client.flags();
            assertNotNull(flags);
            assertSame(flags, client.flags(), "flags() should return the same instance");
        }
    }

    @Test
    void builderWithoutEnvironment_throwsSmplException() {
        SmplClientBuilder builder = SmplClient.builder().apiKey("test-key");
        // resolveEnvironment with null env var should throw
        assertThrows(SmplException.class, () -> builder.resolveEnvironment(null));
    }

    @Test
    void builderWithEnvironmentEnvVar_resolvesFromEnvVar() {
        SmplClientBuilder builder = SmplClient.builder().apiKey("test-key");
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
    void builderWithNoService_returnsNull() {
        SmplClientBuilder builder = SmplClient.builder();
        assertNull(builder.resolveService(null));
    }

    @Test
    void builderWithoutApiKeyOrEnv_throwsSmplException() {
        String envKey = System.getenv("SMPLKIT_API_KEY");
        boolean hasConfigFile = java.nio.file.Files.exists(
                java.nio.file.Paths.get(System.getProperty("user.home"), ".smplkit"));
        if (envKey == null || envKey.isEmpty()) {
            if (!hasConfigFile) {
                SmplClientBuilder builder = SmplClient.builder().environment("test");
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
        try {
            SmplClient result = SmplClient.create();
            assertNotNull(result);
            assertEquals("test-env", result.environment());
            result.close();
        } finally {
            clearEnv("SMPLKIT_API_KEY");
            clearEnv("SMPLKIT_ENVIRONMENT");
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
                .build();
        assertDoesNotThrow(client::close);
        // Should be safe to close twice
        assertDoesNotThrow(client::close);
    }

    @Test
    void isConnected_falseBeforeConnect() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .build()) {
            assertFalse(client.isConnected());
        }
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
        SmplClient client = new SmplClient(httpClient, "test-key", "test", null,
                Duration.ofSeconds(30));
        assertNotNull(client);
        assertNotNull(client.config());
        assertEquals("test", client.environment());
        assertNull(client.service());
    }
}
