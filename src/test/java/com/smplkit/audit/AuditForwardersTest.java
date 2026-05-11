package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ActionsApi;
import com.smplkit.internal.generated.audit.api.ForwardersApi;
import com.smplkit.internal.generated.audit.api.ResourceTypesApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SIEM forwarders management surface, resource types, and
 * actions clients.
 *
 * <p>Stubs the audit service via the JDK's built-in HttpServer; no
 * real network. The wrapper layer here must reach 100% line coverage
 * to satisfy the SDK CI gate.</p>
 */
class AuditForwardersTest {

    private static final UUID FWD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private HttpServer server;
    private ForwardersApi forwardersApi;
    private ResourceTypesApi resourceTypesApi;
    private ActionsApi actionsApi;
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
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(baseUrl);
        apiClient.setReadTimeout(Duration.ofSeconds(5));
        forwardersApi = new ForwardersApi(apiClient);
        resourceTypesApi = new ResourceTypesApi(apiClient);
        actionsApi = new ActionsApi(apiClient);
    }

    @AfterEach
    void stop() {
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

    private static String forwarderResource(String name, String slug) {
        return "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"" + name + "\",\"slug\":\"" + slug + "\","
                + "\"forwarder_type\":\"DATADOG\",\"enabled\":true,"
                + "\"http\":{\"method\":\"POST\",\"url\":\"https://siem.example.com/in\","
                + "\"headers\":[{\"name\":\"DD-API-KEY\",\"value\":\"<redacted>\"}],"
                + "\"success_status\":\"2xx\"},"
                + "\"data\":{},\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}";
    }

    // -----------------------------------------------------------------
    // AuditForwarders CRUD
    // -----------------------------------------------------------------

    @Test
    void create_returnsForwarder() throws Exception {
        handler.set(ex -> respondJson(ex, 201,
                "{\"data\":" + forwarderResource("Datadog production", "datadog_production") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        CreateForwarderInput input = new CreateForwarderInput(
                "Datadog production", ForwarderType.DATADOG,
                new ForwarderHttp("https://siem.example.com/in"));
        input.http.headers.add(new HttpHeader("DD-API-KEY", "real-secret"));
        input.filter = Map.of("==", java.util.List.of(1, 1));
        input.transform = "$";
        Forwarder fwd = fwds.create(input);
        assertEquals("datadog_production", fwd.slug);
        assertEquals(1, fwd.http.headers.size());
        assertEquals("<redacted>", fwd.http.headers.get(0).value);
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
            respondJson(ex, 200, body);
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        ListForwardersInput in1 = new ListForwardersInput();
        in1.forwarderType = ForwarderType.DATADOG;
        in1.enabled = true;
        in1.pageSize = 1;
        ListForwardersPage first = fwds.list(in1);
        assertEquals("tok-2", first.nextCursor);
        ListForwardersInput in2 = new ListForwardersInput();
        in2.pageAfter = first.nextCursor;
        ListForwardersPage second = fwds.list(in2);
        assertNull(second.nextCursor);
    }

    @Test
    void get_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":" + forwarderResource("x", "x") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertEquals("x", fwd.name);
    }

    @Test
    void update_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":" + forwarderResource("Renamed", "renamed") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        CreateForwarderInput in = new CreateForwarderInput("Renamed", ForwarderType.DATADOG,
                new ForwarderHttp("https://x"));
        Forwarder fwd = fwds.update(FWD_ID, in);
        assertEquals("Renamed", fwd.name);
    }

    @Test
    void delete_success() throws Exception {
        handler.set(ex -> respondJson(ex, 204, ""));
        new AuditForwarders(forwardersApi).delete(FWD_ID);
    }

    @Test
    void list_emptyDataAndNoLinks() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"meta\":{\"page_size\":1}}"));
        ListForwardersPage page = new AuditForwarders(forwardersApi).list(new ListForwardersInput());
        assertEquals(0, page.forwarders.size());
        assertNull(page.nextCursor);
    }

    @Test
    void list_linkWithoutPageAfter_returnsNullCursor() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":[],\"links\":{\"next\":\"/api/v1/forwarders?other=v\"},\"meta\":{\"page_size\":1}}"));
        ListForwardersPage page = new AuditForwarders(forwardersApi).list(new ListForwardersInput());
        assertNull(page.nextCursor);
    }

    @Test
    void forwarderTypeFromValueRoundTripsAndRejectsUnknown() {
        for (ForwarderType t : ForwarderType.values()) {
            assertEquals(t, ForwarderType.fromValue(t.getValue()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ForwarderType.fromValue("definitely-not-a-real-type"));
    }

    @Test
    void modelDefaultConstructorsCoverable() {
        CreateForwarderInput cfi = new CreateForwarderInput();
        cfi.name = "x";
        ListForwardersInput lfi = new ListForwardersInput();
        ForwarderHttp fh = new ForwarderHttp();
        assertEquals("x", cfi.name);
        assertNull(lfi.forwarderType);
        assertEquals("POST", fh.method);
    }

    @Test
    void create_nullForwarderType_passesNull() throws Exception {
        handler.set(ex -> respondJson(ex, 201,
                "{\"data\":{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\","
                + "\"attributes\":{\"name\":\"x\",\"slug\":\"x\","
                + "\"enabled\":true,"
                + "\"http\":{\"method\":\"POST\",\"url\":\"https://x\","
                + "\"success_status\":\"2xx\"},"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        CreateForwarderInput in = new CreateForwarderInput("x", null, new ForwarderHttp("https://x"));
        Forwarder fwd = fwds.create(in);
        assertNull(fwd.forwarderType);
    }

    @Test
    void forwarder_httpFromGen_nullSrc() throws Exception {
        // Create a forwarder response with null http — exercises httpFromGen(null).
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\","
                + "\"attributes\":{\"name\":\"x\",\"slug\":\"x\","
                + "\"enabled\":false,"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertNotNull(fwd.http);
        assertFalse(fwd.enabled);
    }

    // -----------------------------------------------------------------
    // AuditManagementClient
    // -----------------------------------------------------------------

    @Test
    void auditManagementClient_exposesForwarders() {
        AuditManagementClient mgmt = new AuditManagementClient(
                "sk_test", Map.of(), Duration.ofSeconds(5),
                "http://127.0.0.1:" + server.getAddress().getPort());
        assertNotNull(mgmt.forwarders);
    }

    // -----------------------------------------------------------------
    // AuditResourceTypesClient
    // -----------------------------------------------------------------

    @Test
    void resourceTypes_list_returnsRows() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":["
                + "{\"id\":\"invoice\",\"type\":\"resource_type\","
                + "\"attributes\":{\"resource_type\":\"invoice\",\"created_at\":\"2026-04-01T00:00:00Z\"}},"
                + "{\"id\":\"user\",\"type\":\"resource_type\","
                + "\"attributes\":{\"resource_type\":\"user\",\"created_at\":\"2026-04-02T00:00:00Z\"}}"
                + "],\"meta\":{\"page_size\":25}}"));
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesPage page = rt.list(new ListResourceTypesInput());
        assertEquals(2, page.resourceTypes.size());
        assertEquals("invoice", page.resourceTypes.get(0).id);
        assertEquals("invoice", page.resourceTypes.get(0).resourceType);
        assertNotNull(page.resourceTypes.get(0).createdAt);
        assertNull(page.nextCursor);
    }

    @Test
    void resourceTypes_list_emptyData() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"meta\":{\"page_size\":25}}"));
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesPage page = rt.list(new ListResourceTypesInput());
        assertEquals(0, page.resourceTypes.size());
        assertNull(page.nextCursor);
    }

    @Test
    void resourceTypes_list_paginationCursor() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":[],"
                + "\"links\":{\"next\":\"/api/v1/resource_types?page[after]=tok-rt&page[size]=1\"},"
                + "\"meta\":{\"page_size\":1}}"));
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesInput in = new ListResourceTypesInput();
        in.pageSize = 1;
        ListResourceTypesPage page = rt.list(in);
        assertEquals("tok-rt", page.nextCursor);
    }

    @Test
    void resourceTypes_list_linkWithoutPageAfter_returnsNull() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":[],\"links\":{\"next\":\"/api/v1/resource_types?other=v\"}}"));
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesPage page = rt.list(new ListResourceTypesInput());
        assertNull(page.nextCursor);
    }

    @Test
    void resourceTypes_list_cursorWithAmpersand() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":[],"
                + "\"links\":{\"next\":\"/api/v1/resource_types?page[after]=tok-xyz&extra=junk\"}}"));
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesPage page = rt.list(new ListResourceTypesInput());
        assertEquals("tok-xyz", page.nextCursor);
    }

    @Test
    void auditResourceType_constructorPopulatesFields() {
        AuditResourceType rt = new AuditResourceType("invoice", "invoice",
                java.time.OffsetDateTime.parse("2026-04-01T00:00:00Z"));
        assertEquals("invoice", rt.id);
        assertEquals("invoice", rt.resourceType);
        assertNotNull(rt.createdAt);
    }

    @Test
    void listResourceTypesInput_isInstantiable() {
        ListResourceTypesInput in = new ListResourceTypesInput();
        in.pageSize = 10;
        in.pageAfter = "tok";
        assertEquals(10, in.pageSize);
        assertEquals("tok", in.pageAfter);
    }

    @Test
    void listResourceTypesPage_constructorPopulatesFields() {
        ListResourceTypesPage page = new ListResourceTypesPage(java.util.List.of(), "tok");
        assertEquals(0, page.resourceTypes.size());
        assertEquals("tok", page.nextCursor);
    }

    // -----------------------------------------------------------------
    // AuditActionsClient
    // -----------------------------------------------------------------

    @Test
    void actions_list_returnsRows() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":["
                + "{\"id\":\"invoice.created\",\"type\":\"action\","
                + "\"attributes\":{\"action\":\"invoice.created\",\"created_at\":\"2026-04-01T00:00:00Z\"}},"
                + "{\"id\":\"user.updated\",\"type\":\"action\","
                + "\"attributes\":{\"action\":\"user.updated\",\"created_at\":\"2026-04-02T00:00:00Z\"}}"
                + "],\"meta\":{\"page_size\":25}}"));
        AuditActionsClient ac = new AuditActionsClient(actionsApi);
        ListActionsInput in = new ListActionsInput();
        in.filterResourceType = "invoice";
        ListActionsPage page = ac.list(in);
        assertEquals(2, page.actions.size());
        assertEquals("invoice.created", page.actions.get(0).id);
        assertEquals("invoice.created", page.actions.get(0).action);
        assertNotNull(page.actions.get(0).createdAt);
        assertNull(page.nextCursor);
    }

    @Test
    void actions_list_emptyData() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"meta\":{\"page_size\":25}}"));
        AuditActionsClient ac = new AuditActionsClient(actionsApi);
        ListActionsPage page = ac.list(new ListActionsInput());
        assertEquals(0, page.actions.size());
        assertNull(page.nextCursor);
    }

    @Test
    void actions_list_paginationCursor() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":[],"
                + "\"links\":{\"next\":\"/api/v1/actions?page[after]=tok-ac&page[size]=1\"},"
                + "\"meta\":{\"page_size\":1}}"));
        AuditActionsClient ac = new AuditActionsClient(actionsApi);
        ListActionsInput in = new ListActionsInput();
        in.pageSize = 1;
        in.pageAfter = "prev-tok";
        ListActionsPage page = ac.list(in);
        assertEquals("tok-ac", page.nextCursor);
    }

    @Test
    void actions_list_linkWithoutPageAfter_returnsNull() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":[],\"links\":{\"next\":\"/api/v1/actions?other=v\"}}"));
        AuditActionsClient ac = new AuditActionsClient(actionsApi);
        ListActionsPage page = ac.list(new ListActionsInput());
        assertNull(page.nextCursor);
    }

    @Test
    void actions_list_cursorWithAmpersand() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":[],"
                + "\"links\":{\"next\":\"/api/v1/actions?page[after]=tok-ac&extra=junk\"}}"));
        AuditActionsClient ac = new AuditActionsClient(actionsApi);
        ListActionsPage page = ac.list(new ListActionsInput());
        assertEquals("tok-ac", page.nextCursor);
    }

    @Test
    void auditAction_constructorPopulatesFields() {
        AuditAction action = new AuditAction("invoice.created", "invoice.created",
                java.time.OffsetDateTime.parse("2026-04-01T00:00:00Z"));
        assertEquals("invoice.created", action.id);
        assertEquals("invoice.created", action.action);
        assertNotNull(action.createdAt);
    }

    @Test
    void listActionsInput_isInstantiable() {
        ListActionsInput in = new ListActionsInput();
        in.filterResourceType = "invoice";
        in.pageSize = 5;
        in.pageAfter = "tok";
        assertEquals("invoice", in.filterResourceType);
        assertEquals(5, in.pageSize);
        assertEquals("tok", in.pageAfter);
    }

    @Test
    void listActionsPage_constructorPopulatesFields() {
        ListActionsPage page = new ListActionsPage(java.util.List.of(), "tok-a");
        assertEquals(0, page.actions.size());
        assertEquals("tok-a", page.nextCursor);
    }
}
