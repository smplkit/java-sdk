package com.smplkit.flags;

import com.smplkit.SharedWebSocket;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SharedWebSocketTest {

    @Test
    void onAndOff_registerAndUnregister() {
        SharedWebSocket ws = new SharedWebSocket();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();

        ws.on("test_event", received::set);
        assertEquals("disconnected", ws.connectionStatus());

        // off should not throw
        ws.off("test_event", received::set);
        ws.off("nonexistent_event", received::set);
    }

    @Test
    void close_doesNotThrow() {
        SharedWebSocket ws = new SharedWebSocket();
        assertDoesNotThrow(ws::close);
    }

    @Test
    void startDoesNotThrow_withTestConstructor() {
        SharedWebSocket ws = new SharedWebSocket();
        // start with null httpClient should not throw (returns early)
        assertDoesNotThrow(ws::start);
    }

    @Test
    void connectionStatus_defaultsToDisconnected() {
        SharedWebSocket ws = new SharedWebSocket();
        assertEquals("disconnected", ws.connectionStatus());
    }

    // --- buildWsUrl tests ---

    @Test
    void buildWsUrl_httpsPrefix() {
        String result = SharedWebSocket.buildWsUrl("https://app.smplkit.com", "key123");
        assertEquals("wss://app.smplkit.com/api/ws/v1/events?api_key=key123", result);
    }

    @Test
    void buildWsUrl_httpPrefix() {
        String result = SharedWebSocket.buildWsUrl("http://localhost:8080", "key123");
        assertEquals("ws://localhost:8080/api/ws/v1/events?api_key=key123", result);
    }

    @Test
    void buildWsUrl_noSchemePrefix() {
        String result = SharedWebSocket.buildWsUrl("app.smplkit.com", "key123");
        assertEquals("wss://app.smplkit.com/api/ws/v1/events?api_key=key123", result);
    }

    @Test
    void buildWsUrl_trailingWhitespace() {
        String result = SharedWebSocket.buildWsUrl("https://app.smplkit.com  ", "key123");
        assertEquals("wss://app.smplkit.com/api/ws/v1/events?api_key=key123", result);
    }

    // --- ensureConnected with thread start ---

    @Test
    void ensureConnected_startsThreadWhenNotConnected() {
        // Test constructor has null httpClient, so start() returns early
        SharedWebSocket ws = new SharedWebSocket();
        // ensureConnected when not connected and thread is null
        assertDoesNotThrow(() -> ws.ensureConnected(Duration.ofMillis(50)));
    }

    @Test
    void ensureConnected_withLatchTimeout() throws Exception {
        // Create a real SharedWebSocket that will fail to connect (bad URL)
        // but will exercise the latch.await path
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SharedWebSocket ws = new SharedWebSocket(httpClient, "https://localhost:19999", "test-key");

        // ensureConnected will start the thread and await the latch (which will timeout)
        ws.ensureConnected(Duration.ofMillis(100));

        // Clean up
        ws.close();
    }

    @Test
    void close_withRunningThread() throws Exception {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SharedWebSocket ws = new SharedWebSocket(httpClient, "https://localhost:19999", "test-key");

        // Start the thread
        ws.start();
        Thread.sleep(50); // Let it start

        // Close should interrupt and join the thread
        assertDoesNotThrow(ws::close);
        assertEquals("disconnected", ws.connectionStatus());
    }

    @Test
    void close_afterAlreadyClosed() throws Exception {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        SharedWebSocket ws = new SharedWebSocket(httpClient, "https://localhost:19999", "test-key");
        ws.close();
        // Second close should be safe
        assertDoesNotThrow(ws::close);
    }

    @Test
    void start_whenClosed_doesNothing() {
        SharedWebSocket ws = new SharedWebSocket();
        ws.close(); // sets closed = true
        ws.start(); // should return early because closed
        assertEquals("disconnected", ws.connectionStatus());
    }
}
