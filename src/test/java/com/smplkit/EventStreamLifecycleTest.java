package com.smplkit;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Connection-lifecycle tests for {@link EventStream}: connect validation,
 * the reconnect loop, refetch-on-reconnect, and teardown. All connections
 * are injected via the {@link EventStream.Connector} seam — no sockets.
 */
class EventStreamLifecycleTest {

    private static final String CONNECT_FRAMES = "retry: 1000\n\nevent: connected\ndata: {}\n\n";

    private static InputStream frames(String raw) {
        return new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ connectOnce

    @Test
    void connectOnce_success_marksConnectedAndReadsToEof() throws Exception {
        AtomicReference<java.util.Map<String, Object>> received = new AtomicReference<>();
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "text/event-stream; charset=utf-8",
                        frames(CONNECT_FRAMES + "event: flag_changed\ndata: {\"id\": \"k\"}\n\n")),
                "http://test/api/v1/events");
        stream.on("flag_changed", received::set);

        assertTrue(stream.connectOnce());

        assertEquals("connected", stream.connectionStatus());
        assertEquals("k", received.get().get("id"));
        assertTrue(stream.everConnected);
    }

    @Test
    void connectOnce_non200_isRejected() throws Exception {
        TrackingStream body = new TrackingStream("unauthorized");
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(401, "application/json", body),
                "http://test/api/v1/events");

        assertFalse(stream.connectOnce());

        assertTrue(body.closed.get(), "rejected connection body must be closed");
        assertNotEquals("connected", stream.connectionStatus());
        assertFalse(stream.everConnected);
    }

    @Test
    void connectOnce_wrongContentType_isRejected() throws Exception {
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "application/json", frames("{}")),
                "http://test/api/v1/events");

        assertFalse(stream.connectOnce());
        assertFalse(stream.everConnected);
    }

    @Test
    void connectOnce_nullContentType_isRejected() throws Exception {
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, null, frames("")),
                "http://test/api/v1/events");

        assertFalse(stream.connectOnce());
    }

    @Test
    void connectOnce_rejectedBodyCloseFailure_isSwallowed() {
        InputStream explosive = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(500, "text/plain", explosive),
                "http://test/api/v1/events");

        assertDoesNotThrow(() -> assertFalse(stream.connectOnce()));
    }

    // ------------------------------------------------- refetch on reconnect

    @Test
    void refetchCallbacks_notInvokedOnInitialConnect() throws Exception {
        AtomicInteger refetches = new AtomicInteger();
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "text/event-stream", frames(CONNECT_FRAMES)),
                "http://test/api/v1/events");
        stream.onReconnect(refetches::incrementAndGet);

        assertTrue(stream.connectOnce());

        assertEquals(0, refetches.get(), "refetch must NOT run on the initial connect");
    }

    @Test
    void refetchCallbacks_invokedOnEverySuccessfulReconnect() throws Exception {
        AtomicInteger refetches = new AtomicInteger();
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "text/event-stream", frames(CONNECT_FRAMES)),
                "http://test/api/v1/events");
        stream.onReconnect(refetches::incrementAndGet);

        stream.connectOnce(); // initial
        stream.connectOnce(); // reconnect #1
        stream.connectOnce(); // reconnect #2

        assertEquals(2, refetches.get());
    }

    @Test
    void refetchCallback_exception_doesNotBreakOthers() throws Exception {
        AtomicInteger refetches = new AtomicInteger();
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "text/event-stream", frames(CONNECT_FRAMES)),
                "http://test/api/v1/events");
        stream.onReconnect(() -> { throw new RuntimeException("refetch boom"); });
        stream.onReconnect(refetches::incrementAndGet);

        stream.connectOnce();
        assertDoesNotThrow(stream::connectOnce);

        assertEquals(1, refetches.get(), "second callback still runs after the first throws");
    }

    @Test
    void failedReconnect_doesNotTriggerRefetch() throws Exception {
        AtomicInteger refetches = new AtomicInteger();
        AtomicInteger connects = new AtomicInteger();
        EventStream stream = new EventStream(
                uri -> {
                    if (connects.incrementAndGet() == 2) {
                        return new EventStream.Connection(500, "text/plain", frames(""));
                    }
                    return new EventStream.Connection(200, "text/event-stream", frames(CONNECT_FRAMES));
                },
                "http://test/api/v1/events");
        stream.onReconnect(refetches::incrementAndGet);

        stream.connectOnce(); // initial success
        stream.connectOnce(); // rejected — no refetch
        assertEquals(0, refetches.get());
        stream.connectOnce(); // successful reconnect — refetch
        assertEquals(1, refetches.get());
    }

    // ------------------------------------------------------------ readStream

    @Test
    void readStream_readFailure_isCaughtAndReported() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("mid-stream failure");
            }
        };
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "text/event-stream", broken),
                "http://test/api/v1/events");

        assertDoesNotThrow(() -> assertTrue(stream.connectOnce()));
    }

    @Test
    void readStream_recordsDisconnectGaugeOnDrop() throws Exception {
        // Verified through the metrics seam in TelemetryIntegrationTest; here
        // just prove EOF completes without metrics wired (null reporter path).
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "text/event-stream", frames(CONNECT_FRAMES)),
                "http://test/api/v1/events");
        assertTrue(stream.connectOnce());
        assertEquals("connected", stream.connectionStatus());
    }

    // -------------------------------------------------------------- runLoop

    @Test
    void runLoop_connectFailsAndRetriesWithBackoff() throws Exception {
        AtomicInteger connectAttempts = new AtomicInteger();
        List<Long> delays = new CopyOnWriteArrayList<>();
        CountDownLatch threeDelays = new CountDownLatch(3);
        EventStream stream = new EventStream(
                uri -> {
                    connectAttempts.incrementAndGet();
                    throw new RuntimeException("connection failed");
                },
                "http://test/api/v1/events");
        stream.baseRetryMs = 5; // keep the test fast; arithmetic is exact
        stream.delayListenerForTests = d -> {
            delays.add(d);
            threeDelays.countDown();
        };

        Thread t = new Thread(stream::runLoop, "test-events-thread");
        t.setDaemon(true);
        t.start();

        assertTrue(threeDelays.await(5, TimeUnit.SECONDS), "three backoff delays within 5s");
        stream.close();
        t.join(3000);

        assertTrue(connectAttempts.get() >= 3);
        assertEquals(List.of(5L, 10L, 20L), delays.subList(0, 3), "delays double without reset");
    }

    @Test
    void runLoop_successResetsBackoffToBase() throws Exception {
        // Connections alternate: success, fail, fail, success — the delay
        // sequence must be [base, 2*base, 4*base, base].
        AtomicInteger connects = new AtomicInteger();
        List<Long> delays = new CopyOnWriteArrayList<>();
        CountDownLatch fourDelays = new CountDownLatch(4);
        EventStream stream = new EventStream(
                uri -> {
                    int n = connects.incrementAndGet();
                    if (n == 2 || n == 3) {
                        return new EventStream.Connection(503, "text/plain", frames(""));
                    }
                    // Successful connects seed retry: 8ms then EOF immediately
                    return new EventStream.Connection(200, "text/event-stream",
                            frames("retry: 8\n\nevent: connected\ndata: {}\n\n"));
                },
                "http://test/api/v1/events");
        stream.delayListenerForTests = d -> {
            delays.add(d);
            fourDelays.countDown();
        };

        Thread t = new Thread(stream::runLoop, "test-events-thread");
        t.setDaemon(true);
        t.start();

        assertTrue(fourDelays.await(5, TimeUnit.SECONDS), "four delays within 5s");
        stream.close();
        t.join(3000);

        assertEquals(List.of(8L, 16L, 32L, 8L), delays.subList(0, 4),
                "backoff must reset to the server-seeded base after a successful connect");
    }

    @Test
    void runLoop_exitsWhenClosedDuringConnect() {
        EventStream stream = new EventStream(
                uri -> {
                    throw new RuntimeException("always fails");
                },
                "http://test/api/v1/events");
        stream.closed = true;
        // while (!closed) is false — returns immediately
        assertDoesNotThrow(stream::runLoop);
    }

    @Test
    void runLoop_closedInsideConnector_skipsWarningAndReturns() {
        // The connector closes its own stream and then throws: the catch sees
        // closed=true (no warning) and the loop exits before sleeping.
        AtomicBoolean connectorCalled = new AtomicBoolean(false);
        AtomicReference<EventStream> ref = new AtomicReference<>();
        EventStream stream = new EventStream(uri -> {
            connectorCalled.set(true);
            ref.get().closed = true;
            throw new RuntimeException("boom");
        }, "http://test/api/v1/events");
        ref.set(stream);

        assertDoesNotThrow(stream::runLoop);
        assertTrue(connectorCalled.get());
        assertTrue(stream.closed);
    }

    @Test
    void runLoop_closedAfterSuccessfulStream_returnsWithoutSleeping() {
        AtomicReference<EventStream> ref = new AtomicReference<>();
        EventStream stream = new EventStream(uri -> {
            // The stream EOFs immediately; mark closed so the loop exits at
            // the post-connect check instead of entering the backoff sleep.
            ref.get().closed = true;
            return new EventStream.Connection(200, "text/event-stream", frames(CONNECT_FRAMES));
        }, "http://test/api/v1/events");
        ref.set(stream);

        assertDoesNotThrow(stream::runLoop);
    }

    @Test
    void runLoop_interruptedDuringBackoffSleep_exits() throws Exception {
        EventStream stream = new EventStream(
                uri -> {
                    throw new RuntimeException("always fails");
                },
                "http://test/api/v1/events");
        stream.baseRetryMs = 60_000; // long sleep — the interrupt must break it

        Thread t = new Thread(stream::runLoop, "test-events-thread");
        t.setDaemon(true);
        t.start();

        Thread.sleep(100); // let it fail once and enter the sleep
        t.interrupt();
        t.join(2000);

        assertFalse(t.isAlive());
    }

    // ----------------------------------------------------------- start/close

    @Test
    void start_viaConnector_connectsAndDispatches() throws Exception {
        CountDownLatch connectedSeen = new CountDownLatch(1);
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "text/event-stream",
                        new BlockingTailStream(CONNECT_FRAMES)),
                "http://test/api/v1/events");
        stream.on("connected", d -> connectedSeen.countDown());

        stream.start();
        stream.ensureConnected(java.time.Duration.ofSeconds(5));

        assertTrue(connectedSeen.await(5, TimeUnit.SECONDS));
        assertEquals("connected", stream.connectionStatus());
        stream.close();
        assertEquals("disconnected", stream.connectionStatus());
    }

    @Test
    void close_unblocksReaderAndJoinsThread() throws Exception {
        EventStream stream = new EventStream(
                uri -> new EventStream.Connection(200, "text/event-stream",
                        new BlockingTailStream(CONNECT_FRAMES)),
                "http://test/api/v1/events");

        stream.start();
        stream.ensureConnected(java.time.Duration.ofSeconds(5));

        assertDoesNotThrow(stream::close);
        assertEquals("disconnected", stream.connectionStatus());
    }

    @Test
    void close_interruptedDuringJoin() throws Exception {
        EventStream stream = new EventStream();
        // A thread that blocks forever stands in for the stream thread
        Thread blockingThread = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                // expected
            }
        }, "test-blocking-events");
        blockingThread.setDaemon(true);
        blockingThread.start();

        var streamThreadField = EventStream.class.getDeclaredField("streamThread");
        streamThreadField.setAccessible(true);
        streamThreadField.set(stream, blockingThread);

        // Interrupt the closing thread before close() so join() throws
        Thread closeThread = new Thread(() -> {
            Thread.currentThread().interrupt();
            stream.close();
        });
        closeThread.start();
        closeThread.join(3000);

        blockingThread.interrupt();
        blockingThread.join(1000);
    }

    @Test
    void ensureConnected_interruptedDuringAwait() throws Exception {
        EventStream stream = new EventStream();
        stream.setConnectionStatus("connecting");
        stream.connectedLatch = new CountDownLatch(1);

        Thread testThread = new Thread(() ->
                stream.ensureConnected(java.time.Duration.ofMillis(5000)));
        testThread.start();

        Thread.sleep(50);
        testThread.interrupt();
        testThread.join(2000);

        assertFalse(testThread.isAlive());
    }

    // -------------------------------------------------------------- helpers

    /** Simple stream whose close() is observable. */
    private static final class TrackingStream extends InputStream {
        final AtomicBoolean closed = new AtomicBoolean(false);
        private final ByteArrayInputStream delegate;

        TrackingStream(String content) {
            this.delegate = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /**
     * Serves the given frames, then blocks until closed — models a healthy
     * SSE connection that stays open after its initial events.
     */
    private static final class BlockingTailStream extends InputStream {
        private final ByteArrayInputStream head;
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private volatile boolean closed = false;

        BlockingTailStream(String frames) {
            this.head = new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() throws IOException {
            int b = head.read();
            if (b != -1) {
                return b;
            }
            try {
                closedLatch.await();
            } catch (InterruptedException e) {
                throw new IOException("interrupted", e);
            }
            return -1;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = head.read(buf, off, len);
            if (n != -1) {
                return n;
            }
            if (closed) {
                return -1;
            }
            try {
                closedLatch.await();
            } catch (InterruptedException e) {
                throw new IOException("interrupted", e);
            }
            return -1;
        }

        @Override
        public void close() {
            closed = true;
            closedLatch.countDown();
        }
    }
}
