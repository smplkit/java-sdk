package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
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
}
