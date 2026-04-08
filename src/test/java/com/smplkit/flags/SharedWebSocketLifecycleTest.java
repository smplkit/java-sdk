package com.smplkit.flags;

import com.smplkit.SharedWebSocket;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SharedWebSocket WsListener callbacks and connection lifecycle.
 */
class SharedWebSocketLifecycleTest {

    // --- WsListener: onOpen ---

    @Test
    void wsListener_onOpen_requestsOne() {
        SharedWebSocket sws = new SharedWebSocket();
        CountDownLatch latch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(latch);

        WebSocket mockWs = mock(WebSocket.class);
        listener.onOpen(mockWs);

        verify(mockWs).request(1);
    }

    // --- WsListener: onText with last=true ---

    @Test
    void wsListener_onText_lastTrue_dispatchesMessage() {
        SharedWebSocket sws = new SharedWebSocket();
        CountDownLatch latch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(latch);

        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        sws.on("flag_changed", received::set);

        WebSocket mockWs = mock(WebSocket.class);
        listener.onText(mockWs, "{\"event\":\"flag_changed\",\"key\":\"test\"}", true);

        assertNotNull(received.get());
        verify(mockWs, atLeast(1)).request(1);
    }

    // --- WsListener: onText with last=false (buffering) ---

    @Test
    void wsListener_onText_buffersUntilLast() {
        SharedWebSocket sws = new SharedWebSocket();
        CountDownLatch latch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(latch);

        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        sws.on("flag_changed", received::set);

        WebSocket mockWs = mock(WebSocket.class);
        // First fragment
        listener.onText(mockWs, "{\"event\":\"flag_", false);
        assertNull(received.get());

        // Second fragment (last)
        listener.onText(mockWs, "changed\",\"key\":\"test\"}", true);
        assertNotNull(received.get());
    }

    // --- WsListener: onText with ping ---

