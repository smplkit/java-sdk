package com.smplkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@link EventStream} against a real local HTTP server
 * speaking SSE (JDK built-in {@code com.sun.net.httpserver}). These exercise
 * the production connector — request headers, streaming body handling, the
 * reconnect loop, backoff reset, refetch-on-reconnect, and the liveness
 * watchdog — over real sockets on localhost.
 */
class EventStreamSseServerTest {

    private static final String CONNECTED_FRAME = "event: connected\ndata: {}\n\n";

    private HttpServer server;
    private ExecutorService serverExecutor;
    private EventStream stream;
    private final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sse-test-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverExecutor);
    }

    @AfterEach
    void tearDown() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
        // Release any handler still holding a connection open, then stop.
        for (CountDownLatch hold : holds) {
            hold.countDown();
        }
        holds.clear();
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void sseHeaders(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
    }

    private static void write(HttpExchange exchange, String frames) throws IOException {
        OutputStream out = exchange.getResponseBody();
        out.write(frames.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Registers a hold latch the teardown always releases. */
    private CountDownLatch newHold() {
        CountDownLatch hold = new CountDownLatch(1);
        holds.add(hold);
        return hold;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // REQUIRED: backoff resets to base after a successful connect
    // -----------------------------------------------------------------------

    @Test
    void backoff_resetsToBase_afterSuccessfulConnect() throws Exception {
        // Wire sequence: success (seeds retry: 40) → 503 → 503 → success →
        // hold. The observed delay sequence must be [40, 80, 160, 40]: the
        // two failures double the delay, the reconnect success resets it.
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch lastHold = newHold();
        server.createContext("/api/v1/events", exchange -> {
            int n = connections.incrementAndGet();
            if (n == 2 || n == 3) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            sseHeaders(exchange);
            if (n == 1) {
                write(exchange, "retry: 40\n\n" + CONNECTED_FRAME);
            } else {
                write(exchange, CONNECTED_FRAME);
            }
            if (n >= 5) {
                awaitQuietly(lastHold); // keep the final connection open
            }
            // n == 1 and n == 4 close immediately: each drop after a
            // successful connect forces the next delay to be computed.
            exchange.close();
        });
        server.start();

        List<Long> delays = new CopyOnWriteArrayList<>();
        CountDownLatch fourDelays = new CountDownLatch(4);
        stream = new EventStream(HttpClient.newHttpClient(), baseUrl(), "sk_test_events");
        stream.delayListenerForTests = d -> {
            delays.add(d);
            fourDelays.countDown();
        };

        stream.start();

        assertTrue(fourDelays.await(10, TimeUnit.SECONDS), "four reconnect delays within 10s");
        assertEquals(List.of(40L, 80L, 160L, 40L), delays.subList(0, 4),
                "delay must return to the server-seeded base after a successful connect");
    }

    // -----------------------------------------------------------------------
    // REQUIRED: reconnect triggers a full refetch; initial connect does not
    // -----------------------------------------------------------------------

    @Test
    void reconnect_triggersRefetch_initialConnectDoesNot() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch firstConnectionUp = new CountDownLatch(1);
        CountDownLatch dropFirst = newHold();
        CountDownLatch secondHold = newHold();
        server.createContext("/api/v1/events", exchange -> {
            int n = connections.incrementAndGet();
            sseHeaders(exchange);
            write(exchange, "retry: 20\n\n" + CONNECTED_FRAME);
            if (n == 1) {
                firstConnectionUp.countDown();
                awaitQuietly(dropFirst); // server-side drop on command
            } else {
                awaitQuietly(secondHold);
            }
            exchange.close();
        });
        server.start();

        AtomicInteger refetches = new AtomicInteger();
        CountDownLatch refetchRan = new CountDownLatch(1);
        stream = new EventStream(HttpClient.newHttpClient(), baseUrl(), "sk_test_events");
        stream.onReconnect(() -> {
            refetches.incrementAndGet();
            refetchRan.countDown();
        });

        stream.start();
        stream.ensureConnected(Duration.ofSeconds(5));

        assertTrue(firstConnectionUp.await(5, TimeUnit.SECONDS));
        assertEquals("connected", stream.connectionStatus());
        assertEquals(0, refetches.get(), "refetch must NOT run on the initial connect");

        dropFirst.countDown(); // server drops the stream

        assertTrue(refetchRan.await(10, TimeUnit.SECONDS), "refetch must run on reconnect");
        assertEquals(1, refetches.get());
    }

    // -----------------------------------------------------------------------
    // Liveness: silent stream is force-reconnected after the read timeout
    // -----------------------------------------------------------------------

    @Test
    void livenessWatchdog_reconnectsAfterSilence() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch secondConnection = new CountDownLatch(1);
        CountDownLatch hold = newHold();
        server.createContext("/api/v1/events", exchange -> {
            int n = connections.incrementAndGet();
            sseHeaders(exchange);
            write(exchange, "retry: 20\n\n" + CONNECTED_FRAME);
            if (n >= 2) {
                secondConnection.countDown();
            }
            awaitQuietly(hold); // go silent: no keepalives, no events
            exchange.close();
        });
        server.start();

        stream = new EventStream(HttpClient.newHttpClient(), baseUrl(), "sk_test_events");
        stream.readTimeoutMs = 300; // test seam; production default is 45s

        stream.start();
        stream.ensureConnected(Duration.ofSeconds(5));

        assertTrue(secondConnection.await(10, TimeUnit.SECONDS),
                "watchdog must drop the silent stream and reconnect");
    }

    @Test
    void keepaliveComments_countAsLiveness() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch hold = newHold();
        server.createContext("/api/v1/events", exchange -> {
            connections.incrementAndGet();
            sseHeaders(exchange);
            write(exchange, CONNECTED_FRAME);
            // Comment keepalives every 100ms — well inside the 400ms timeout
            try {
                for (int i = 0; i < 12 && hold.getCount() > 0; i++) {
                    Thread.sleep(100);
                    write(exchange, ": keepalive\n");
                }
            } catch (InterruptedException | IOException e) {
                // client gone or test over
            }
            awaitQuietly(hold);
            exchange.close();
        });
        server.start();

        stream = new EventStream(HttpClient.newHttpClient(), baseUrl(), "sk_test_events");
        stream.readTimeoutMs = 400;

        stream.start();
        stream.ensureConnected(Duration.ofSeconds(5));

        // Across 1.2s of comment-only traffic the watchdog must never fire
        Thread.sleep(1200);
        assertEquals(1, connections.get(),
                "comment frames alone must keep the connection alive");
        assertEquals("connected", stream.connectionStatus());
    }

    // -----------------------------------------------------------------------
    // Wire details: headers, event payloads, auth failure
    // -----------------------------------------------------------------------

    @Test
    void request_carriesAcceptAuthorizationAndUserAgent() throws Exception {
        ConcurrentHashMap<String, String> seen = new ConcurrentHashMap<>();
        CountDownLatch requestSeen = new CountDownLatch(1);
        CountDownLatch hold = newHold();
        server.createContext("/api/v1/events", exchange -> {
            seen.put("accept", String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
            seen.put("authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            seen.put("user-agent", String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
            seen.put("last-event-id", String.valueOf(exchange.getRequestHeaders().getFirst("Last-Event-ID")));
            requestSeen.countDown();
            sseHeaders(exchange);
            write(exchange, CONNECTED_FRAME);
            awaitQuietly(hold);
            exchange.close();
        });
        server.start();

        stream = new EventStream(HttpClient.newHttpClient(), baseUrl(), "sk_api_test123");
        stream.start();
        stream.ensureConnected(Duration.ofSeconds(5));

        assertTrue(requestSeen.await(5, TimeUnit.SECONDS));
        assertEquals("text/event-stream", seen.get("accept"));
        assertEquals("Bearer sk_api_test123", seen.get("authorization"));
        assertTrue(seen.get("user-agent").startsWith("smplkit-sdk-java/"),
                "events request must carry the SDK User-Agent, got: " + seen.get("user-agent"));
        assertEquals("null", seen.get("last-event-id"), "Last-Event-ID must never be sent");
    }

    @Test
    void events_areDispatchedToListeners_overTheWire() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        ConcurrentHashMap<String, Object> payload = new ConcurrentHashMap<>();
        CountDownLatch hold = newHold();
        server.createContext("/api/v1/events", exchange -> {
            sseHeaders(exchange);
            write(exchange, "retry: 1000\n\n" + CONNECTED_FRAME
                    + "event: flag_changed\ndata: {\"id\": \"checkout-v2\"}\n\n"
                    + ": keepalive\n");
            awaitQuietly(hold);
            exchange.close();
        });
        server.start();

        stream = new EventStream(HttpClient.newHttpClient(), baseUrl(), "sk_test_events");
        stream.on("flag_changed", data -> {
            payload.putAll(data);
            received.countDown();
        });

        stream.start();
        stream.ensureConnected(Duration.ofSeconds(5));

        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertEquals("checkout-v2", payload.get("id"));
        assertEquals(1000L, stream.baseRetryMs);
    }

    @Test
    void authFailure401_isRetriedWithBackoff_neverConnects() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch threeAttempts = new CountDownLatch(3);
        server.createContext("/api/v1/events", exchange -> {
            connections.incrementAndGet();
            threeAttempts.countDown();
            byte[] body = "{\"error\": \"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        stream = new EventStream(HttpClient.newHttpClient(), baseUrl(), "sk_bad_key");
        stream.baseRetryMs = 20; // keep retries fast for the test

        stream.start();

        assertTrue(threeAttempts.await(10, TimeUnit.SECONDS),
                "401 responses must be retried with backoff");
        assertNotEquals("connected", stream.connectionStatus());
    }
}
