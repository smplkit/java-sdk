package com.smplkit.flags;

import com.smplkit.SharedWebSocket;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.flags.types.FlagDeclaration;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the flags runtime start-retry behaviour introduced to fix the
 * bulk-register 500 / coordinated-rebuild production incident.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>Buffer is NOT drained until the bulk-register POST succeeds.</li>
 *   <li>{@code connected} stays {@code false} after a 500 response.</li>
 *   <li>A second start attempt (after the backoff) flushes the still-pending
 *       queue and connects successfully.</li>
 *   <li>WebSocket handlers are registered exactly once across retry attempts.</li>
 *   <li>A {@code fetchAllFlags} failure also triggers the retry path.</li>
 * </ul>
 */
class FlagsClientStartRetryTest {

    private FlagsApi mockApi;
    private ContextsApi mockContextsApi;
    private FlagsClient client;

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextsApi, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
        // Inject a mock WebSocket so a successful connect never dials a real
        // socket (keeps the whole class WS-free). Tests that need to verify WS
        // interactions install their own mock over this one.
        client.setSharedWs(Mockito.mock(SharedWebSocket.class));
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    // -----------------------------------------------------------------------
    // Buffer retained on 500
    // -----------------------------------------------------------------------

    @Test
    void bufferNotDrainedUntilFlushSucceeds() throws ApiException {
        // First bulk-register: 500. Second: OK.
        when(mockApi.bulkRegisterFlags(any()))
                .thenThrow(new ApiException(500, "Service Unavailable"))
                .thenReturn(null);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());

        client.register(new FlagDeclaration("feature-x", "BOOLEAN", false), false);
        assertEquals(1, client.flagBuffer.pendingCount());

        // First connect: flush 500s
        client.ensureConnected();

        assertEquals(1, client.flagBuffer.pendingCount(), "buffer must survive a failed flush");
    }

    // -----------------------------------------------------------------------
    // connected stays false after 500
    // -----------------------------------------------------------------------

    @Test
    void connectedFalseAfterFlushFailure() throws ApiException {
        when(mockApi.bulkRegisterFlags(any()))
                .thenThrow(new ApiException(500, "Service Unavailable"));
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());

        client.register(new FlagDeclaration("feature-x", "BOOLEAN", false), false);
        client.ensureConnected();

        assertFalse(client.isConnected());
        assertTrue(client.isRetryScheduled());
    }

    // -----------------------------------------------------------------------
    // 500-then-200: second start flushes pending queue and connects
    // -----------------------------------------------------------------------

    @Test
    void retryAfterFlushFailure_connectsAndClearsBuffer() throws ApiException {
        when(mockApi.bulkRegisterFlags(any()))
                .thenThrow(new ApiException(500, "Service Unavailable"))
                .thenReturn(null);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());

        client.register(new FlagDeclaration("feature-x", "BOOLEAN", false), false);

        // First attempt: fails
        client.ensureConnected();
        assertFalse(client.isConnected());
        assertEquals(1, client.flagBuffer.pendingCount());

        // Simulate the scheduled retry firing: reset retry state and reconnect
        client.disconnect();
        client.ensureConnected();

        assertTrue(client.isConnected());
        assertEquals(0, client.flagBuffer.pendingCount(), "buffer must be committed after successful flush");
    }

    // -----------------------------------------------------------------------
    // WebSocket handlers registered exactly once across retry attempts
    // -----------------------------------------------------------------------

    @Test
    void wsHandlersRegisteredExactlyOnce_acrossRetry() throws ApiException {
        SharedWebSocket mockWs = Mockito.mock(SharedWebSocket.class);
        client.setSharedWs(mockWs);

        when(mockApi.bulkRegisterFlags(any()))
                .thenThrow(new ApiException(500, "Service Unavailable"))
                .thenReturn(null);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());

        client.register(new FlagDeclaration("feature-x", "BOOLEAN", false), false);

        // First attempt: fails before WS registration
        client.ensureConnected();
        verify(mockWs, never()).on(anyString(), any());

        // Simulate retry
        client.disconnect();
        client.ensureConnected();

        // Handlers registered exactly once each
        verify(mockWs, times(1)).on(eq("flag_changed"), any());
        verify(mockWs, times(1)).on(eq("flag_deleted"), any());
        verify(mockWs, times(1)).on(eq("flags_changed"), any());
    }

    // -----------------------------------------------------------------------
    // fetchAllFlags failure also triggers retry
    // -----------------------------------------------------------------------

    @Test
    void fetchAllFlagsFailure_preventsConnect_schedulesRetry() throws ApiException {
        // Flush succeeds but listFlags 500s
        when(mockApi.bulkRegisterFlags(any())).thenReturn(null);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenThrow(new ApiException(500, "Service Unavailable"));

        client.ensureConnected();

        assertFalse(client.isConnected());
        assertTrue(client.isRetryScheduled());
    }

    // -----------------------------------------------------------------------
    // Real timer: retry fires automatically and connects (integration smoke-test)
    // -----------------------------------------------------------------------

    @Test
    void retryTimer_firesAutomaticallyAndConnects() throws Exception {
        AtomicInteger bulkCalls = new AtomicInteger();
        // The retry's successful flush is the deterministic barrier: count the
        // latch down on the second (succeeding) bulk-register so the test waits
        // on a real signal from the daemon retry thread, not a fixed sleep.
        CountDownLatch retryFlushLatch = new CountDownLatch(1);
        when(mockApi.bulkRegisterFlags(any())).thenAnswer(inv -> {
            if (bulkCalls.incrementAndGet() == 1) {
                throw new ApiException(500, "Service Unavailable");
            }
            retryFlushLatch.countDown();
            return null;
        });
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());

        client.register(new FlagDeclaration("feature-x", "BOOLEAN", false), false);
        client.ensureConnected();

        assertFalse(client.isConnected());

        // First backoff is 1 second; the scheduled retry fires on a daemon thread.
        // Wait on the flush barrier rather than sleeping a fixed interval.
        assertTrue(retryFlushLatch.await(5, TimeUnit.SECONDS),
                "automatic retry must re-attempt the flush within 5s");

        // The retry thread sets connected=true immediately after the successful
        // flush + fetch; spin on the volatile so the assertion is stable without
        // a fixed sleep (no coverage is lost to a premature read).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!client.isConnected() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertTrue(client.isConnected(), "runtime must be connected after automatic retry");
        assertEquals(0, client.flagBuffer.pendingCount());
    }

    // -----------------------------------------------------------------------
    // disconnect() cancels pending retry
    // -----------------------------------------------------------------------

    @Test
    void disconnect_cancelsPendingRetry() throws ApiException {
        when(mockApi.bulkRegisterFlags(any()))
                .thenThrow(new ApiException(500, "Service Unavailable"));

        client.register(new FlagDeclaration("feature-x", "BOOLEAN", false), false);
        client.ensureConnected();

        assertTrue(client.isRetryScheduled());

        client.disconnect();
        assertFalse(client.isRetryScheduled(), "disconnect must clear the retry flag");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static FlagListResponse emptyListResponse() {
        return new FlagListResponse().data(List.of());
    }
}
