package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SIEM forwarders + functions wrapper surface.
 *
 * <p>Stubs the audit service via the JDK's built-in HttpServer; no
 * real network. The wrapper layer here must reach 100% line coverage
 * to satisfy the SDK CI gate.</p>
 */
class AuditForwardersTest {

    private static final UUID FWD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID DELIVERY_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    private HttpServer server;
    private AuditClient client;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", ex -> {
            HttpHandler h = handler.get();
            if (h == null) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
            } else {
                h.handle(ex);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new AuditClient(HttpClient.newHttpClient(), "sk_api_test",
                Duration.ofSeconds(5), baseUrl);
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    private void respondJson(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] resp = body.getBytes();
        ex.getResponseHeaders().add("Content-Type", contentType);
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, resp.length);
            ex.getResponseBody().write(resp);
        }
        ex.close();
    }

    private static String forwarderResource(String name, String slug) {
        return "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"" + name + "\",\"slug\":\"" + slug + "\","
                + "\"forwarder_type\":\"datadog\",\"enabled\":true,"
                + "\"http\":{\"method\":\"POST\",\"url\":\"https://siem.example.com/in\","
                + "\"headers\":[{\"name\":\"DD-API-KEY\",\"value\":\"<redacted>\"}],"
                + "\"success_status\":\"2xx\"},"
                + "\"data\":{},\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}";
    }

    private static String deliveryResource(String status) {
        return "{\"id\":\"" + DELIVERY_ID + "\",\"type\":\"forwarder_delivery\","
                + "\"attributes\":{\"forwarder_id\":\"" + FWD_ID + "\","
                + "\"event_id\":\"33333333-4444-5555-6666-777777777777\","
                + "\"attempt_number\":1,\"status\":\"" + status + "\","
                + "\"request\":{\"method\":\"POST\",\"url\":\"https://x\","
                + "\"headers\":[{\"name\":\"X-K\",\"value\":\"<redacted>\"}]},"
                + "\"response_status\":202,\"response_body\":\"ok\","
                + "\"latency_ms\":42,\"created_at\":\"2026-05-07T12:00:01Z\"}}";
    }

    // -----------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------

    @Test
    void create_returnsForwarder() throws Exception {
        handler.set(ex -> respondJson(ex, 201, "application/vnd.api+json",
                "{\"data\":" + forwarderResource("Datadog production", "datadog_production") + "}"));
        CreateForwarderInput input = new CreateForwarderInput(
                "Datadog production", "datadog",
                new ForwarderHttp("https://siem.example.com/in"));
        input.http.headers.add(new HttpHeader("DD-API-KEY", "real-secret"));
        input.filter = java.util.Map.of("==", java.util.List.of(1, 1));
        input.transform = "$";
        input.data = java.util.Map.of("team", "platform");
        Forwarder fwd = client.forwarders().create(input);
        assertEquals("datadog_production", fwd.slug);
        assertEquals(1, fwd.http.headers.size());
        assertEquals("<redacted>", fwd.http.headers.get(0).value);
    }

    @Test
    void create_402_throwsApiException() {
        handler.set(ex -> respondJson(ex, 402, "application/vnd.api+json",
                "{\"errors\":[{\"status\":\"402\"}]}"));
        CreateForwarderInput input = new CreateForwarderInput("x", "http",
                new ForwarderHttp("https://x"));
        assertThrows(ApiException.class, () -> client.forwarders().create(input));
    }

    @Test
    void list_paginatesAndExtractsCursor() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        handler.set(ex -> {
            int n = calls.incrementAndGet();
            String body = n == 1
                    ? "{\"data\":[" + forwarderResource("A", "a")
                            + "],\"links\":{\"next\":\"/api/v1/forwarders?page[size]=1&page[after]=tok-2\"},"
                            + "\"meta\":{\"page_size\":1}}"
                    : "{\"data\":[" + forwarderResource("B", "b")
                            + "],\"meta\":{\"page_size\":1}}";
            respondJson(ex, 200, "application/vnd.api+json", body);
        });
        ListForwardersInput in1 = new ListForwardersInput();
        in1.forwarderType = "datadog";
        in1.enabled = true;
        in1.pageSize = 1;
        ListForwardersPage first = client.forwarders().list(in1);
        assertEquals("tok-2", first.nextCursor);
        ListForwardersInput in2 = new ListForwardersInput();
        in2.pageAfter = first.nextCursor;
        ListForwardersPage second = client.forwarders().list(in2);
        assertNull(second.nextCursor);
    }

    @Test
    void get_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json",
                "{\"data\":" + forwarderResource("x", "x") + "}"));
        Forwarder fwd = client.forwarders().get(FWD_ID);
        assertEquals("x", fwd.name);
    }

    @Test
    void update_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json",
                "{\"data\":" + forwarderResource("Renamed", "renamed") + "}"));
        CreateForwarderInput in = new CreateForwarderInput("Renamed", "datadog",
                new ForwarderHttp("https://x"));
        Forwarder fwd = client.forwarders().update(FWD_ID, in);
        assertEquals("Renamed", fwd.name);
    }

    @Test
    void delete_success() throws Exception {
        handler.set(ex -> respondJson(ex, 204, "application/vnd.api+json", ""));
        client.forwarders().delete(FWD_ID);
    }

    // -----------------------------------------------------------------
    // Deliveries
    // -----------------------------------------------------------------

    @Test
    void deliveries_list_filtersAndPaginates() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json",
                "{\"data\":[" + deliveryResource("succeeded") + "],"
                        + "\"meta\":{\"page_size\":1}}"));
        ListDeliveriesInput in = new ListDeliveriesInput();
        in.status = "succeeded";
        in.createdAtRange = "[2020-01-01T00:00:00Z,*)";
        in.pageSize = 1;
        ListDeliveriesPage page = client.forwarders().listDeliveries(FWD_ID, in);
        assertEquals(1, page.deliveries.size());
        assertEquals("succeeded", page.deliveries.get(0).status);
    }

    @Test
    void retryDelivery_returnsNewRow() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json",
                "{\"data\":" + deliveryResource("succeeded") + "}"));
        ForwarderDelivery row = client.forwarders().retryDelivery(FWD_ID, DELIVERY_ID);
        assertEquals("succeeded", row.status);
    }

    @Test
    void retryFailedDeliveries_summary() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json",
                "{\"attempted\":3,\"succeeded\":2,\"failed\":1}"));
        RetryFailedDeliveriesSummary s = client.forwarders().retryFailedDeliveries(FWD_ID);
        assertEquals(3, s.attempted);
        assertEquals(2, s.succeeded);
        assertEquals(1, s.failed);
    }

    // -----------------------------------------------------------------
    // functions.test_forwarder.execute
    // -----------------------------------------------------------------

    @Test
    void executeTestForwarder_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "application/json",
                "{\"succeeded\":true,\"response_status\":202,"
                        + "\"response_headers\":{\"X-Echo\":\"y\"},"
                        + "\"response_body\":\"accepted\","
                        + "\"latency_ms\":12,\"error\":null}"));
        TestForwarderInput in = new TestForwarderInput("https://siem.example.com/in");
        in.body = "{\"hello\":\"world\"}";
        in.timeoutMs = 5000;
        in.headers.add(new HttpHeader("X-K", "v"));
        TestForwarderResult r = client.functions().executeTestForwarder(in);
        assertTrue(r.succeeded);
        assertEquals(Integer.valueOf(202), r.responseStatus);
        assertEquals("accepted", r.responseBody);
        assertEquals("y", r.responseHeaders.get("X-Echo"));
    }

    // -----------------------------------------------------------------
    // do_not_forward
    // -----------------------------------------------------------------

    @Test
    void modelDefaultConstructorsCoverable() {
        // Exercise the no-arg ctors so coverage hits the empty-init paths.
        CreateForwarderInput cfi = new CreateForwarderInput();
        cfi.name = "x";
        TestForwarderInput tfi = new TestForwarderInput();
        tfi.url = "https://x";
        ListForwardersInput lfi = new ListForwardersInput();
        ListDeliveriesInput ldi = new ListDeliveriesInput();
        ForwarderHttp fh = new ForwarderHttp();
        assertEquals("x", cfi.name);
        assertEquals("https://x", tfi.url);
        assertNull(lfi.forwarderType);
        assertNull(ldi.status);
        assertEquals("POST", fh.method);
    }

    @Test
    void list_emptyDataAndNoLinks() throws Exception {
        // Covers the (resp.getData() == null) and (resp.getLinks() == null) branches.
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json",
                "{\"meta\":{\"page_size\":1}}"));
        ListForwardersPage page = client.forwarders().list(new ListForwardersInput());
        assertEquals(0, page.forwarders.size());
        assertNull(page.nextCursor);
    }

    @Test
    void deliveries_list_emptyDataAndNoLinks() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json",
                "{\"meta\":{\"page_size\":1}}"));
        ListDeliveriesPage page = client.forwarders().listDeliveries(FWD_ID, new ListDeliveriesInput());
        assertEquals(0, page.deliveries.size());
        assertNull(page.nextCursor);
    }

    @Test
    void list_linkWithoutPageAfter_returnsNullCursor() throws Exception {
        // Covers the nextCursor() branch where link exists but doesn't
        // contain page[after]= (e.g. a server error in pagination shape).
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json",
                "{\"data\":[],\"links\":{\"next\":\"/api/v1/forwarders?other=v\"},\"meta\":{\"page_size\":1}}"));
        ListForwardersPage page = client.forwarders().list(new ListForwardersInput());
        assertNull(page.nextCursor);
    }

    @Test
    void retryFailedDeliveries_handlesNullFields() throws Exception {
        // The summary fields are non-nullable per the spec; the wrapper
        // also defends against missing values. Send an empty object.
        handler.set(ex -> respondJson(ex, 200, "application/vnd.api+json", "{}"));
        RetryFailedDeliveriesSummary s = client.forwarders().retryFailedDeliveries(FWD_ID);
        assertEquals(0, s.attempted);
        assertEquals(0, s.succeeded);
        assertEquals(0, s.failed);
    }

    @Test
    void executeTestForwarder_nullResponseBody() throws Exception {
        // Covers the (resp.getResponseBody() == null) branch.
        handler.set(ex -> respondJson(ex, 200, "application/json",
                "{\"succeeded\":false,\"response_status\":null,"
                        + "\"response_headers\":{},\"latency_ms\":null,\"error\":\"x\"}"));
        TestForwarderInput in = new TestForwarderInput("https://x");
        TestForwarderResult r = client.functions().executeTestForwarder(in);
        assertFalse(r.succeeded);
        assertEquals("", r.responseBody);
    }

    @Test
    void events_record_passesDoNotForward() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        handler.set(ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            capturedBody.set(new String(body));
            latch.countDown();
            String resp = "{\"data\":{\"id\":\"00000000-0000-0000-0000-000000000001\","
                    + "\"type\":\"event\",\"attributes\":{\"action\":\"u.created\","
                    + "\"resource_type\":\"u\",\"resource_id\":\"1\","
                    + "\"do_not_forward\":true,"
                    + "\"occurred_at\":\"2026-05-07T12:00:00Z\","
                    + "\"created_at\":\"2026-05-07T12:00:01Z\","
                    + "\"actor_type\":\"API_KEY\",\"actor_label\":\"\"}}}";
            respondJson(ex, 201, "application/vnd.api+json", resp);
        });
        CreateEventInput in = new CreateEventInput("u.created", "u", "1");
        in.doNotForward = true;
        client.events().record(in);
        client.events().flush(2000);
        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(capturedBody.get().contains("\"do_not_forward\":true"),
                "expected do_not_forward in body, got: " + capturedBody.get());
    }
}
