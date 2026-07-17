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
    void build_withAllOptions_constructsClient() throws Exception {
        try (FlagsClient client = FlagsClient.builder()
                .apiKey("test-key")
                .baseDomain("example.test")
                .scheme("http")
                .environment("staging")
                .service("checkout")
                .streaming(false)
                .timeout(Duration.ofSeconds(10))
                .debug(true)
                .extraHeaders(Map.of("X-Team", "platform"))
                .header("X-Trace", "abc123")
                .build()) {
            assertNotNull(client);
            assertEquals("staging", field(client, "environment"));
            assertEquals("checkout", field(client, "parentService"));
        }
    }

    // --- environment/service resolution through the standard chain ---

    @Test
    void build_environmentAndServiceResolvedFromEnvVars() throws Exception {
        setEnv("SMPLKIT_ENVIRONMENT", "env-from-var");
        setEnv("SMPLKIT_SERVICE", "svc-from-var");
        try (FlagsClient client = FlagsClient.builder().apiKey("test-key").build()) {
            assertEquals("env-from-var", field(client, "environment"));
            assertEquals("svc-from-var", field(client, "parentService"));
        } finally {
            clearEnv("SMPLKIT_ENVIRONMENT");
            clearEnv("SMPLKIT_SERVICE");
        }
    }

    @Test
    void build_builderEnvironmentAndServiceWinOverEnvVars() throws Exception {
        setEnv("SMPLKIT_ENVIRONMENT", "env-from-var");
        setEnv("SMPLKIT_SERVICE", "svc-from-var");
        try (FlagsClient client = FlagsClient.builder()
                .apiKey("test-key")
                .environment("env-from-builder")
                .service("svc-from-builder")
                .build()) {
            assertEquals("env-from-builder", field(client, "environment"));
            assertEquals("svc-from-builder", field(client, "parentService"));
        } finally {
            clearEnv("SMPLKIT_ENVIRONMENT");
            clearEnv("SMPLKIT_SERVICE");
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
        assertThrows(NullPointerException.class, () -> b.service(null));
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
        assertSame(b, b.service("svc"));
        assertSame(b, b.streaming(true));
        assertSame(b, b.timeout(Duration.ofSeconds(5)));
        assertSame(b, b.debug(false));
        assertSame(b, b.header("a", "b"));
        assertSame(b, b.extraHeaders(Map.of("c", "d")));
        assertSame(b, b.baseUrl("https://x"));
        assertSame(b, b.profile("default"));
    }

    // --- helpers ---

    private static Object field(FlagsClient client, String name) throws Exception {
        var f = FlagsClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(client);
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
}
