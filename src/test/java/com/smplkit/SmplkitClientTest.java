package com.smplkit;

import com.smplkit.config.ConfigClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SmplkitClient} and {@link SmplkitClientBuilder}.
 */
class SmplkitClientTest {

    @Test
    void builderConstructsClientWithDefaults() {
        try (SmplkitClient client = SmplkitClient.builder()
                .apiKey("test-key")
                .build()) {
            assertNotNull(client);
            assertNotNull(client.config());
        }
    }

    @Test
    void builderConstructsClientWithCustomOptions() {
        try (SmplkitClient client = SmplkitClient.builder()
                .apiKey("test-key")
                .baseUrl("https://custom.example.com")
                .timeout(Duration.ofSeconds(60))
                .build()) {
            assertNotNull(client);
            assertNotNull(client.config());
        }
    }

    @Test
    void configReturnsConfigClient() {
        try (SmplkitClient client = SmplkitClient.builder()
                .apiKey("test-key")
                .build()) {
            ConfigClient config = client.config();
            assertNotNull(config);
            assertSame(config, client.config(), "config() should return the same instance");
        }
    }

    @Test
    void builderRequiresApiKey() {
        SmplkitClientBuilder builder = SmplkitClient.builder();
        assertThrows(NullPointerException.class, builder::build);
    }

    @Test
    void builderRejectsNullApiKey() {
        assertThrows(NullPointerException.class, () ->
                SmplkitClient.builder().apiKey(null));
    }

    @Test
    void builderRejectsNullBaseUrl() {
        assertThrows(NullPointerException.class, () ->
                SmplkitClient.builder().baseUrl(null));
    }

    @Test
    void builderRejectsNullTimeout() {
        assertThrows(NullPointerException.class, () ->
                SmplkitClient.builder().timeout(null));
    }

    @Test
    void clientIsAutoCloseable() {
        SmplkitClient client = SmplkitClient.builder()
                .apiKey("test-key")
                .build();
        assertDoesNotThrow(client::close);
        // Should be safe to close twice
        assertDoesNotThrow(client::close);
    }

    @Test
    void packagePrivateConstructorWithHttpClient_createsClient() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SmplkitClient client = new SmplkitClient(httpClient, "test-key",
                "https://config.smplkit.com", Duration.ofSeconds(30));
        assertNotNull(client);
        assertNotNull(client.config());
    }

    @Test
    void transport_returnsTransportInstance() {
        try (SmplkitClient client = SmplkitClient.builder()
                .apiKey("test-key")
                .build()) {
            assertNotNull(client.transport());
        }
    }
}
