package com.smplkit.config;

import com.smplkit.SharedWebSocket;
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
    void builder_fluentChain_buildsStandaloneClient() {
        ConfigClient client = ConfigClient.builder()
                .apiKey("test-key")
                .baseDomain("example.test")
                .scheme("http")
                .environment("staging")
                .timeout(Duration.ofSeconds(5))
                .debug(true)
                .extraHeaders(Map.of("X-Trace", "abc"))
                .build();
        try (client) {
            assertNotNull(client);
            assertFalse(client.isConnected());
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
        // No live use → no owned WebSocket. close() must not throw and may be
        // called more than once.
        assertDoesNotThrow(client::close);
        assertDoesNotThrow(client::close);
    }

    @Test
    void close_withOwnedWebSocket_tearsItDown() throws Exception {
        // A standalone client that opened its own WebSocket must close it. We
        // inject a fake owned WS via reflection so close() exercises the
        // owns-WS teardown branch without dialing a real socket.
        ConfigClient client = ConfigClient.create("test-key");
        SharedWebSocket fakeWs = mock(SharedWebSocket.class);
        setField(client, "wsManager", fakeWs);
        setField(client, "ownsWs", true);

        client.close();

        verify(fakeWs).close();
        // Idempotent: a second close() does not re-close the (now-null) WS.
        assertDoesNotThrow(client::close);
        verify(fakeWs, Mockito.times(1)).close();
    }

    @Test
    void standalone_ensureWs_opensOwnsAndClosesWebSocket() throws Exception {
        // Build a standalone client via the package-private ctor with a mocked
        // ConfigsApi and an unroutable app base URL. We invoke ensureWs()
        // directly (rather than through ensureConnected, which would block on
        // SharedWebSocket.ensureConnected awaiting a connection that never
        // completes). The owned WS's daemon fails to connect harmlessly, and
        // close() tears it down. Mirrors the FlagsClient standalone-WS pattern.
        // ensureWs() is invoked directly, so the transport is never used here —
        // a bare mock satisfies the constructor.
        ConfigsApi mockApi = Mockito.mock(ConfigsApi.class);
        ConfigClient client = new ConfigClient(mockApi, HttpClient.newHttpClient(),
                "test-key", "http://localhost:1", "staging", "test-service");

        java.lang.reflect.Method ensureWs = ConfigClient.class.getDeclaredMethod("ensureWs");
        ensureWs.setAccessible(true);
        Object ws = ensureWs.invoke(client);
        assertNotNull(ws, "standalone ensureWs should open and own a WebSocket");
        assertTrue(ownsWs(client), "standalone ensureWs should mark the WS as owned");

        // A second call returns the same already-owned WS (early-return branch).
        assertSame(ws, ensureWs.invoke(client));

        assertDoesNotThrow(client::close);
        assertFalse(ownsWs(client), "close() should release the owned WebSocket");
    }

    private static boolean ownsWs(ConfigClient client) {
        try {
            Field f = ConfigClient.class.getDeclaredField("ownsWs");
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
}
