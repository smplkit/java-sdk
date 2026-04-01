package com.smplkit.flags;

import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.ResponseFlag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FlagsClient WebSocket event handling and lifecycle.
 */
class FlagsClientWebSocketTest {

    private FlagsApi mockApi;
    private FlagsClient client;
    private SharedWebSocket sharedWs;
    private static final String FLAG_ID = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        sharedWs = new SharedWebSocket(); // test WS (no real connection)
        client = new FlagsClient(mockApi, HttpClient.newHttpClient(), "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));
        client.setSharedWs(sharedWs);
    }

    @Test
    void flagChanged_reloadsAndFiresListeners() throws ApiException {
        setupList("feature-x", Map.of("staging", Map.of("enabled", true, "default", true)));
        client.connect("staging");

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        // Simulate a WS flag_changed event
        setupList("feature-x", Map.of("staging", Map.of("enabled", true, "default", false)));
        client.simulateFlagChanged();

        assertNotNull(received.get());
        assertEquals("feature-x", received.get().key());
        assertEquals("websocket", received.get().source());
    }

    @Test
    void flagDeleted_reloadsAndFiresListeners() throws ApiException {
        setupList("feature-x", Map.of());
        client.connect("staging");

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        client.simulateFlagDeleted();

        assertNotNull(received.get());
    }

    @Test
    void flagChanged_whenNotConnected_ignored() throws ApiException {
        // Don't connect
        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateFlagChanged();

        assertEquals(0, count.get());
    }

    @Test
    void flagDeleted_whenNotConnected_ignored() {
        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateFlagDeleted();
        assertEquals(0, count.get());
    }

    @Test
    void connect_registersWsListeners() throws ApiException {
        setupList("feature-x", Map.of());
        sharedWs.setConnectionStatus("connected");

        client.connect("staging");

        assertTrue(client.isConnected());
        assertEquals("connected", client.connectionStatus());
    }

    @Test
    void disconnect_unregistersWsListeners() throws ApiException {
        setupList("feature-x", Map.of());
        sharedWs.setConnectionStatus("connected");
        client.connect("staging");

        client.disconnect();

        assertFalse(client.isConnected());
        assertEquals("disconnected", client.connectionStatus());
    }

    @Test
    void connect_thenDisconnect_handleReturnsDefault() throws ApiException {
        setupList("feature-x", Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));
        client.connect("staging");

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertTrue(handle.get(List.of()));

        client.disconnect();
        assertFalse(handle.get(List.of()));
    }

    @Test
    void refresh_invalidatesCache() throws ApiException {
        setupList("feature-x", Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));
        client.connect("staging");

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertTrue(handle.get(List.of())); // Miss
        assertTrue(handle.get(List.of())); // Hit

        FlagStats beforeRefresh = client.stats();
        assertEquals(1, beforeRefresh.cacheHits());

        setupList("feature-x", Map.of(
                "staging", Map.of("enabled", true, "default", false)
        ));
        client.refresh();

        assertFalse(handle.get(List.of())); // Miss (cache was cleared)
    }

    @Test
    void wsEventTriggeredViaSharedWs() throws ApiException {
        setupList("feature-x", Map.of());
        sharedWs.setConnectionStatus("connected");
        client.connect("staging");

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        // Use SharedWebSocket simulateMessage to trigger event dispatch
        setupList("feature-x", Map.of());
        sharedWs.simulateMessage("{\"event\":\"flag_changed\",\"flag_key\":\"feature-x\"}");

        assertNotNull(received.get());
    }

    // --- Helpers ---

    private void setupList(String key, Map<String, Object> environments) throws ApiException {
        Map<String, Object> listResponse = Map.of("data", List.of(
                Map.of("id", FLAG_ID, "attributes", Map.of(
                        "key", key, "name", key, "type", "BOOLEAN",
                        "default", false, "values", List.of(),
                        "environments", environments
                ))
        ));
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(listResponse);
    }
}
