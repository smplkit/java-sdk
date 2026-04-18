package com.smplkit.flags;

import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagBulkRequest;
import com.smplkit.internal.generated.flags.model.FlagBulkResponse;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the flag auto-registration pipeline:
 * FlagRegistrationBuffer, typed method buffer population, flushFlags,
 * flush-before-fetch ordering, threshold flush, and scheduled flush.
 */
class FlagAutoRegistrationTest {

    private FlagsApi mockApi;
    private ContextsApi mockContextsApi;
    private FlagsClient client;

    @BeforeEach
    void setUp() throws ApiException {
        mockApi = Mockito.mock(FlagsApi.class);
        mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextsApi, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
        client.setParentService("my-service");
        // Default stub so _connectInternal doesn't fail
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(new FlagListResponse().data(List.of()));
    }

    // -----------------------------------------------------------------------
    // FlagRegistrationBuffer unit tests
    // -----------------------------------------------------------------------

    @Test
    void buffer_add_increasesPendingCount() {
        FlagsClient.FlagRegistrationBuffer buf = new FlagsClient.FlagRegistrationBuffer();
        assertEquals(0, buf.pendingCount());
        buf.add("flag-a", "BOOLEAN", false, "svc", "env");
        assertEquals(1, buf.pendingCount());
    }

    @Test
    void buffer_deduplicates_sameId() {
        FlagsClient.FlagRegistrationBuffer buf = new FlagsClient.FlagRegistrationBuffer();
        buf.add("flag-a", "BOOLEAN", false, "svc", "env");
        buf.add("flag-a", "BOOLEAN", true, "svc", "env"); // duplicate — ignored
        assertEquals(1, buf.pendingCount());
    }

    @Test
    void buffer_drain_returnsAllAndClearsPending() {
        FlagsClient.FlagRegistrationBuffer buf = new FlagsClient.FlagRegistrationBuffer();
        buf.add("flag-a", "BOOLEAN", false, "svc", "env");
        buf.add("flag-b", "STRING", "x", "svc", "env");

        List<FlagsClient.FlagRegistrationEntry> batch = buf.drain();
        assertEquals(2, batch.size());
        assertEquals(0, buf.pendingCount());
    }

    @Test
    void buffer_drain_emptyBufferReturnsEmptyList() {
        FlagsClient.FlagRegistrationBuffer buf = new FlagsClient.FlagRegistrationBuffer();
        assertTrue(buf.drain().isEmpty());
    }

    @Test
    void buffer_afterDrain_canAcceptNewEntries() {
        FlagsClient.FlagRegistrationBuffer buf = new FlagsClient.FlagRegistrationBuffer();
        buf.add("flag-a", "BOOLEAN", false, "svc", "env");
        buf.drain();
        // drain clears pending but seen set still prevents re-add
        buf.add("flag-a", "BOOLEAN", false, "svc", "env");
        assertEquals(0, buf.pendingCount()); // already seen — still deduped
    }

