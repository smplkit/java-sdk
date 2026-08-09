package com.smplkit.config;

import com.smplkit.EventStream;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers {@link ConfigClientBuilder} fluent setters + {@code build()}, the
 * {@link ConfigClient} standalone factories, and the standalone {@code close()}
 * no-op. {@code build()} resolves credentials and constructs the transport but
 * performs no network I/O, so these are hermetic.
 */
class ConfigClientBuilderTest {

    @Test
    void builder_fluentChain_buildsStandaloneClient() throws Exception {
        ConfigClient client = ConfigClient.builder()
                .apiKey("test-key")
                .baseDomain("example.test")
                .scheme("http")
                .environment("staging")
                .service("checkout")
                .streaming(false)
                .timeout(Duration.ofSeconds(5))
                .debug(true)
                .extraHeaders(Map.of("X-Trace", "abc"))
                .build();
        try (client) {
            assertNotNull(client);
            assertFalse(client.isConnected());
            assertEquals("staging", getField(client, "environment"));
            assertEquals("checkout", getField(client, "service"));
        }
    }

    // --- environment/service resolution through the standard chain ---

    @Test
    void builder_environmentAndServiceResolvedFromEnvVars() throws Exception {
        setEnv("SMPLKIT_ENVIRONMENT", "env-from-var");
        setEnv("SMPLKIT_SERVICE", "svc-from-var");
        try (ConfigClient client = ConfigClient.builder().apiKey("test-key").build()) {
            assertEquals("env-from-var", getField(client, "environment"));
            assertEquals("svc-from-var", getField(client, "service"));
        } finally {
            clearEnv("SMPLKIT_ENVIRONMENT");
            clearEnv("SMPLKIT_SERVICE");
        }
    }

    @Test
    void builder_builderEnvironmentAndServiceWinOverEnvVars() throws Exception {
        setEnv("SMPLKIT_ENVIRONMENT", "env-from-var");
        setEnv("SMPLKIT_SERVICE", "svc-from-var");
        try (ConfigClient client = ConfigClient.builder()
                .apiKey("test-key")
                .environment("env-from-builder")
                .service("svc-from-builder")
                .build()) {
            assertEquals("env-from-builder", getField(client, "environment"));
            assertEquals("svc-from-builder", getField(client, "service"));
        } finally {
            clearEnv("SMPLKIT_ENVIRONMENT");
            clearEnv("SMPLKIT_SERVICE");
        }
    }

    @Test
    void builder_withExplicitBaseUrl_buildsClient() {
        ConfigClient client = ConfigClient.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost:8001")
                .build();
        try (client) {
            assertNotNull(client);
        }
    }

    @Test
    void builder_withProfile_isAccepted() {
        // Supplying a profile name is accepted by the builder; resolution falls
        // back to the explicit apiKey so no ~/.smplkit entry is required.
        ConfigClient client = ConfigClient.builder()
                .profile("default")
                .apiKey("test-key")
                .build();
        try (client) {
            assertNotNull(client);
        }
    }

    @Test
    void builder_setters_rejectNull() {
        ConfigClientBuilder b = ConfigClient.builder();
        assertThrows(NullPointerException.class, () -> b.profile(null));
        assertThrows(NullPointerException.class, () -> b.apiKey(null));
        assertThrows(NullPointerException.class, () -> b.baseUrl(null));
        assertThrows(NullPointerException.class, () -> b.baseDomain(null));
        assertThrows(NullPointerException.class, () -> b.scheme(null));
        assertThrows(NullPointerException.class, () -> b.environment(null));
        assertThrows(NullPointerException.class, () -> b.service(null));
        assertThrows(NullPointerException.class, () -> b.timeout(null));
        assertThrows(NullPointerException.class, () -> b.extraHeaders(null));
    }

    @Test
    void create_withApiKey_buildsStandalone() {
        try (ConfigClient client = ConfigClient.create("test-key")) {
            assertNotNull(client);
            assertFalse(client.isConnected());
        }
    }

    @Test
    void close_onNeverConnectedStandalone_isNoOp() {
        ConfigClient client = ConfigClient.create("test-key");
        // No live use → no owned event stream. close() must not throw and may
        // be called more than once.
        assertDoesNotThrow(client::close);
        assertDoesNotThrow(client::close);
    }

    @Test
    void close_withOwnedEventStream_tearsItDown() throws Exception {
        // A standalone client that opened its own event stream must close it.
        // We inject a fake owned stream via reflection so close() exercises
        // the owns-stream teardown branch without dialing a real connection.
        ConfigClient client = ConfigClient.create("test-key");
        EventStream fakeStream = mock(EventStream.class);
        setField(client, "eventStream", fakeStream);
        setField(client, "ownsEventStream", true);

        client.close();

        verify(fakeStream).close();
        // Idempotent: a second close() does not re-close the (now-null) stream.
        assertDoesNotThrow(client::close);
        verify(fakeStream, Mockito.times(1)).close();
    }

    @Test
    void standalone_ensureEventStream_opensOwnsAndClosesStream() throws Exception {
        // Build a standalone client via the package-private ctor with a mocked
        // ConfigsApi and an unroutable app base URL. We invoke
        // ensureEventStream() directly (rather than through ensureConnected,
        // which would block on EventStream.ensureConnected awaiting a
        // connection that never completes). The owned stream's daemon fails to
        // connect harmlessly, and close() tears it down. Mirrors the
        // FlagsClient standalone-stream pattern. ensureEventStream() is
        // invoked directly, so the transport is never used here — a bare mock
        // satisfies the constructor.
        ConfigsApi mockApi = Mockito.mock(ConfigsApi.class);
        ConfigClient client = new ConfigClient(mockApi, HttpClient.newHttpClient(),
                "test-key", "http://localhost:1", "staging", "test-service", true);

        java.lang.reflect.Method ensureStream = ConfigClient.class.getDeclaredMethod("ensureEventStream");
        ensureStream.setAccessible(true);
        Object stream = ensureStream.invoke(client);
        assertNotNull(stream, "standalone ensureEventStream should open and own a stream");
        assertTrue(ownsEventStream(client), "standalone ensureEventStream should mark the stream as owned");

        // A second call returns the same already-owned stream (early-return branch).
        assertSame(stream, ensureStream.invoke(client));

        assertDoesNotThrow(client::close);
        assertFalse(ownsEventStream(client), "close() should release the owned stream");
    }

    private static boolean ownsEventStream(ConfigClient client) {
        try {
            Field f = ConfigClient.class.getDeclaredField("ownsEventStream");
            f.setAccessible(true);
            return f.getBoolean(client);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(ConfigClient client, String name, Object value) {
        try {
            Field f = ConfigClient.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(client, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object getField(ConfigClient client, String name) throws Exception {
        Field f = ConfigClient.class.getDeclaredField(name);
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
