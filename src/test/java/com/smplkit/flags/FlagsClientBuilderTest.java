package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the standalone {@link FlagsClientBuilder}: every fluent setter, the
 * null-argument guards, and {@link FlagsClientBuilder#build()}. All builds are
 * offline — an explicit API key short-circuits credential resolution and no
 * network call is made until a live method runs.
 */
class FlagsClientBuilderTest {

    @Test
    void build_withApiKey_constructsClient() {
        try (FlagsClient client = FlagsClient.builder().apiKey("test-key").build()) {
            assertNotNull(client);
            assertFalse(client.isConnected());
        }
    }

    @Test
    void build_withAllOptions_constructsClient() {
        try (FlagsClient client = FlagsClient.builder()
                .apiKey("test-key")
                .baseDomain("example.test")
                .scheme("http")
                .environment("staging")
                .timeout(Duration.ofSeconds(10))
                .debug(true)
                .extraHeaders(Map.of("X-Team", "platform"))
                .header("X-Trace", "abc123")
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void build_withBaseUrl_constructsClient() {
        try (FlagsClient client = FlagsClient.builder()
                .apiKey("test-key")
                .baseUrl("https://flags.example.test")
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void build_withProfileAndExplicitApiKey_constructsClient() {
        // profile() is accepted; the explicit apiKey still wins during resolution.
        try (FlagsClient client = FlagsClient.builder()
                .profile("default")
                .apiKey("test-key")
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void create_staticFactory_withApiKey() {
        try (FlagsClient client = FlagsClient.create("test-key")) {
            assertNotNull(client);
        }
    }

    // --- null-argument guards on the fluent setters ---

    @Test
    void setters_rejectNullArguments() {
        FlagsClientBuilder b = FlagsClient.builder();
        assertThrows(NullPointerException.class, () -> b.profile(null));
        assertThrows(NullPointerException.class, () -> b.apiKey(null));
        assertThrows(NullPointerException.class, () -> b.baseUrl(null));
        assertThrows(NullPointerException.class, () -> b.baseDomain(null));
        assertThrows(NullPointerException.class, () -> b.scheme(null));
        assertThrows(NullPointerException.class, () -> b.environment(null));
        assertThrows(NullPointerException.class, () -> b.timeout(null));
        assertThrows(NullPointerException.class, () -> b.extraHeaders(null));
        assertThrows(NullPointerException.class, () -> b.header(null, "v"));
        assertThrows(NullPointerException.class, () -> b.header("k", null));
    }

    @Test
    void setters_returnSameBuilderForChaining() {
        FlagsClientBuilder b = FlagsClient.builder();
        assertSame(b, b.apiKey("k"));
        assertSame(b, b.baseDomain("d"));
        assertSame(b, b.scheme("https"));
        assertSame(b, b.environment("staging"));
        assertSame(b, b.timeout(Duration.ofSeconds(5)));
        assertSame(b, b.debug(false));
        assertSame(b, b.header("a", "b"));
        assertSame(b, b.extraHeaders(Map.of("c", "d")));
        assertSame(b, b.baseUrl("https://x"));
        assertSame(b, b.profile("default"));
    }
}
