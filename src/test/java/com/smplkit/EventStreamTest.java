package com.smplkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventStreamTest {

    @Test
    void onAndOff_registerAndUnregister() {
        EventStream stream = new EventStream();
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();

        stream.on("test_event", received::set);
        assertEquals("disconnected", stream.connectionStatus());

        // off should not throw
        stream.off("test_event", received::set);
        stream.off("nonexistent_event", received::set);
    }

    @Test
    void onReconnectAndOffReconnect_registerAndUnregister() {
        EventStream stream = new EventStream();
        AtomicInteger count = new AtomicInteger();
        Runnable refetch = count::incrementAndGet;

        stream.onReconnect(refetch);
        stream.offReconnect(refetch);
        // Removing an unregistered callback is a no-op
        stream.offReconnect(count::incrementAndGet);
        assertEquals(0, count.get());
    }

    @Test
    void close_doesNotThrow() {
        EventStream stream = new EventStream();
        assertDoesNotThrow(stream::close);
    }

    @Test
    void startDoesNotThrow_withTestConstructor() {
        EventStream stream = new EventStream();
        // start with null connector should not throw (returns early)
        assertDoesNotThrow(stream::start);
    }

    @Test
    void connectionStatus_defaultsToDisconnected() {
        EventStream stream = new EventStream();
        assertEquals("disconnected", stream.connectionStatus());
    }

    // --- buildEventsUrl tests ---

    @Test
    void buildEventsUrl_httpsPrefix() {
        String result = EventStream.buildEventsUrl("https://app.smplkit.com");
        assertEquals("https://app.smplkit.com/api/v1/events", result);
    }

    @Test
    void buildEventsUrl_httpPrefix() {
        String result = EventStream.buildEventsUrl("http://localhost:8080");
        assertEquals("http://localhost:8080/api/v1/events", result);
    }

    @Test
    void buildEventsUrl_noSchemePrefix() {
        String result = EventStream.buildEventsUrl("app.smplkit.com");
        assertEquals("https://app.smplkit.com/api/v1/events", result);
    }

    @Test
    void buildEventsUrl_trailingWhitespace() {
        String result = EventStream.buildEventsUrl("https://app.smplkit.com  ");
        assertEquals("https://app.smplkit.com/api/v1/events", result);
    }

    // --- backoff arithmetic ---

    @Test
    void nextDelayMs_doublesFromServerSeededBase() {
        EventStream stream = new EventStream();
        stream.baseRetryMs = 250;
        assertEquals(250, stream.nextDelayMs());
        assertEquals(500, stream.nextDelayMs());
        assertEquals(1000, stream.nextDelayMs());
        assertEquals(2000, stream.nextDelayMs());
    }

    @Test
    void nextDelayMs_defaultBaseIsOneSecond() {
        EventStream stream = new EventStream();
        assertEquals(EventStream.DEFAULT_RETRY_MS, stream.nextDelayMs());
    }

    @Test
    void nextDelayMs_capsAtSixtySeconds() {
        EventStream stream = new EventStream();
        stream.attempt = 10; // 1000 << 10 > 60s
        assertEquals(EventStream.MAX_BACKOFF_MS, stream.nextDelayMs());
        // Shift is clamped so a huge attempt count cannot overflow
        stream.attempt = 63;
        assertEquals(EventStream.MAX_BACKOFF_MS, stream.nextDelayMs());
    }

    @Test
    void nextDelayMs_resetToZeroAttempts_returnsBase() {
        EventStream stream = new EventStream();
        stream.baseRetryMs = 40;
        stream.attempt = 5;
        stream.attempt = 0; // what runLoop does after a successful connect
        assertEquals(40, stream.nextDelayMs());
    }

    // --- ensureConnected with thread start ---

    @Test
    void ensureConnected_startsThreadWhenNotConnected() {
        // Test constructor has null connector, so start() returns early
        EventStream stream = new EventStream();
        assertDoesNotThrow(() -> stream.ensureConnected(Duration.ofMillis(50)));
    }

    @Test
    void ensureConnected_withLatchTimeout() {
        // A real EventStream pointed at a refused localhost port: it will fail
        // to connect but exercises the latch.await path.
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        EventStream stream = new EventStream(httpClient, "http://localhost:19999", "test-key");

        stream.ensureConnected(Duration.ofMillis(100));

        stream.close();
    }

    @Test
    void close_withRunningThread() throws Exception {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        EventStream stream = new EventStream(httpClient, "http://localhost:19999", "test-key");

        stream.start();
        Thread.sleep(50); // Let it start

        assertDoesNotThrow(stream::close);
        assertEquals("disconnected", stream.connectionStatus());
    }

    @Test
    void close_afterAlreadyClosed() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        EventStream stream = new EventStream(httpClient, "http://localhost:19999", "test-key");
        stream.close();
        // Second close should be safe
        assertDoesNotThrow(stream::close);
    }

    @Test
    void start_whenClosed_doesNothing() {
        EventStream stream = new EventStream();
        stream.close(); // sets closed = true
        stream.start(); // should return early because closed
        assertEquals("disconnected", stream.connectionStatus());
    }

    @Test
    void start_whenThreadAlreadyAlive_isNoOp() throws Exception {
        // A connector whose body blocks until closed keeps the stream thread
        // alive; a second start() must not spawn a replacement thread.
        java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
        EventStream stream = new EventStream((uri) -> {
            connected.countDown();
            return new EventStream.Connection(200, "text/event-stream",
                    new java.io.InputStream() {
                        @Override
                        public int read() throws java.io.IOException {
                            try {
                                Thread.sleep(60_000);
                            } catch (InterruptedException e) {
                                throw new java.io.IOException("interrupted");
                            }
                            return -1;
                        }
                    });
        }, "http://test/api/v1/events");

        stream.start();
        assertTrue(connected.await(2, java.util.concurrent.TimeUnit.SECONDS));
        Thread first = threadOf(stream);
        stream.start(); // guard: already-alive thread → no-op
        assertSame(first, threadOf(stream));
        stream.close();
    }

    private static Thread threadOf(EventStream stream) throws Exception {
        var f = EventStream.class.getDeclaredField("streamThread");
        f.setAccessible(true);
        return (Thread) f.get(stream);
    }
}
