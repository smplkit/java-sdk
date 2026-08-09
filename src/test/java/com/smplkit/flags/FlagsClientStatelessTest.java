package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.Context;
import com.smplkit.errors.SmplError;
import com.smplkit.flags.types.FlagDeclaration;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stateless live surface for {@link FlagsClient} — {@code streaming(false)}.
 *
 * <p>The first live call still flushes discovery (best-effort) and fetches
 * all definitions once, blocking, but no event stream is opened, no periodic
 * flush tasks are scheduled, no connect retry is queued, and registration
 * threshold flushes run inline on the calling thread. All assertions on the
 * mocked transports are made immediately after the triggering call — no
 * latch, no sleep — proving no background thread sits in the path.</p>
 */
class FlagsClientStatelessTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private ContextsApi mockContextsApi;
    private FlagsClient client;

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextsApi, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.test", "https://app.smplkit.test",
                Duration.ofSeconds(5), false, false);
        client.setEnvironment("staging");
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    // ------------------------------------------------------------ lazy connect

    @Test
    void statelessConnect_fetchesFlagsAndSkipsEventStreamAndTimers() throws Exception {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(makeFlagListResponse("beta", "BOOLEAN", true));

        Flag<Boolean> handle = client.booleanFlag("beta", false);
        assertTrue(client.isConnected());
        assertTrue(handle.get());

        // No event stream, no handler registration, no scheduled periodic flushes.
        assertNull(field(client, "eventStream"));
        assertFalse((boolean) field(client, "eventHandlersRegistered"));
        assertNull(field(client, "flagFlushFuture"));
        assertNull(field(client, "contextFlushFuture"));
    }

    @Test
    void statelessConnect_fetchFailurePropagatesAndRetriesInline() throws Exception {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenThrow(new ApiException(500, "boom"))
                .thenReturn(makeFlagListResponse("beta", "BOOLEAN", true));

        // First live call: the fetch failure surfaces to the caller — no
        // background retry timer is queued.
        assertThrows(SmplError.class, () -> client.booleanFlag("beta", false));
        assertFalse(client.isConnected());
        assertFalse(client.isRetryScheduled());

        // The next live call retries inline and succeeds.
        Flag<Boolean> handle = client.booleanFlag("beta", false);
        assertTrue(client.isConnected());
        assertTrue(handle.get());
    }

    @Test
    void statelessConnect_discoveryFlushFailureIsBestEffort() throws Exception {
        when(mockApi.bulkRegisterFlags(any())).thenThrow(new ApiException(503, "unavailable"));
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));

        client.register(new FlagDeclaration("pending", "BOOLEAN", false, null, null));
        Flag<Boolean> handle = client.booleanFlag("beta", false);

        // Flush failed but connect still succeeded; the declaration stays
        // buffered for a later flush.
        assertTrue(client.isConnected());
        assertFalse(handle.get());
        assertTrue(client.pendingCount() >= 1);
    }

    // ---------------------------------------------------- inline threshold flush

    @Test
    void register_thresholdFlushRunsInline() throws Exception {
        when(mockApi.bulkRegisterFlags(any())).thenReturn(null);

        List<FlagDeclaration> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(new FlagDeclaration("flag-" + i, "BOOLEAN", false, null, null));
        }
        client.register(items);

        // Inline: the bulk POST already happened on this thread.
        verify(mockApi, times(1)).bulkRegisterFlags(any());
        assertEquals(0, client.pendingCount());
    }

    @Test
    void register_thresholdFlushInline_swallowsFailure() throws Exception {
        when(mockApi.bulkRegisterFlags(any())).thenThrow(new ApiException(503, "unavailable"));

        List<FlagDeclaration> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(new FlagDeclaration("flag-" + i, "BOOLEAN", false, null, null));
        }
        assertDoesNotThrow(() -> client.register(items));

        // Items stay buffered until a later flush succeeds.
        assertEquals(50, client.pendingCount());
    }

    @Test
    void evaluate_contextThresholdFlushesInline() throws Exception {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));
        when(mockContextsApi.bulkRegisterContexts(any())).thenReturn(null);

        // 100 distinct contexts trip CONTEXT_BATCH_FLUSH_SIZE; the flush runs
        // inline on the evaluating thread.
        for (int i = 0; i < 100; i++) {
            client._evaluateHandle("beta", false,
                    List.of(new Context("user", "u-" + i, Map.of())));
        }
        verify(mockContextsApi, times(1)).bulkRegisterContexts(any());
    }

    // ------------------------------------------------------------------ refresh

    @Test
    void refresh_statelessFiresListenersFromDeltas() throws Exception {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(makeFlagListResponse("beta", "BOOLEAN", false));

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);
        assertTrue(client.isConnected());

        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(makeFlagListResponse("beta", "BOOLEAN", true));
        client.refresh();

        assertNotNull(received.get());
        assertEquals("beta", received.get().id());
        assertEquals("manual", received.get().source());
    }

    // ------------------------------------------------------------------ helpers

    private static FlagListResponse makeFlagListResponse(String id, String type, Object defaultValue) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        Map<String, Object> map = Map.of("data", List.of(Map.of(
                "id", id, "type", "flag", "attributes", attrs)));
        return OBJECT_MAPPER.convertValue(map, FlagListResponse.class);
    }

    private static Object field(FlagsClient client, String name) throws Exception {
        Field f = FlagsClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(client);
    }
}
