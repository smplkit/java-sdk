package com.smplkit.flags;

import com.smplkit.SharedWebSocket;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SharedWebSocketFullTest {

    @Test
    void dispatch_firesListeners() {
        SharedWebSocket ws = new SharedWebSocket();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ws.on("test_event", received::set);

        Map<String, Object> data = Map.of("event", "test_event", "payload", "hello");
        ws.dispatch("test_event", data);

        assertNotNull(received.get());
        assertEquals("hello", received.get().get("payload"));
    }

    @Test
    void dispatch_doesNothingForUnregisteredEvent() {
        SharedWebSocket ws = new SharedWebSocket();
        assertDoesNotThrow(() -> ws.dispatch("unknown", Map.of()));
    }

    @Test
    void dispatch_catchesListenerExceptions() {
        SharedWebSocket ws = new SharedWebSocket();
        ws.on("test", data -> { throw new RuntimeException("boom"); });
        assertDoesNotThrow(() -> ws.dispatch("test", Map.of()));
    }

    @Test
    void onAndOff_multipleListeners() {
        SharedWebSocket ws = new SharedWebSocket();
        AtomicInteger count = new AtomicInteger();
        java.util.function.Consumer<Map<String, Object>> listener1 = data -> count.incrementAndGet();
        java.util.function.Consumer<Map<String, Object>> listener2 = data -> count.incrementAndGet();

        ws.on("test", listener1);
        ws.on("test", listener2);
        ws.dispatch("test", Map.of());
        assertEquals(2, count.get());

        ws.off("test", listener1);
        ws.dispatch("test", Map.of());
        assertEquals(3, count.get()); // Only listener2 fired
    }

    @Test
    void simulateMessage_connectedType() {
        SharedWebSocket ws = new SharedWebSocket();
        ws.simulateMessage("{\"type\":\"connected\"}");
        assertEquals("connected", ws.connectionStatus());
    }

    @Test
    void simulateMessage_errorType() {
        SharedWebSocket ws = new SharedWebSocket();
        assertDoesNotThrow(() -> ws.simulateMessage("{\"type\":\"error\",\"message\":\"bad\"}"));
    }

    @Test
    void simulateMessage_eventDispatch() {
        SharedWebSocket ws = new SharedWebSocket();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ws.on("flag_changed", received::set);

        ws.simulateMessage("{\"event\":\"flag_changed\",\"flag_key\":\"test\"}");
        assertNotNull(received.get());
        assertEquals("flag_changed", received.get().get("event"));
    }

    @Test
    void simulateMessage_invalidJson() {
        SharedWebSocket ws = new SharedWebSocket();
        assertDoesNotThrow(() -> ws.simulateMessage("not json"));
    }

    @Test
    void simulateMessage_noEventOrType() {
        SharedWebSocket ws = new SharedWebSocket();
        assertDoesNotThrow(() -> ws.simulateMessage("{\"data\":\"value\"}"));
    }

    @Test
    void simulateMessage_ping() {
        SharedWebSocket ws = new SharedWebSocket();
        assertDoesNotThrow(() -> ws.simulateMessage("ping"));
    }

    @Test
    void setConnectionStatus() {
        SharedWebSocket ws = new SharedWebSocket();
        ws.setConnectionStatus("connecting");
        assertEquals("connecting", ws.connectionStatus());
    }

    @Test
    void ensureConnected_noOp_whenAlreadyConnected() {
        SharedWebSocket ws = new SharedWebSocket();
        ws.setConnectionStatus("connected");
        assertDoesNotThrow(() -> ws.ensureConnected(Duration.ofMillis(100)));
    }

    @Test
    void close_multipleTimesIsSafe() {
        SharedWebSocket ws = new SharedWebSocket();
        ws.close();
        ws.close(); // second close should not throw
    }

    @Test
    void simulateMessage_connectedType_countsDownConnectedLatch() throws Exception {
        SharedWebSocket ws = new SharedWebSocket();
        // Set the connected latch so the "connected" handler exercises the
        // `latch != null` true arm and actually counts it down.
        CountDownLatch latch = new CountDownLatch(1);
        ws.connectedLatch = latch;

        ws.simulateMessage("{\"type\":\"connected\"}");

        assertEquals("connected", ws.connectionStatus());
        assertTrue(latch.await(1, TimeUnit.SECONDS), "connected latch should be counted down");
    }

    @Test
    void ensureConnected_restartsWhenThreadDied() throws Exception {
        // Drive the `wsThread != null && !wsThread.isAlive()` second operand:
        // inject an already-terminated thread, then call ensureConnected while
        // not connected. The no-arg test constructor's start() is a safe no-op
        // (null connector), so this is fully deterministic and socket-free.
        SharedWebSocket ws = new SharedWebSocket();

        Thread dead = new Thread(() -> { });
        dead.start();
        dead.join(); // guaranteed terminated
        assertFalse(dead.isAlive());
        setWsThread(ws, dead);

        // status is "disconnected" (not "connected"), so ensureConnected
        // proceeds to the `wsThread == null || !wsThread.isAlive()` check.
        assertDoesNotThrow(() -> ws.ensureConnected(Duration.ofMillis(10)));
    }

    @Test
    void connectToServer_realConnector_invokesBuildAsync() {
        // Built with the real-httpClient constructor so wsConnector is the
        // production lambda (newWebSocketBuilder().header(...).buildAsync().join()).
        // A refused port makes join() throw, but the lambda body runs first.
        HttpClient httpClient = HttpClient.newHttpClient();
        SharedWebSocket ws = new SharedWebSocket(httpClient, "https://127.0.0.1:1", "test-key");
        try {
            // Synchronous on the test thread -> the connector lambda is exercised
            // deterministically (no daemon-thread timing).
            assertThrows(Exception.class, ws::connectToServer);
        } finally {
            ws.close();
        }
    }

    /** Injects a thread into the private wsThread field. */
    private static void setWsThread(SharedWebSocket ws, Thread t) throws Exception {
        var f = SharedWebSocket.class.getDeclaredField("wsThread");
        f.setAccessible(true);
        f.set(ws, t);
    }
}
