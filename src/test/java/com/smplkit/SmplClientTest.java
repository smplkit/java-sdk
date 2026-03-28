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
    void builderRequiresApiKey() {
        SmplClientBuilder builder = SmplClient.builder();
        assertThrows(NullPointerException.class, builder::build);
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
