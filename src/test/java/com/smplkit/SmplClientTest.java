package com.smplkit;

import com.smplkit.config.ConfigClient;
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
                .build()) {
            assertNotNull(client);
            assertNotNull(client.config());
        }
    }

    @Test
    void builderConstructsClientWithCustomOptions() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .timeout(Duration.ofSeconds(60))
                .build()) {
            assertNotNull(client);
            assertNotNull(client.config());
        }
    }

    @Test
    void configReturnsConfigClient() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .build()) {
            ConfigClient config = client.config();
            assertNotNull(config);
            assertSame(config, client.config(), "config() should return the same instance");
        }
    }

    @Test
    void builderWithoutApiKeyOrEnv_throwsSmplException() {
        // When no explicit key, no env var, and no config file, build() should throw
        // SmplException. This test relies on SMPLKIT_API_KEY not being set in the
        // test environment and ~/.smplkit not existing with a valid key.
        // In CI this is always true.
        SmplClientBuilder builder = SmplClient.builder();
        String envKey = System.getenv("SMPLKIT_API_KEY");
        if (envKey == null || envKey.isEmpty()) {
            assertThrows(com.smplkit.errors.SmplException.class, builder::build);
        }
    }

    @Test
    void builderRejectsNullApiKey() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().apiKey(null));
    }

    @Test
    void builderRejectsNullTimeout() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().timeout(null));
    }

    @Test
    void createNoArg_coversStaticFactory() throws Exception {
        // Set SMPLKIT_API_KEY via reflection so create() resolves successfully.
        setEnv("SMPLKIT_API_KEY", "sk_api_test_create");
        try {
            SmplClient result = SmplClient.create();
            assertNotNull(result);
            result.close();
        } finally {
            clearEnv("SMPLKIT_API_KEY");
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
                .build();
        assertDoesNotThrow(client::close);
        // Should be safe to close twice
        assertDoesNotThrow(client::close);
    }

    @Test
    void packagePrivateConstructorWithHttpClient_createsClient() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SmplClient client = new SmplClient(httpClient, "test-key",
                Duration.ofSeconds(30));
        assertNotNull(client);
        assertNotNull(client.config());
    }
}
