package com.smplkit.audit;

import com.smplkit.errors.ConnectionError;
import com.smplkit.errors.NotFoundError;
import com.smplkit.errors.ValidationError;
import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.api.EventsApi;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stateless (unbuffered) audit recording — {@code buffered(false)}.
 *
 * <p>Every {@code record} performs one blocking POST on the calling thread:
 * assertions on the server-side counter run immediately after the call with
 * no latch or sleep, proving there is no background buffer in the path.
 * Failures surface as the SDK's typed exceptions, and the buffer lifecycle
 * methods ({@code flush} / {@code close}) are no-ops.</p>
 */
class AuditStatelessTest {

    private HttpServer server;
    private EventsApi api;
    private AtomicInteger postCount;
    private AtomicReference<String> lastPostBody;
    private AtomicReference<String> lastIdempotencyKey;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        postCount = new AtomicInteger();
        lastPostBody = new AtomicReference<>();
        lastIdempotencyKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        var apiClient = new ApiClient();
        apiClient.updateBaseUri(baseUrl);
        apiClient.setReadTimeout(Duration.ofSeconds(2));
        api = new EventsApi(apiClient);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private void install201Handler() {
        server.createContext("/api/v1/events", ex -> {
            for (var entry : ex.getRequestHeaders().entrySet()) {
                if (entry.getKey().equalsIgnoreCase("Idempotency-Key") && !entry.getValue().isEmpty()) {
                    lastIdempotencyKey.set(entry.getValue().get(0));
                }
            }
            lastPostBody.set(new String(ex.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            postCount.incrementAndGet();
            byte[] resp = ("{\"data\":{\"id\":\"00000000-0000-0000-0000-000000000001\"," +
                    "\"type\":\"event\",\"attributes\":{\"event_type\":\"x.created\"," +
                    "\"resource_type\":\"x\",\"resource_id\":\"1\"," +
                    "\"occurred_at\":\"2026-05-06T12:00:00Z\"," +
                    "\"created_at\":\"2026-05-06T12:00:01Z\"," +
                    "\"actor_type\":\"API_KEY\",\"actor_id\":null,\"actor_label\":\"\"," +
                    "\"data\":{},\"idempotency_key\":\"\"}}}").getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            ex.sendResponseHeaders(201, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
    }

    private void installErrorHandler(int status, String body) {
        server.createContext("/api/v1/events", ex -> {
            byte[] resp = body.getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            ex.sendResponseHeaders(status, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
    }

    // ------------------------------------------------------------- AuditEvents

    @Test
    void record_unbuffered_postsSynchronouslyWithEnvironmentAndIdempotencyKey() {
        install201Handler();
        AuditEvents events = new AuditEvents(api, "production", false);
        assertFalse(events.isBuffered());

        var input = new CreateEventInput("invoice.created", "invoice", "inv-1");
        input.idempotencyKey = "idem-1";
        events.record(input);

        // No buffer: the POST happened on the calling thread, so the counter
        // is already 1 with no wait.
        assertEquals(1, postCount.get());
        assertTrue(lastPostBody.get().contains("\"environment\":\"production\""));
        assertEquals("idem-1", lastIdempotencyKey.get());
        events.close(); // no-op — must not throw
    }

    @Test
    void record_unbuffered_flushArgumentIsIgnored() {
        install201Handler();
        AuditEvents events = new AuditEvents(api, null, false);

        events.record(new CreateEventInput("a.b", "a", "1"), true);
        events.record(new CreateEventInput("a.b", "a", "2"), true, 12_345L);

        assertEquals(2, postCount.get());
    }

    @Test
    void record_unbuffered_mapsHttpStatusToTypedException() {
        installErrorHandler(404, "{\"errors\":[{\"status\":\"404\",\"detail\":\"nope\"}]}");
        AuditEvents events = new AuditEvents(api, null, false);

        NotFoundError ex = assertThrows(NotFoundError.class,
                () -> events.record(new CreateEventInput("a.b", "a", "1")));
        assertTrue(ex.getMessage().contains("nope"));
    }

    @Test
    void record_unbuffered_maps422ToValidationError() {
        installErrorHandler(422, "{\"errors\":[{\"status\":\"422\",\"detail\":\"bad payload\"}]}");
        AuditEvents events = new AuditEvents(api, null, false);

        assertThrows(ValidationError.class,
                () -> events.record(new CreateEventInput("a.b", "a", "1")));
    }

    @Test
    void record_unbuffered_mapsConnectFailureToConnectionError() {
        // Refused port -> generated ApiException with code 0 -> ConnectionError.
        var apiClient = new ApiClient();
        apiClient.updateBaseUri("http://127.0.0.1:1");
        apiClient.setReadTimeout(Duration.ofSeconds(1));
        AuditEvents events = new AuditEvents(new EventsApi(apiClient), null, false);

        assertThrows(ConnectionError.class,
                () -> events.record(new CreateEventInput("a.b", "a", "1")));
    }

    @Test
    void record_unbuffered_stillValidatesRequiredFields() {
        install201Handler();
        AuditEvents events = new AuditEvents(api, null, false);

        assertThrows(IllegalArgumentException.class,
                () -> events.record(new CreateEventInput("", "a", "1")));
        assertEquals(0, postCount.get());
    }

    @Test
    void flush_unbuffered_isNoOp() {
        install201Handler();
        AuditEvents events = new AuditEvents(api, null, false);
        assertDoesNotThrow(() -> events.flush(1_000));
        assertEquals(0, postCount.get());
    }

    @Test
    void bufferedDefaultConstructor_reportsBuffered() {
        AuditEvents events = new AuditEvents(api, "staging");
        assertTrue(events.isBuffered());
        events.close();
    }

    // ------------------------------------------------------------- AuditClient

    @Test
    void auditClient_unbuffered_recordsSynchronouslyAndCloseIsNoOp() {
        install201Handler();
        AuditClient client = new AuditClient(HttpClient.newHttpClient(), "sk_test",
                Map.of(), Duration.ofSeconds(2), baseUrl, "staging", false);

        client.events().record(new CreateEventInput("user.created", "user", "u-1"));
        assertEquals(1, postCount.get());
        assertTrue(lastPostBody.get().contains("\"environment\":\"staging\""));

        // flush + close are buffer no-ops for the unbuffered client.
        assertDoesNotThrow(() -> client.events().flush(1_000));
        assertDoesNotThrow(client::close);
        assertDoesNotThrow(client::close);
    }

    @Test
    void auditClient_unbuffered_recordThrowsTypedExceptionOnFailure() {
        installErrorHandler(404, "{\"errors\":[{\"status\":\"404\",\"detail\":\"missing\"}]}");
        AuditClient client = new AuditClient(HttpClient.newHttpClient(), "sk_test",
                Map.of(), Duration.ofSeconds(2), baseUrl, null, false);

        assertThrows(NotFoundError.class,
                () -> client.events().record(new CreateEventInput("a.b", "a", "1"), true));
        client.close();
    }

    // -------------------------------------------------------- AsyncAuditClient

    @Test
    void asyncClient_unbuffered_recordRunsOnExecutorAndCompletes() {
        install201Handler();
        AuditClient sync = new AuditClient(HttpClient.newHttpClient(), "sk_test",
                Map.of(), Duration.ofSeconds(2), baseUrl, "production", false);
        try (AsyncAuditClient async = AsyncAuditClient.wrap(sync)) {
            // flush=true is meaningless in unbuffered mode; the future completes
            // once the single POST is durable.
            async.events().record(new CreateEventInput("job.done", "job", "j-1"), true).join();
            assertEquals(1, postCount.get());

            async.events().record(new CreateEventInput("job.done", "job", "j-2"), false, 10L).join();
            assertEquals(2, postCount.get());
        }
    }

    @Test
    void asyncClient_unbuffered_recordCompletesExceptionallyWithTypedCause() {
        installErrorHandler(422, "{\"errors\":[{\"status\":\"422\",\"detail\":\"invalid\"}]}");
        AuditClient sync = new AuditClient(HttpClient.newHttpClient(), "sk_test",
                Map.of(), Duration.ofSeconds(2), baseUrl, null, false);
        try (AsyncAuditClient async = AsyncAuditClient.wrap(sync)) {
            CompletionException ex = assertThrows(CompletionException.class,
                    () -> async.events().record(new CreateEventInput("a.b", "a", "1"), true).join());
            assertInstanceOf(ValidationError.class, ex.getCause());
        }
    }

    @Test
    void asyncClient_unbuffered_voidRecordDelegatesBlockingly() {
        install201Handler();
        AuditClient sync = new AuditClient(HttpClient.newHttpClient(), "sk_test",
                Map.of(), Duration.ofSeconds(2), baseUrl, null, false);
        try (AsyncAuditClient async = AsyncAuditClient.wrap(sync)) {
            async.events().record(new CreateEventInput("a.b", "a", "1"));
            assertEquals(1, postCount.get());
        }
    }
}