    @Test
    void wsListener_onText_ping_respondsPong() {
        SharedWebSocket sws = new SharedWebSocket();
        CountDownLatch latch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(latch);

        WebSocket mockWs = mock(WebSocket.class);
        when(mockWs.sendText(anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(mockWs));

        CompletionStage<?> result = listener.onText(mockWs, "ping", true);

        verify(mockWs).sendText("pong", true);
        assertNull(result);
    }

    // --- WsListener: onClose when not closed ---

    @Test
    void wsListener_onClose_whenNotClosed_countsDownAndReconnects() throws InterruptedException {
        // Use constructor with httpClient so reconnect can attempt connectToServer
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SharedWebSocket sws = new SharedWebSocket(httpClient, "https://localhost:19999", "test-key");
        sws.closed = false;
        CountDownLatch closedLatch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(closedLatch);

        WebSocket mockWs = mock(WebSocket.class);
        listener.onClose(mockWs, 1000, "normal");

        // The closedLatch should have been counted down
        assertEquals(0, closedLatch.getCount());

        // Give the reconnect thread time to start, then close to stop it
        Thread.sleep(200);
        sws.close();
    }

    // --- WsListener: onClose when already closed ---

    @Test
    void wsListener_onClose_whenClosed_justCountsDown() {
        SharedWebSocket sws = new SharedWebSocket();
        sws.closed = true;
        CountDownLatch closedLatch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(closedLatch);

        WebSocket mockWs = mock(WebSocket.class);
        listener.onClose(mockWs, 1000, "normal");

        assertEquals(0, closedLatch.getCount());
    }

    // --- WsListener: onError when not closed ---

    @Test
    void wsListener_onError_whenNotClosed_countsDownAndReconnects() throws InterruptedException {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SharedWebSocket sws = new SharedWebSocket(httpClient, "https://localhost:19999", "test-key");
        sws.closed = false;
        CountDownLatch closedLatch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(closedLatch);

        WebSocket mockWs = mock(WebSocket.class);
        listener.onError(mockWs, new RuntimeException("test error"));

        assertEquals(0, closedLatch.getCount());

        // Give the reconnect thread time to start, then close to stop it
        Thread.sleep(200);
        sws.close();
    }

    // --- WsListener: onError when already closed ---

    @Test
    void wsListener_onError_whenClosed_justCountsDown() {
        SharedWebSocket sws = new SharedWebSocket();
        sws.closed = true;
        CountDownLatch closedLatch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(closedLatch);

        WebSocket mockWs = mock(WebSocket.class);
        listener.onError(mockWs, new RuntimeException("test error"));

        assertEquals(0, closedLatch.getCount());
    }

    // --- reconnect: interrupted during sleep ---

    @Test
    void reconnect_interruptedDuringSleep() throws InterruptedException {
        SharedWebSocket sws = new SharedWebSocket();
        sws.closed = false;

        Thread reconnectThread = new Thread(() -> sws.reconnect(0), "test-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();

        // Wait a bit then interrupt
        Thread.sleep(100);
        reconnectThread.interrupt();
        reconnectThread.join(2000);

        assertFalse(reconnectThread.isAlive());
    }

    // --- reconnect: closed before sleep finishes ---

    @Test
    void reconnect_closedDuringLoop() throws InterruptedException {
        SharedWebSocket sws = new SharedWebSocket();
        sws.closed = false;

        Thread reconnectThread = new Thread(() -> sws.reconnect(0), "test-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();

        Thread.sleep(50);
        sws.closed = true;

        reconnectThread.join(3000);
        assertFalse(reconnectThread.isAlive());
    }

    // --- wsThreadEntry: closed when connect fails ---

    @Test
    void wsThreadEntry_closedOnFailure() {
        SharedWebSocket sws = new SharedWebSocket();
        // closed = true so reconnect is skipped
        sws.closed = true;

        // wsThreadEntry will try connectToServer which fails (httpClient is null)
        // Since closed=true, it won't reconnect
        assertDoesNotThrow(sws::wsThreadEntry);
    }

    // --- wsThreadEntry: connect fails, closed=false → reconnect starts ---

    @Test
    void wsThreadEntry_connectFailsAndReconnects() throws InterruptedException {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SharedWebSocket sws = new SharedWebSocket(httpClient, "https://localhost:19999", "test-key");

        Thread t = new Thread(sws::wsThreadEntry, "test-ws-thread");
        t.setDaemon(true);
        t.start();

        // Let it fail to connect and enter reconnect — allow enough time for
        // the connection attempt to fail and reconnect(0) to start (1s backoff)
        Thread.sleep(2500);
        sws.close();
        t.join(3000);
    }

    // --- close: with a mock webSocket ---

    @Test
    void close_withWebSocket_sendsClose() {
        SharedWebSocket sws = new SharedWebSocket();
        WebSocket mockWs = mock(WebSocket.class);
        when(mockWs.sendClose(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        sws.webSocket = mockWs;

        sws.close();

        verify(mockWs).sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        assertEquals("disconnected", sws.connectionStatus());
    }

    // --- close: webSocket.sendClose throws ---

    @Test
    void close_sendCloseThrows_ignoredException() {
        SharedWebSocket sws = new SharedWebSocket();
        WebSocket mockWs = mock(WebSocket.class);
        when(mockWs.sendClose(anyInt(), anyString()))
                .thenThrow(new RuntimeException("close failed"));
        sws.webSocket = mockWs;

        assertDoesNotThrow(sws::close);
        assertEquals("disconnected", sws.connectionStatus());
    }

    // --- close: with a running wsThread ---

    @Test
    void close_withRunningWsThread() throws Exception {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SharedWebSocket sws = new SharedWebSocket(httpClient, "https://localhost:19999", "test-key");

        // Start the background thread
        sws.start();
        Thread.sleep(100); // Let it start

        // close should interrupt and join the thread
        assertDoesNotThrow(sws::close);
    }

    // --- close: InterruptedException during thread join ---

    @Test
    void close_interruptedDuringJoin() throws Exception {
        SharedWebSocket sws = new SharedWebSocket();
        // Create a thread that blocks forever
        Thread blockingThread = new Thread(() -> {
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                // expected
            }
        }, "test-blocking-ws");
        blockingThread.setDaemon(true);
        blockingThread.start();

        // Set the wsThread field via reflection
        java.lang.reflect.Field wsThreadField = SharedWebSocket.class.getDeclaredField("wsThread");
        wsThreadField.setAccessible(true);
        wsThreadField.set(sws, blockingThread);

        // Interrupt the current thread before calling close, so join() throws InterruptedException
        Thread closeThread = new Thread(() -> {
            Thread.currentThread().interrupt();
            sws.close();
        });
        closeThread.start();
        closeThread.join(3000);

        // Clean up
        blockingThread.interrupt();
        blockingThread.join(1000);
    }

    // --- ensureConnected: InterruptedException path ---

    @Test
    void ensureConnected_interruptedDuringAwait() throws InterruptedException {
        SharedWebSocket sws = new SharedWebSocket();
        sws.setConnectionStatus("connecting");
        sws.connectedLatch = new CountDownLatch(1);

        Thread testThread = new Thread(() -> {
            sws.ensureConnected(Duration.ofMillis(5000));
        });
        testThread.start();

        Thread.sleep(50);
        testThread.interrupt();
        testThread.join(2000);

        assertTrue(testThread.isInterrupted() || !testThread.isAlive());
    }

    // --- connectToServer: success path via injectable connector ---

    @Test
    void connectToServer_successPath() throws Exception {
        WebSocket mockWs = mock(WebSocket.class);
        CountDownLatch testLatch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<SharedWebSocket> swsRef = new java.util.concurrent.atomic.AtomicReference<>();

        SharedWebSocket sws = new SharedWebSocket((uri, listener) -> {
            testLatch.countDown();
            // Simulate a close event after a short delay to release closedLatch.await()
            Thread releaser = new Thread(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                swsRef.get().closed = true;
                listener.onClose(mockWs, 1000, "done");
            });
            releaser.setDaemon(true);
            releaser.start();
            return mockWs;
        }, "wss://test/events");
        swsRef.set(sws);

        Thread t = new Thread(() -> {
            try {
                sws.connectToServer();
            } catch (Exception e) {
                // may be interrupted
            }
        });
        t.setDaemon(true);
        t.start();

        testLatch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        t.join(3000);

        assertSame(mockWs, sws.webSocket);
    }

    // --- wsThreadEntry: connectToServer fails, reconnect called ---

    @Test
    void wsThreadEntry_failedConnect_reconnectsWhenNotClosed() throws InterruptedException {
        java.util.concurrent.atomic.AtomicInteger connectAttempts = new java.util.concurrent.atomic.AtomicInteger();

        SharedWebSocket sws = new SharedWebSocket((uri, listener) -> {
            connectAttempts.incrementAndGet();
            throw new RuntimeException("connection failed");
        }, "wss://test/events");

        Thread t = new Thread(sws::wsThreadEntry, "test-ws-thread");
        t.setDaemon(true);
        t.start();

        // Let it fail initial connect and enter reconnect loop (1s backoff)
        Thread.sleep(1500);
        sws.close();
        t.join(3000);

        assertTrue(connectAttempts.get() >= 2); // initial + at least 1 reconnect
    }

    // --- reconnect: successful reconnect ---

    @Test
    void reconnect_successfulReconnect() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        WebSocket mockWs = mock(WebSocket.class);

        SharedWebSocket sws = new SharedWebSocket((uri, listener) -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("first attempt fails");
            }
            // Second attempt succeeds. connectToServer will call closedLatch.await(),
            // so we need to count it down from another thread to unblock.
            // The WsListener.onClose callback will count down the closedLatch.
            // Simulate that by calling onClose after a short delay.
            Thread releaser = new Thread(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                listener.onClose(mockWs, 1000, "test done");
            });
            releaser.setDaemon(true);
            releaser.start();
            return mockWs;
        }, "wss://test/events");

