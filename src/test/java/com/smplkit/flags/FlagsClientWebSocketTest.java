package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.SharedWebSocket;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private FlagsClient client;
    private SharedWebSocket sharedWs;
    private static final String FLAG_ID = "feature-x";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        sharedWs = new SharedWebSocket(); // test WS (no real connection)
        client = new FlagsClient(mockApi, null, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setSharedWs(sharedWs);
        client.setEnvironment("staging");
    }

    @Test
    void flagChanged_reloadsAndFiresListeners() throws ApiException {
        setupList("feature-x", Map.of("staging", Map.of("enabled", true, "default", true)));
        client._connectInternal();

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        // Simulate a WS flag_changed event with changed data
        setupGetFlag("feature-x", Map.of("staging", Map.of("enabled", true, "default", false)));
        client.simulateFlagChanged("feature-x");

        assertNotNull(received.get());
        assertEquals("feature-x", received.get().id());
        assertEquals("websocket", received.get().source());
    }

    @Test
    void flagDeleted_reloadsAndFiresListeners() throws ApiException {
        setupList("feature-x", Map.of());
        client._connectInternal();

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        client.simulateFlagDeleted("feature-x");

        assertNotNull(received.get());
        assertEquals("websocket", received.get().source());
        assertTrue(received.get().isDeleted());
    }

    @Test
    void flagChanged_whenNotConnected_ignored() throws ApiException {
        // Do not connect
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

        client._connectInternal();

        assertTrue(client.isConnected());
    }

    @Test
    void connect_registersWsListenersByName() throws ApiException {
        setupList("feature-x", Map.of());
        SharedWebSocket mockWs = mock(SharedWebSocket.class);
        client.setSharedWs(mockWs);

        client._connectInternal();

        verify(mockWs).on(eq("flag_changed"), any());
        verify(mockWs).on(eq("flag_deleted"), any());
        verify(mockWs).on(eq("flags_changed"), any());
    }

    @Test
    void disconnect_unregistersWsListeners() throws ApiException {
        setupList("feature-x", Map.of());
        sharedWs.setConnectionStatus("connected");
        client._connectInternal();

        client.disconnect();

        assertFalse(client.isConnected());
    }

    @Test
    void connect_thenDisconnect_handleReturnsDefault() throws ApiException {
        setupList("feature-x", Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));
        client._connectInternal();

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));

        client.disconnect();

        // After disconnect, flag store is cleared; next get() triggers reconnect with empty store
        setupList("other-key", Map.of());
        assertFalse(handle.get(List.of())); // returns default
    }

    @Test
    void refresh_invalidatesCache() throws ApiException {
        setupList("feature-x", Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));
        client._connectInternal();

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of())); // miss
        assertTrue(handle.get(List.of())); // hit

        FlagStats beforeRefresh = client.stats();
        assertEquals(1, beforeRefresh.cacheHits());

        setupList("feature-x", Map.of(
                "staging", Map.of("enabled", true, "default", false)
        ));
        client.refresh();

        assertFalse(handle.get(List.of())); // miss after cache cleared
    }

    @Test
    void wsEventTriggeredViaSharedWs() throws ApiException {
        setupList("feature-x", Map.of("staging", Map.of("enabled", true, "default", true)));
        sharedWs.setConnectionStatus("connected");
        client._connectInternal();

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        // Use SharedWebSocket simulateMessage to trigger event dispatch
        // Message must have "id" field matching the flag key, and data must differ
        setupGetFlag("feature-x", Map.of("staging", Map.of("enabled", true, "default", false)));
        sharedWs.simulateMessage("{\"event\":\"flag_changed\",\"id\":\"feature-x\"}");

        assertNotNull(received.get());
    }

    @Test
    void onChange_keyScoped_onlyFiresForMatchingKey() throws ApiException {
        FlagListResponse listResponse = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagAttrs("flag-a", Map.of()),
                flagAttrs("flag-b", Map.of())
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(listResponse);
        client._connectInternal();

        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        client.onChange("flag-a", e -> aCount.incrementAndGet());
        client.onChange("flag-b", e -> bCount.incrementAndGet());

        // flag_changed for flag-a only — only flag-a listener fires, not flag-b
        setupGetFlag("flag-a", Map.of("staging", Map.of("enabled", true, "default", true)));
        client.simulateFlagChanged("flag-a");

        assertEquals(1, aCount.get());
        assertEquals(0, bCount.get());
    }

    // --- Helpers ---

    private void setupList(String id, Map<String, Object> environments) throws ApiException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        FlagListResponse listResponse = OBJECT_MAPPER.convertValue(Map.of("data", List.of(Map.of(
                "id", id, "type", "flag", "attributes", attrs
        ))), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(listResponse);
    }

    private void setupGetFlag(String id, Map<String, Object> environments) throws ApiException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        FlagResponse flagResponse = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", id, "type", "flag", "attributes", attrs
        )), FlagResponse.class);
        when(mockApi.getFlag(id)).thenReturn(flagResponse);
    }

    private static Map<String, Object> flagAttrs(String id, Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        return Map.of("id", id, "type", "flag", "attributes", attrs);
    }
}
