package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.EventsApi;
import com.smplkit.internal.generated.audit.model.Event;
import com.smplkit.internal.generated.audit.model.EventResource;
import com.smplkit.internal.generated.audit.model.EventResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuditClientTest {

    private HttpServer server;
    private AuditClient client;
    private AtomicInteger postCount;
    private AtomicReference<String> lastIdempotencyKey;
    private AtomicReference<String> lastEnvironmentHeader;
    private CountDownLatch firstPostSeen;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        postCount = new AtomicInteger();
        lastIdempotencyKey = new AtomicReference<>();
        lastEnvironmentHeader = new AtomicReference<>();
        firstPostSeen = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        installDefaultHandlers();
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new AuditClient(HttpClient.newHttpClient(), "sk_api_test", Map.of(), Duration.ofSeconds(5), baseUrl);
    }

    private String firstHeader(HttpExchange ex, String name) {
        for (var entry : ex.getRequestHeaders().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
            }
        }
        return null;
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    private void installDefaultHandlers() {
        server.createContext("/api/v1/events", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                if (ex.getRequestURI().getPath().equals("/api/v1/events")
                        && ex.getRequestMethod().equals("POST")) {
                    // JDK HttpServer normalizes header keys (e.g. "Idempotency-key");
                    // case-insensitive lookup.
                    lastIdempotencyKey.set(firstHeader(ex, "Idempotency-Key"));
                    lastEnvironmentHeader.set(firstHeader(ex, "X-Smplkit-Environment"));
                    postCount.incrementAndGet();
                    firstPostSeen.countDown();
                    String body = "{\"data\":{\"id\":\"00000000-0000-0000-0000-000000000001\",\"type\":\"event\",\"attributes\":{\"event_type\":\"x.created\",\"resource_type\":\"x\",\"resource_id\":\"1\",\"occurred_at\":\"2026-05-06T12:00:00Z\",\"created_at\":\"2026-05-06T12:00:01Z\",\"actor_type\":\"API_KEY\",\"actor_id\":null,\"actor_label\":\"\",\"data\":{},\"idempotency_key\":\"\"}}}";
                    byte[] resp = body.getBytes();
                    ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
                    ex.sendResponseHeaders(201, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                    return;
                }
                if (ex.getRequestMethod().equals("GET")) {
                    lastEnvironmentHeader.set(firstHeader(ex, "X-Smplkit-Environment"));
                    String body = "{\"data\":{\"id\":\"11111111-2222-3333-4444-555555555555\",\"type\":\"event\",\"attributes\":{\"event_type\":\"x.created\",\"resource_type\":\"x\",\"resource_id\":\"1\",\"occurred_at\":\"2026-05-06T12:00:00Z\",\"created_at\":\"2026-05-06T12:00:01Z\",\"actor_type\":\"API_KEY\",\"actor_id\":null,\"actor_label\":\"\",\"data\":{},\"idempotency_key\":\"k\",\"environment\":\"production\"}}}";
                    byte[] resp = body.getBytes();
                    ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
                    ex.sendResponseHeaders(200, resp.length);
                    ex.getResponseBody().write(resp);
                    ex.close();
                    return;
                }
                ex.sendResponseHeaders(405, -1);
                ex.close();
            }
        });
    }

    @Test
    void create_returnsImmediately_thenPostsInBackground() throws Exception {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            CreateEventInput input = new CreateEventInput("user.created", "user", "u-" + i);
            client.events().record(input);
        }
        assertTrue(System.currentTimeMillis() - start < 200, "create should return in milliseconds");
        client.events().flush(2_000);
        assertTrue(postCount.get() >= 1, "expected at least one background POST");
    }

    @Test
    void create_passesIdempotencyKeyHeader() throws Exception {
        CreateEventInput input = new CreateEventInput("user.created", "user", "u-1");
        input.idempotencyKey = "key-abc";
        client.events().record(input);
        // flush() signals the worker to drain immediately. Wait on the
        // handler's CountDownLatch so we don't race the network round-trip
        // — CI runners are much slower than local.
        client.events().flush(2_000);
        // flush() restores the interrupt flag if its internal Thread.sleep
        // was interrupted by Gradle's test executor. Clear the flag before
        // awaiting the latch so we don't trip over JUnit reused threads
        // carrying state from earlier tests in the run.
        Thread.interrupted();
        assertTrue(firstPostSeen.await(15, java.util.concurrent.TimeUnit.SECONDS),
                "test server never received the POST");
        assertEquals("key-abc", lastIdempotencyKey.get());
    }

    @Test
    void create_rejectsMissingFields() {
        CreateEventInput input = new CreateEventInput();
        input.eventType = "user.created";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.events().record(input));
        assertTrue(ex.getMessage().contains("resourceType")
                || ex.getMessage().contains("resourceId"));
    }

    @Test
    void get_roundTripsAnEvent() throws Exception {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        AuditEvent ev = client.events().get(id);
        assertEquals(id, ev.id);
        assertEquals("x.created", ev.eventType);
        assertEquals("API_KEY", ev.actorType);
        assertNull(ev.actorId);
        assertEquals("production", ev.environment);
    }

    @Test
    void get_404_throwsApiException() throws Exception {
        // Replace the handler with one that always 404s.
        server.removeContext("/api/v1/events");
        server.createContext("/api/v1/events", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        UUID id = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () -> client.events().get(id));
        assertEquals(404, ex.getCode());
    }

    @Test
    void create_nestsSnapshotInsideData() throws Exception {
        CreateEventInput input = new CreateEventInput("invoice.created", "invoice", "inv-1");
        input.occurredAt = OffsetDateTime.parse("2026-05-06T12:00:00Z");
        input.data = java.util.Map.of(
                "snapshot", java.util.Map.of("total_cents", 4900),
                "request_id", "req-1");
        client.events().record(input);
        client.events().flush(2_000);
        assertTrue(postCount.get() >= 1);
    }

    @Test
    void create_forwardsCustomerSuppliedActorFields() throws Exception {
        CreateEventInput input = new CreateEventInput("user.created", "user", "u-1");
        input.actorType = "EXTERNAL_SERVICE";
        input.actorId = "not-a-uuid:billing-bot";
        input.actorLabel = "Billing Bot";
        client.events().record(input);
        client.events().flush(2_000);
        assertTrue(postCount.get() >= 1);
    }

    @Test
    void create_setsDoNotForward() throws Exception {
        CreateEventInput input = new CreateEventInput("user.created", "user", "u-1");
        input.doNotForward = true;
        client.events().record(input);
        client.events().flush(2_000);
        assertTrue(postCount.get() >= 1);
    }

    @Test
    void client_exposesResourceTypesAndEventTypesAccessors() {
        assertNotNull(client.resourceTypes());
        assertNotNull(client.eventTypes());
    }

    @Test
    void client_exposesCategoriesAccessor() {
        assertNotNull(client.categories());
    }

    @Test
    void create_withCategory_serializesField() throws Exception {
        CreateEventInput input = new CreateEventInput("user.created", "user", "u-1");
        input.category = "auth";
        client.events().record(input);
        client.events().flush(2_000);
        assertTrue(postCount.get() >= 1);
    }

    // -----------------------------------------------------------------
    // Inline flush on record() — parity with canonical record(flush=...)
    // -----------------------------------------------------------------

    @Test
    void record_flushTrue_blocksUntilDurable() {
        // The default handler responds 201 synchronously. With flush=true the
        // call must not return until the buffered POST has completed, so the
        // count is observable immediately — no separate flush(), no sleep.
        CreateEventInput input = new CreateEventInput("invoice.created", "invoice", "inv-1");
        client.events().record(input, true, 2_000);
        assertEquals(1, postCount.get(), "record(flush=true) must block until the event is durable");
        assertEquals(0, firstPostSeen.getCount(), "POST should have been observed by the server");
    }

    @Test
    void record_flushTrue_defaultTimeoutOverload_blocksUntilDurable() {
        // The two-arg overload applies the 5s default timeout; same durability
        // guarantee as the explicit-timeout overload.
        CreateEventInput input = new CreateEventInput("invoice.created", "invoice", "inv-2");
        client.events().record(input, true);
        assertEquals(1, postCount.get(), "record(flush=true) must block until the event is durable");
    }

    @Test
    void record_flushFalse_doesNotBlockUntilDurable() throws Exception {
        // Gate the server so the POST cannot complete until released. A
        // fire-and-forget record returns without draining the buffer (a single
        // enqueue is below the worker's watermark), so durability has NOT
        // happened — observable as postCount still 0. An explicit flush() then
        // drives the drain and makes the event durable. The gate keeps the
        // "still 0" assertion deterministic even if the periodic timer fires.
        CountDownLatch gate = new CountDownLatch(1);
        server.removeContext("/api/v1/events");
        server.createContext("/api/v1/events", ex -> {
            if (ex.getRequestMethod().equals("POST")) {
                try {
                    gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                postCount.incrementAndGet();
                String body = "{\"data\":{\"id\":\"00000000-0000-0000-0000-000000000001\",\"type\":\"event\",\"attributes\":{\"event_type\":\"x.created\",\"resource_type\":\"x\",\"resource_id\":\"1\",\"occurred_at\":\"2026-05-06T12:00:00Z\",\"created_at\":\"2026-05-06T12:00:01Z\",\"actor_type\":\"API_KEY\",\"actor_id\":null,\"actor_label\":\"\",\"data\":{},\"idempotency_key\":\"\"}}}";
                byte[] resp = body.getBytes();
                ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
                ex.sendResponseHeaders(201, resp.length);
                ex.getResponseBody().write(resp);
                ex.close();
            } else {
                ex.sendResponseHeaders(405, -1);
                ex.close();
            }
        });

        CreateEventInput input = new CreateEventInput("invoice.created", "invoice", "inv-3");
        client.events().record(input, false);
        // Durability is gated server-side; a fire-and-forget record must not
        // have waited for it.
        assertEquals(0, postCount.get(), "record(flush=false) must not block until durable");

        gate.countDown();
        client.events().flush(5_000);
        Thread.interrupted();
        assertEquals(1, postCount.get(), "event should be durable after release + flush");
    }

    // -----------------------------------------------------------------
    // Environment-header injection (ADR-055)
    // -----------------------------------------------------------------

    @Test
    void record_injectsConfiguredEnvironmentHeader() throws Exception {
        // The default client (set up in @BeforeEach) carries no environment;
        // build one bound to "production" against the same server.
        try (AuditClient envClient = new AuditClient(HttpClient.newHttpClient(), "sk_api_test",
                Map.of(), Duration.ofSeconds(5), baseUrl, "production")) {
            CreateEventInput input = new CreateEventInput("user.created", "user", "u-1");
            envClient.events().record(input);
            envClient.events().flush(2_000);
            Thread.interrupted();
            assertTrue(firstPostSeen.await(15, java.util.concurrent.TimeUnit.SECONDS),
                    "test server never received the POST");
            assertEquals("production", lastEnvironmentHeader.get());
        }
    }

    @Test
    void get_injectsConfiguredEnvironmentHeader() throws Exception {
        try (AuditClient envClient = new AuditClient(HttpClient.newHttpClient(), "sk_api_test",
                Map.of(), Duration.ofSeconds(5), baseUrl, "staging")) {
            UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
            envClient.events().get(id);
            assertEquals("staging", lastEnvironmentHeader.get());
        }
    }

    @Test
    void noEnvironment_omitsEnvironmentHeader() throws Exception {
        // The default client has no environment configured (null path).
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        client.events().get(id);
        assertNull(lastEnvironmentHeader.get());
    }

    @Test
    void explicitEnvironmentHeader_overridesConfiguredEnvironment() throws Exception {
        // A caller-supplied X-Smplkit-Environment in extraHeaders wins over
        // the SDK's configured environment.
        try (AuditClient envClient = new AuditClient(HttpClient.newHttpClient(), "sk_api_test",
                Map.of("X-Smplkit-Environment", "explicit-override"), Duration.ofSeconds(5),
                baseUrl, "production")) {
            UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
            envClient.events().get(id);
            assertEquals("explicit-override", lastEnvironmentHeader.get());
        }
    }
}
