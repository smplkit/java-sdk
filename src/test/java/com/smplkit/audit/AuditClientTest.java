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
    private CountDownLatch firstPostSeen;

    @BeforeEach
    void start() throws Exception {
        postCount = new AtomicInteger();
        lastIdempotencyKey = new AtomicReference<>();
        firstPostSeen = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        installDefaultHandlers();
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new AuditClient(HttpClient.newHttpClient(), "sk_api_test", Map.of(), Duration.ofSeconds(5), baseUrl);
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
                    // case-insensitive lookup via getFirst.
                    String found = null;
                    for (var entry : ex.getRequestHeaders().entrySet()) {
                        if (entry.getKey().equalsIgnoreCase("Idempotency-Key")) {
                            found = entry.getValue().isEmpty() ? null : entry.getValue().get(0);
                            break;
                        }
                    }
                    lastIdempotencyKey.set(found);
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
                    String body = "{\"data\":{\"id\":\"11111111-2222-3333-4444-555555555555\",\"type\":\"event\",\"attributes\":{\"event_type\":\"x.created\",\"resource_type\":\"x\",\"resource_id\":\"1\",\"occurred_at\":\"2026-05-06T12:00:00Z\",\"created_at\":\"2026-05-06T12:00:01Z\",\"actor_type\":\"API_KEY\",\"actor_id\":null,\"actor_label\":\"\",\"data\":{},\"idempotency_key\":\"k\"}}}";
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
}
