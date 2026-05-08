package com.smplkit.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-body shape tests for the audit wrapper.
 *
 * <p>Asserts on the actual JSON the SDK posts. Guards against the
 * failure mode that shipped smplkit-sdk@3.2.21 / @smplkit/sdk@3.0.19:
 * the generated client compiled cleanly after the spec dropped a
 * field, but the wrapper kept emitting it, and CI was none the wiser
 * because no test inspected the bytes.</p>
 *
 * <p>The whitelists below come from the audit service's OpenAPI spec
 * (openapi/audit.json: components.schemas.Event / .Forwarder), not
 * from the generated client.</p>
 */
class AuditWireShapeTest {

    private static final UUID FWD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** POST /api/v1/events accepts only these attribute keys. The rest
     *  (created_at, actor_*, idempotency_key) are readOnly. */
    private static final Set<String> EVENT_POST_ATTRS = Set.of(
            "action", "resource_type", "resource_id",
            "occurred_at", "data", "do_not_forward");

    /** POST/PUT /api/v1/forwarders accepts only these attribute keys.
     *  slug is x-immutable; created_at/updated_at/deleted_at/version
     *  are readOnly. */
    private static final Set<String> FORWARDER_POST_ATTRS = Set.of(
            "name", "forwarder_type", "http",
            "enabled", "filter", "transform", "data");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private AuditClient client;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private final AtomicReference<String> capturedMethod = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedIdempotencyKey = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> latch = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        latch.set(new CountDownLatch(1));
        server.createContext("/api/v1/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            capturedMethod.set(ex.getRequestMethod());
            capturedBody.set(new String(body));
            for (var entry : ex.getRequestHeaders().entrySet()) {
                if (entry.getKey().equalsIgnoreCase("Idempotency-Key")) {
                    capturedIdempotencyKey.set(
                            entry.getValue().isEmpty() ? null : entry.getValue().get(0));
                }
            }
            HttpHandler h = handler.get();
            if (h != null) {
                h.handle(ex);
            } else {
                ex.sendResponseHeaders(500, -1);
                ex.close();
            }
            latch.get().countDown();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new AuditClient(HttpClient.newHttpClient(), "sk_api_test",
                Map.of(), Duration.ofSeconds(5), baseUrl);
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    private void respondJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] resp = body.getBytes();
        ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, resp.length);
            ex.getResponseBody().write(resp);
        }
        ex.close();
    }

    private static String eventResponseJson() {
        return "{\"data\":{\"id\":\"00000000-0000-0000-0000-000000000001\","
                + "\"type\":\"event\",\"attributes\":{\"action\":\"invoice.created\","
                + "\"resource_type\":\"invoice\",\"resource_id\":\"inv-1\","
                + "\"occurred_at\":\"2026-05-06T12:00:00Z\","
                + "\"created_at\":\"2026-05-06T12:00:01Z\","
                + "\"actor_type\":\"API_KEY\",\"actor_label\":\"\","
                + "\"data\":{},\"idempotency_key\":\"k-1\"}}}";
    }

    private static String forwarderResponseJson(String name) {
        return "{\"data\":{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\","
                + "\"attributes\":{\"name\":\"" + name + "\",\"slug\":\"x\","
                + "\"forwarder_type\":\"datadog\",\"enabled\":true,"
                + "\"http\":{\"method\":\"POST\",\"url\":\"https://siem.example.com/in\","
                + "\"headers\":[{\"name\":\"DD-API-KEY\",\"value\":\"<redacted>\"}],"
                + "\"success_status\":\"2xx\"},"
                + "\"data\":{},\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAttributes() throws Exception {
        Map<String, Object> root = MAPPER.readValue(
                capturedBody.get(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        return (Map<String, Object>) data.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRoot() throws Exception {
        return MAPPER.readValue(
                capturedBody.get(), new TypeReference<Map<String, Object>>() {});
    }

    // -----------------------------------------------------------------
    // events.record — wire body
    // -----------------------------------------------------------------

    @Test
    void eventsRecord_wireShape_allParameters() throws Exception {
        handler.set(ex -> respondJson(ex, 201, eventResponseJson()));
        CreateEventInput in = new CreateEventInput("invoice.created", "invoice", "inv-1");
        in.occurredAt = OffsetDateTime.of(2026, 5, 6, 12, 0, 0, 0, ZoneOffset.UTC);
        in.data = Map.of(
                "snapshot", Map.of("total_cents", 4900),
                "req_id", "abc");
        in.idempotencyKey = "k-1";
        in.doNotForward = true;
        client.events().record(in);
        client.events().flush(2_000);
        assertTrue(latch.get().await(2, TimeUnit.SECONDS));

        Map<String, Object> root = parseRoot();
        assertEquals(Set.of("data"), root.keySet(), "envelope must only contain 'data'");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        assertEquals("event", data.get("type"));
        // ID is a placeholder on POST -- server assigns. Java wrapper sends "".
        assertEquals("", data.get("id"));

        Map<String, Object> attrs = parseAttributes();
        assertEquals("invoice.created", attrs.get("action"));
        assertEquals("invoice", attrs.get("resource_type"));
        assertEquals("inv-1", attrs.get("resource_id"));
        assertNotNull(attrs.get("occurred_at"));
        assertTrue(attrs.get("occurred_at").toString().startsWith("2026-05-06T12:00:00"),
                "occurred_at must round-trip; got " + attrs.get("occurred_at"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dataField = (Map<String, Object>) attrs.get("data");
        assertNotNull(dataField);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) dataField.get("snapshot");
        assertEquals(4900, ((Number) snapshot.get("total_cents")).intValue());
        assertEquals("abc", dataField.get("req_id"));
        assertEquals(true, attrs.get("do_not_forward"));

        // Idempotency-Key is a HEADER, not a body attribute.
        assertFalse(attrs.containsKey("idempotency_key"),
                "idempotency_key must NOT appear in body");
        assertEquals("k-1", capturedIdempotencyKey.get());
    }

    @Test
    void eventsRecord_wireShape_minimalCallStaysWithinWhitelist() throws Exception {
        // Java's generated Event model ships Jackson defaults for data
        // ({}) and do_not_forward (false), so a minimal call serializes
        // five keys instead of three. That's an inefficiency, not a bug
        // — the documented server schema accepts both, and the values
        // match the server's own defaults. What MUST hold is that no
        // undocumented field appears even on a minimal call.
        handler.set(ex -> respondJson(ex, 201, eventResponseJson()));
        CreateEventInput in = new CreateEventInput("invoice.created", "invoice", "inv-1");
        client.events().record(in);
        client.events().flush(2_000);
        assertTrue(latch.get().await(2, TimeUnit.SECONDS));

        Map<String, Object> attrs = parseAttributes();
        assertTrue(attrs.containsKey("action"));
        assertTrue(attrs.containsKey("resource_type"));
        assertTrue(attrs.containsKey("resource_id"));
        Set<String> unexpected = new HashSet<>(attrs.keySet());
        unexpected.removeAll(EVENT_POST_ATTRS);
        assertTrue(unexpected.isEmpty(),
                "minimal call must stay within documented whitelist; extras: " + unexpected);
    }

    @Test
    void eventsRecord_wireShape_doNotForwardSerializesCorrectly() throws Exception {
        // The Java generated model emits do_not_forward=false even when
        // the wrapper doesn't explicitly set it. That's wire-equivalent
        // to the server's own default, so the test guards behavior, not
        // optimization: when the caller passes false, the wire value is
        // false (not flipped, not coerced to a string).
        handler.set(ex -> respondJson(ex, 201, eventResponseJson()));
        CreateEventInput in = new CreateEventInput("x", "y", "z");
        in.doNotForward = false;
        client.events().record(in);
        client.events().flush(2_000);
        assertTrue(latch.get().await(2, TimeUnit.SECONDS));

        Map<String, Object> attrs = parseAttributes();
        if (attrs.containsKey("do_not_forward")) {
            assertEquals(false, attrs.get("do_not_forward"),
                    "do_not_forward must serialize as boolean false when caller passes false");
        }
    }

    @Test
    void eventsRecord_wireShape_noTopLevelSnapshot() throws Exception {
        // Regression guard for the smplkit-sdk@3.2.21 incident.
        handler.set(ex -> respondJson(ex, 201, eventResponseJson()));
        CreateEventInput in = new CreateEventInput("invoice.created", "invoice", "inv-1");
        in.data = Map.of("snapshot", Map.of("total_cents", 4900));
        client.events().record(in);
        client.events().flush(2_000);
        assertTrue(latch.get().await(2, TimeUnit.SECONDS));

        Map<String, Object> attrs = parseAttributes();
        assertFalse(attrs.containsKey("snapshot"),
                "top-level snapshot must not appear on the wire");
        @SuppressWarnings("unchecked")
        Map<String, Object> dataField = (Map<String, Object>) attrs.get("data");
        assertNotNull(dataField);
        assertTrue(dataField.containsKey("snapshot"),
                "data.snapshot must round-trip");
    }

    @Test
    void eventsRecord_wireShape_noExtraKeys() throws Exception {
        handler.set(ex -> respondJson(ex, 201, eventResponseJson()));
        CreateEventInput in = new CreateEventInput("invoice.created", "invoice", "inv-1");
        in.occurredAt = OffsetDateTime.of(2026, 5, 6, 12, 0, 0, 0, ZoneOffset.UTC);
        in.data = Map.of("k", "v");
        in.idempotencyKey = "k-1";
        in.doNotForward = true;
        client.events().record(in);
        client.events().flush(2_000);
        assertTrue(latch.get().await(2, TimeUnit.SECONDS));

        Map<String, Object> attrs = parseAttributes();
        Set<String> unexpected = new HashSet<>(attrs.keySet());
        unexpected.removeAll(EVENT_POST_ATTRS);
        assertTrue(unexpected.isEmpty(),
                "wire body has undocumented fields: " + unexpected);
    }

    // -----------------------------------------------------------------
    // forwarders.create — wire body
    // -----------------------------------------------------------------

    @Test
    void forwardersCreate_wireShape_allParameters() throws Exception {
        handler.set(ex -> respondJson(ex, 201, forwarderResponseJson("Datadog production")));
        CreateForwarderInput in = new CreateForwarderInput(
                "Datadog production", "datadog",
                new ForwarderHttp("https://siem.example.com/in"));
        in.http.method = "POST";
        in.http.successStatus = "2xx";
        in.http.headers.add(new HttpHeader("DD-API-KEY", "real-secret"));
        in.enabled = false;
        in.filter = Map.of("==", List.of(Map.of("var", "action"), "user.created"));
        in.transform = "$";
        in.data = Map.of("team", "platform");
        client.forwarders().create(in);

        assertEquals("POST", capturedMethod.get());
        Map<String, Object> root = parseRoot();
        assertEquals(Set.of("data"), root.keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        assertEquals("forwarder", data.get("type"));
        // POST: server assigns id; wrapper sends "".
        assertEquals("", data.get("id"));

        Map<String, Object> attrs = parseAttributes();
        assertEquals("Datadog production", attrs.get("name"));
        assertEquals("datadog", attrs.get("forwarder_type"));
        assertEquals(false, attrs.get("enabled"));
        assertEquals("$", attrs.get("transform"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dataAttr = (Map<String, Object>) attrs.get("data");
        assertEquals("platform", dataAttr.get("team"));

        @SuppressWarnings("unchecked")
        Map<String, Object> http = (Map<String, Object>) attrs.get("http");
        assertEquals("https://siem.example.com/in", http.get("url"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headers = (List<Map<String, Object>>) http.get("headers");
        assertEquals(1, headers.size());
        assertEquals("DD-API-KEY", headers.get(0).get("name"));
        assertEquals("real-secret", headers.get(0).get("value"));

        // Read-only / immutable fields MUST NOT appear on the wire.
        for (String ro : List.of("slug", "created_at", "updated_at", "deleted_at", "version")) {
            assertFalse(attrs.containsKey(ro),
                    "read-only field " + ro + " should not appear on the wire");
        }
    }

    @Test
    void forwardersCreate_wireShape_noExtraKeys() throws Exception {
        handler.set(ex -> respondJson(ex, 201, forwarderResponseJson("x")));
        CreateForwarderInput in = new CreateForwarderInput(
                "x", "datadog", new ForwarderHttp("https://x"));
        in.enabled = true;
        in.filter = Map.of("x", 1);
        in.transform = "$";
        in.data = Map.of("k", "v");
        client.forwarders().create(in);

        Map<String, Object> attrs = parseAttributes();
        Set<String> unexpected = new HashSet<>(attrs.keySet());
        unexpected.removeAll(FORWARDER_POST_ATTRS);
        assertTrue(unexpected.isEmpty(),
                "wire body has undocumented fields: " + unexpected);
    }

    // -----------------------------------------------------------------
    // forwarders.update — wire body
    // -----------------------------------------------------------------

    @Test
    void forwardersUpdate_wireShape_allParameters() throws Exception {
        handler.set(ex -> respondJson(ex, 200, forwarderResponseJson("Renamed")));
        CreateForwarderInput in = new CreateForwarderInput(
                "Renamed", "datadog",
                new ForwarderHttp("https://siem.example.com/in"));
        in.http.headers.add(new HttpHeader("X-K", "real-secret"));
        in.enabled = false;
        in.filter = Map.of("==", List.of(1, 1));
        in.transform = "$";
        in.data = Map.of("k", "v");
        client.forwarders().update(FWD_ID, in);

        assertEquals("PUT", capturedMethod.get());
        Map<String, Object> root = parseRoot();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        // On PUT the wrapper echoes the path id into the envelope id.
        assertEquals(FWD_ID.toString(), data.get("id"));

        Map<String, Object> attrs = parseAttributes();
        assertEquals("Renamed", attrs.get("name"));
        assertEquals(false, attrs.get("enabled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> http = (Map<String, Object>) attrs.get("http");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headers = (List<Map<String, Object>>) http.get("headers");
        // Headers carry the real plaintext value the caller supplied — the
        // wrapper does NOT round-trip the redacted GET response.
        assertEquals("real-secret", headers.get(0).get("value"));

        for (String ro : List.of("slug", "created_at", "updated_at", "deleted_at", "version")) {
            assertFalse(attrs.containsKey(ro),
                    "read-only field " + ro + " should not appear on the wire");
        }
    }

    @Test
    void forwardersUpdate_wireShape_noExtraKeys() throws Exception {
        handler.set(ex -> respondJson(ex, 200, forwarderResponseJson("Renamed")));
        CreateForwarderInput in = new CreateForwarderInput(
                "x", "http", new ForwarderHttp("https://x"));
        in.enabled = true;
        in.filter = Map.of("x", 1);
        in.transform = "$";
        in.data = Map.of("k", "v");
        client.forwarders().update(FWD_ID, in);

        Map<String, Object> attrs = parseAttributes();
        Set<String> unexpected = new HashSet<>(attrs.keySet());
        unexpected.removeAll(FORWARDER_POST_ATTRS);
        assertTrue(unexpected.isEmpty(),
                "wire body has undocumented fields: " + unexpected);
    }
}