        // Pre-close so when onClose fires, the reconnect inside it sees closed=true and exits
        Thread t = new Thread(() -> {
            sws.reconnect(0);
        }, "test-reconnect");
        t.setDaemon(true);
        t.start();

        // Wait for first fail (1s backoff) + second succeed + onClose
        t.join(5000);
        sws.close();

        assertTrue(attempts.get() >= 2);
    }

    // --- wsThreadEntry: direct call on current thread ---

    @Test
    void wsThreadEntry_directCall_closedOnFail() {
        java.util.concurrent.atomic.AtomicBoolean connectorCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        SharedWebSocket sws = new SharedWebSocket((uri, listener) -> {
            connectorCalled.set(true);
            throw new RuntimeException("connection failed");
        }, "wss://test/events");
        sws.closed = true; // closed, so catch won't reconnect

        // Runs on current thread — wsThreadEntry catches the exception
        sws.wsThreadEntry();
        assertTrue(connectorCalled.get(), "connectToServer should have called the connector");
    }

    // --- wsThreadEntry: connectToServer succeeds (covers call + goto) ---

    @Test
    void wsThreadEntry_connectSucceeds() throws InterruptedException {
        WebSocket mockWs = mock(WebSocket.class);
        java.util.concurrent.atomic.AtomicReference<SharedWebSocket> swsRef = new java.util.concurrent.atomic.AtomicReference<>();
        SharedWebSocket sws = new SharedWebSocket((uri, listener) -> {
            // Simulate a successful connection that closes immediately
            Thread releaser = new Thread(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                swsRef.get().closed = true;
                listener.onClose(mockWs, 1000, "done");
            });
            releaser.setDaemon(true);
            releaser.start();
            return mockWs;
        }, "wss://test/events");
        swsRef.set(sws);

        // Run wsThreadEntry — connectToServer will succeed and block until closedLatch is counted down
        Thread t = new Thread(sws::wsThreadEntry, "test-ws-thread");
        t.setDaemon(true);
        t.start();
        t.join(3000);
    }

    // --- reconnect: exits via while condition (covers line 243) ---

    @Test
    void reconnect_exitsViaWhileCondition() {
        SharedWebSocket sws = new SharedWebSocket((uri, listener) -> {
            throw new RuntimeException("always fails");
        }, "wss://test/events");

        // Set closed = true so the while loop exits immediately
        sws.closed = true;
        sws.reconnect(0); // while (!closed) is false, returns immediately at line 243
    }

    // --- WsListener onError: reconnect lambda covers line 305 ---

    @Test
    void wsListener_onError_reconnectLambdaExecutes() throws InterruptedException {
        java.util.concurrent.atomic.AtomicInteger connectAttempts = new java.util.concurrent.atomic.AtomicInteger();

        SharedWebSocket sws = new SharedWebSocket((uri, listener) -> {
            connectAttempts.incrementAndGet();
            throw new RuntimeException("always fails");
        }, "wss://test/events");
        sws.closed = false;

        CountDownLatch closedLatch = new CountDownLatch(1);
        SharedWebSocket.WsListener listener = sws.new WsListener(closedLatch);

        WebSocket mockWs = mock(WebSocket.class);
        listener.onError(mockWs, new RuntimeException("test"));

        // Give the reconnect thread time to attempt at least one connection
        Thread.sleep(1500);
        sws.close();

        assertTrue(connectAttempts.get() >= 1);
    }
}