    @Test
    void buffer_threadSafety_concurrentAdds() throws InterruptedException {
        FlagsClient.FlagRegistrationBuffer buf = new FlagsClient.FlagRegistrationBuffer();
        int threads = 10;
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                buf.add("flag-" + idx, "BOOLEAN", false, "svc", "env");
                latch.countDown();
            });
            t.start();
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(threads, buf.pendingCount());
    }

    // -----------------------------------------------------------------------
    // Typed flag methods populate buffer
    // -----------------------------------------------------------------------

    @Test
    void booleanFlag_addsToBuffer_withCorrectFields() throws ApiException {
        client.booleanFlag("feature-x", false);

        client.flushFlags();

        ArgumentCaptor<FlagBulkRequest> captor = ArgumentCaptor.forClass(FlagBulkRequest.class);
        verify(mockApi).bulkRegisterFlags(captor.capture());

        FlagBulkRequest req = captor.getValue();
        assertEquals(1, req.getFlags().size());
        var item = req.getFlags().get(0);
        assertEquals("feature-x", item.getId());
        assertEquals("BOOLEAN", item.getType());
        assertEquals(false, item.getDefault());
        assertEquals("my-service", item.getService());
        assertEquals("staging", item.getEnvironment());
    }

    @Test
    void stringFlag_addsToBuffer_withCorrectType() throws ApiException {
        client.stringFlag("color", "red");

        client.flushFlags();

        ArgumentCaptor<FlagBulkRequest> captor = ArgumentCaptor.forClass(FlagBulkRequest.class);
        verify(mockApi).bulkRegisterFlags(captor.capture());
        assertEquals("STRING", captor.getValue().getFlags().get(0).getType());
        assertEquals("red", captor.getValue().getFlags().get(0).getDefault());
    }

    @Test
    void numberFlag_addsToBuffer_withCorrectType() throws ApiException {
        client.numberFlag("rate", 100);

        client.flushFlags();

        ArgumentCaptor<FlagBulkRequest> captor = ArgumentCaptor.forClass(FlagBulkRequest.class);
        verify(mockApi).bulkRegisterFlags(captor.capture());
        assertEquals("NUMERIC", captor.getValue().getFlags().get(0).getType());
        assertEquals(100, captor.getValue().getFlags().get(0).getDefault());
    }

    @Test
    void jsonFlag_addsToBuffer_withCorrectType() throws ApiException {
        client.jsonFlag("config", Map.of("a", 1));

        client.flushFlags();

        ArgumentCaptor<FlagBulkRequest> captor = ArgumentCaptor.forClass(FlagBulkRequest.class);
        verify(mockApi).bulkRegisterFlags(captor.capture());
        assertEquals("JSON", captor.getValue().getFlags().get(0).getType());
    }

    @Test
    void typedMethods_deduplicateSameId() throws ApiException {
        client.booleanFlag("dupe", false);
        client.booleanFlag("dupe", true); // second declaration — ignored

        client.flushFlags();

        ArgumentCaptor<FlagBulkRequest> captor = ArgumentCaptor.forClass(FlagBulkRequest.class);
        verify(mockApi).bulkRegisterFlags(captor.capture());
        assertEquals(1, captor.getValue().getFlags().size());
    }

    @Test
    void flushFlags_emptyBuffer_doesNotCallApi() throws ApiException {
        client.flushFlags();
        verify(mockApi, never()).bulkRegisterFlags(any());
    }

    // -----------------------------------------------------------------------
    // flushFlags failure handling
    // -----------------------------------------------------------------------

    @Test
    void flushFlags_apiFailure_doesNotPropagate() throws ApiException {
        when(mockApi.bulkRegisterFlags(any())).thenThrow(new ApiException(500, "Server Error"));

        client.booleanFlag("flag-x", false);
        assertDoesNotThrow(() -> client.flushFlags());
    }

    @Test
    void connectInternal_flushFailure_doesNotPreventConnect() throws ApiException {
        when(mockApi.bulkRegisterFlags(any())).thenThrow(new ApiException(500, "Internal Error"));
        client.booleanFlag("flag-x", false);
        // flushFlagsSafe -> flushFlags catches the exception; connect should proceed
        assertDoesNotThrow(() -> client._connectInternal());
        assertTrue(client.isConnected());
    }

    // -----------------------------------------------------------------------
    // Flush-before-fetch ordering in _connectInternal
    // -----------------------------------------------------------------------

    @Test
    void connectInternal_flushesBeforeFetchingFlags() throws ApiException {
        client.booleanFlag("feature-y", false);

        List<String> order = new ArrayList<>();
        when(mockApi.bulkRegisterFlags(any())).thenAnswer(inv -> {
            order.add("bulk");
            return new FlagBulkResponse();
        });
        when(mockApi.listFlags(isNull(), isNull())).thenAnswer(inv -> {
            order.add("list");
            return new FlagListResponse().data(List.of());
        });

        client._connectInternal();

        assertEquals(List.of("bulk", "list"), order, "bulk register must precede list");
    }

    // -----------------------------------------------------------------------
    // Scheduled future lifecycle
    // -----------------------------------------------------------------------

    @Test
    void connectInternal_schedulesPeriodicFlush() throws ApiException {
        client._connectInternal();

        // Scheduler started — we verify by disconnecting (cancel) without error
        assertDoesNotThrow(() -> client.disconnect());
    }

    @Test
    void disconnect_cancelsFlagFlushFuture_idempotent() throws ApiException {
        client._connectInternal();
        client.disconnect();
        // Second disconnect is safe
        assertDoesNotThrow(() -> client.disconnect());
    }

    // -----------------------------------------------------------------------
    // Threshold: 50 items triggers eager flush
    // -----------------------------------------------------------------------

    @Test
    void threshold50_booleanFlag_triggersEagerFlushThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        when(mockApi.bulkRegisterFlags(any())).thenAnswer(inv -> {
            latch.countDown();
            return new FlagBulkResponse();
        });

        // Declare 50 flags — last one crosses threshold
        for (int i = 0; i < 50; i++) {
            client.booleanFlag("bool-flag-" + i, false);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "eager flush should have fired within 5s");
    }

    @Test
    void threshold50_stringFlag_triggersEagerFlushThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        when(mockApi.bulkRegisterFlags(any())).thenAnswer(inv -> {
            latch.countDown();
            return new FlagBulkResponse();
        });

        for (int i = 0; i < 50; i++) {
            client.stringFlag("str-flag-" + i, "v" + i);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "eager flush should have fired within 5s");
    }

    @Test
    void threshold50_numberFlag_triggersEagerFlushThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        when(mockApi.bulkRegisterFlags(any())).thenAnswer(inv -> {
            latch.countDown();
            return new FlagBulkResponse();
        });

        for (int i = 0; i < 50; i++) {
            client.numberFlag("num-flag-" + i, i);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "eager flush should have fired within 5s");
    }

    @Test
    void threshold50_jsonFlag_triggersEagerFlushThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        when(mockApi.bulkRegisterFlags(any())).thenAnswer(inv -> {
            latch.countDown();
            return new FlagBulkResponse();
        });

        for (int i = 0; i < 50; i++) {
            client.jsonFlag("json-flag-" + i, Map.of("v", i));
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "eager flush should have fired within 5s");
    }
}
