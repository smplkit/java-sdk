package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * Tests for FlagsClient WS event behaviors:
 * - Fan-out bug fix: flag_changed for key X only fires X's listener, not Y's
 * - Keyed event + content changed → scoped fetch, listener fires
 * - Keyed event + content unchanged → scoped fetch, listener does NOT fire
 * - Plural (flags_changed) event → full fetch, diff-based firing
 * - Deleted event → store removal, listener fires with deleted=true, no fetch
 */
class FlagsClientWsEventsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private FlagsClient client;

    @BeforeEach
    void setUp() throws ApiException {
        mockApi = Mockito.mock(FlagsApi.class);
        client = new FlagsClient(mockApi, null, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
    }

    // -----------------------------------------------------------------------
    // B. Fan-out bug fix
    // -----------------------------------------------------------------------

    @Test
    void flagChanged_forKeyX_onlyFiresXListener_notYListener() throws ApiException {
        setupList("flag-x", "BOOLEAN", false, Map.of());
        setupList("flag-y", "BOOLEAN", false, Map.of());
        // Set up both flags in the initial store
        FlagListResponse both = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("flag-x", "BOOLEAN", false, Map.of()),
                flagData("flag-y", "BOOLEAN", false, Map.of())
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(both);
        client._connectInternal();

        AtomicInteger xCount = new AtomicInteger();
        AtomicInteger yCount = new AtomicInteger();
        client.onChange("flag-x", e -> xCount.incrementAndGet());
        client.onChange("flag-y", e -> yCount.incrementAndGet());

        // Scoped fetch for flag-x returns different content
        setupGetFlag("flag-x", "BOOLEAN", true, Map.of());
        client.simulateFlagChanged("flag-x");

        assertEquals(1, xCount.get(), "flag-x listener should fire exactly once");
        assertEquals(0, yCount.get(), "flag-y listener should NOT fire when flag-x changes");
    }

    @Test
    void flagChanged_globalListenerFiresOnce_notPerKey() throws ApiException {
        FlagListResponse both = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("flag-a", "BOOLEAN", false, Map.of()),
                flagData("flag-b", "BOOLEAN", false, Map.of())
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(both);
        client._connectInternal();

        AtomicInteger globalCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());

        // flag_changed for flag-a: global listener fires exactly once
        setupGetFlag("flag-a", "BOOLEAN", true, Map.of());
        client.simulateFlagChanged("flag-a");

        assertEquals(1, globalCount.get(), "Global listener fires exactly once per event");
    }

    // -----------------------------------------------------------------------
    // C. flag_changed — scoped fetch, diff-based
    // -----------------------------------------------------------------------

    @Test
    void flagChanged_contentChanged_scopedFetch_listenerFires() throws ApiException {
        setupList("my-flag", "BOOLEAN", false, Map.of());
        client._connectInternal();

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange("my-flag", received::set);

        // Different content → listener should fire
        setupGetFlag("my-flag", "BOOLEAN", true, Map.of());
        client.simulateFlagChanged("my-flag");

        assertNotNull(received.get());
        assertEquals("my-flag", received.get().id());
        assertEquals("websocket", received.get().source());
        assertFalse(received.get().isDeleted());
        // Verify scoped fetch (getFlag) was called, not listFlags
        verify(mockApi).getFlag("my-flag");
        verify(mockApi, times(1)).listFlags(isNull(), isNull(), isNull(), isNull()); // only initial
    }

    @Test
    void flagChanged_contentUnchanged_scopedFetch_listenerDoesNotFire() throws ApiException {
        setupList("my-flag", "BOOLEAN", false, Map.of());
        client._connectInternal();

        AtomicInteger count = new AtomicInteger();
        client.onChange("my-flag", e -> count.incrementAndGet());

        // Same content → listener should NOT fire
        setupGetFlag("my-flag", "BOOLEAN", false, Map.of()); // same as initial
        client.simulateFlagChanged("my-flag");

        assertEquals(0, count.get(), "Listener should not fire when content is unchanged");
        verify(mockApi).getFlag("my-flag");
    }

    @Test
    void flagChanged_missingId_isNoOp() throws ApiException {
        setupList("my-flag", "BOOLEAN", false, Map.of());
        client._connectInternal();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        // No "id" field in event data
        client.simulateFlagChanged(); // calls handleFlagChanged with Map.of()

        assertEquals(0, count.get(), "Event with missing id should be ignored");
        verify(mockApi, never()).getFlag(any());
    }

    // -----------------------------------------------------------------------
    // C. flag_deleted — store removal, listener with deleted=true, no fetch
    // -----------------------------------------------------------------------

    @Test
    void flagDeleted_removesFromStore_firesListenerWithDeletedTrue() throws ApiException {
        setupList("deleted-flag", "BOOLEAN", false, Map.of());
        client._connectInternal();

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange("deleted-flag", received::set);
        client.onChange(e -> {}); // register global too

        client.simulateFlagDeleted("deleted-flag");

        assertNotNull(received.get());
        assertEquals("deleted-flag", received.get().id());
        assertTrue(received.get().isDeleted());
        assertEquals("websocket", received.get().source());
        // No HTTP fetch should occur
        verify(mockApi, never()).getFlag(any());
        verify(mockApi, times(1)).listFlags(isNull(), isNull(), isNull(), isNull()); // only initial
    }

    @Test
    void flagDeleted_globalListenerFires() throws ApiException {
        setupList("deleted-flag", "BOOLEAN", false, Map.of());
        client._connectInternal();

        AtomicReference<FlagChangeEvent> globalReceived = new AtomicReference<>();
        client.onChange(globalReceived::set);

        client.simulateFlagDeleted("deleted-flag");

        assertNotNull(globalReceived.get());
        assertTrue(globalReceived.get().isDeleted());
    }

    @Test
    void flagDeleted_otherKeyListener_doesNotFire() throws ApiException {
        FlagListResponse both = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("flag-x", "BOOLEAN", false, Map.of()),
                flagData("flag-y", "BOOLEAN", false, Map.of())
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(both);
        client._connectInternal();

        AtomicInteger yCount = new AtomicInteger();
        client.onChange("flag-y", e -> yCount.incrementAndGet());

        client.simulateFlagDeleted("flag-x");

        assertEquals(0, yCount.get(), "flag-y listener should not fire when flag-x is deleted");
    }

    // -----------------------------------------------------------------------
    // C. flags_changed — full fetch, diff-based firing
    // -----------------------------------------------------------------------

    @Test
    void flagsChanged_fullFetch_diffBasedFiring() throws ApiException {
        setupList("flag-1", "BOOLEAN", false, Map.of());
        client._connectInternal();

        AtomicReference<FlagChangeEvent> receivedKeyed = new AtomicReference<>();
        AtomicReference<FlagChangeEvent> receivedGlobal = new AtomicReference<>();
        client.onChange("flag-1", receivedKeyed::set);
        client.onChange(receivedGlobal::set);

        // flags_changed triggers full list fetch with changed data
        FlagListResponse updated = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("flag-1", "BOOLEAN", true, Map.of()) // changed default
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(updated);
        client.simulateFlagsChanged();

        assertNotNull(receivedKeyed.get(), "Per-key listener should fire for changed flag");
        assertEquals("flag-1", receivedKeyed.get().id());
        assertNotNull(receivedGlobal.get(), "Global listener should fire once");
        // listFlags called twice: once for init, once for flags_changed
        verify(mockApi, times(2)).listFlags(isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void flagsChanged_noContentChange_noListenersFire() throws ApiException {
        setupList("flag-1", "BOOLEAN", false, Map.of());
        client._connectInternal();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());
        client.onChange("flag-1", e -> count.incrementAndGet());

        // Same content — no diff
        setupList("flag-1", "BOOLEAN", false, Map.of());
        client.simulateFlagsChanged();

        assertEquals(0, count.get(), "No listeners should fire when content is unchanged");
    }

    @Test
    void flagsChanged_globalListenerFiresOnce() throws ApiException {
        FlagListResponse initial = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("f1", "BOOLEAN", false, Map.of()),
                flagData("f2", "BOOLEAN", false, Map.of())
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(initial);
        client._connectInternal();

        AtomicInteger globalCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());

        // Both flags change
        FlagListResponse updated = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("f1", "BOOLEAN", true, Map.of()),
                flagData("f2", "BOOLEAN", true, Map.of())
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(updated);
        client.simulateFlagsChanged();

        assertEquals(1, globalCount.get(), "Global listener fires exactly once per flags_changed event");
    }

    @Test
    void flagsChanged_perKeyListenerFiresForEachChangedKey() throws ApiException {
        FlagListResponse initial = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("f1", "BOOLEAN", false, Map.of()),
                flagData("f2", "BOOLEAN", false, Map.of()),
                flagData("f3", "BOOLEAN", false, Map.of())
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(initial);
        client._connectInternal();

        AtomicInteger f1Count = new AtomicInteger();
        AtomicInteger f2Count = new AtomicInteger();
        AtomicInteger f3Count = new AtomicInteger();
        client.onChange("f1", e -> f1Count.incrementAndGet());
        client.onChange("f2", e -> f2Count.incrementAndGet());
        client.onChange("f3", e -> f3Count.incrementAndGet());

        // Only f1 and f2 change
        FlagListResponse updated = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("f1", "BOOLEAN", true, Map.of()),  // changed
                flagData("f2", "BOOLEAN", true, Map.of()),  // changed
                flagData("f3", "BOOLEAN", false, Map.of())  // unchanged
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(updated);
        client.simulateFlagsChanged();

        assertEquals(1, f1Count.get(), "f1 listener should fire (changed)");
        assertEquals(1, f2Count.get(), "f2 listener should fire (changed)");
        assertEquals(0, f3Count.get(), "f3 listener should NOT fire (unchanged)");
    }

    // -----------------------------------------------------------------------
    // FlagChangeEvent.isDeleted()
    // -----------------------------------------------------------------------

    @Test
    void flagChangeEvent_isDeletedFalseByDefault() {
        FlagChangeEvent event = new FlagChangeEvent("my-flag", "websocket");
        assertFalse(event.isDeleted());
        assertFalse(event.deleted());
    }

    @Test
    void flagChangeEvent_isDeletedTrueWhenSet() {
        FlagChangeEvent event = new FlagChangeEvent("my-flag", "websocket", true);
        assertTrue(event.isDeleted());
        assertTrue(event.deleted());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void setupList(String id, String type, Object defaultVal, Map<String, Object> environments)
            throws ApiException {
        FlagListResponse resp = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData(id, type, defaultVal, environments)
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(resp);
    }

    private void setupGetFlag(String id, String type, Object defaultVal, Map<String, Object> environments)
            throws ApiException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", defaultVal);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        FlagResponse resp = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", id, "type", "flag", "attributes", attrs
        )), FlagResponse.class);
        when(mockApi.getFlag(id)).thenReturn(resp);
    }

    private static Map<String, Object> flagData(String id, String type, Object defaultVal,
                                                 Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", defaultVal);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        return Map.of("id", id, "type", "flag", "attributes", attrs);
    }
}
