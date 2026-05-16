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

    private static String forwarderResource(String name, String description) {
        return "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"" + name + "\",\"description\":\"" + description + "\","
                + "\"forwarder_type\":\"DATADOG\",\"enabled\":true,"
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://siem.example.com/in\","
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
                "{\"data\":" + forwarderResource("Datadog production", "prod sink") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        CreateForwarderInput input = new CreateForwarderInput(
                "Datadog production", ForwarderType.DATADOG,
                new HttpConfiguration("https://siem.example.com/in"));
        input.description = "prod sink";
        input.configuration.headers.add(new HttpHeader("DD-API-KEY", "real-secret"));
        input.filter = Map.of("==", java.util.List.of(1, 1));
        input.transformType = "JSONATA";
        input.transform = "$";
        Forwarder fwd = fwds.create(input);
        assertEquals("prod sink", fwd.description);
        assertEquals(1, fwd.configuration.headers.size());
        assertEquals("<redacted>", fwd.configuration.headers.get(0).value);
    }

    @Test
    void list_paginatesAndExposesPagination() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> lastUri = new java.util.concurrent.atomic.AtomicReference<>();
        handler.set(ex -> {
            int n = calls.incrementAndGet();
            lastUri.set(ex.getRequestURI().getRawQuery());
            String body = n == 1
                    ? "{\"data\":[" + forwarderResource("A", "a")
                            + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":1,\"total\":2,\"total_pages\":2}}}"
                    : "{\"data\":[" + forwarderResource("B", "b")
                            + "],\"meta\":{\"pagination\":{\"page\":2,\"size\":1}}}";
            respondJson(ex, 200, body);
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        ListForwardersInput in1 = new ListForwardersInput();
        in1.forwarderType = ForwarderType.DATADOG;
        in1.enabled = true;
        in1.pageNumber = 1;
        in1.pageSize = 1;
        in1.metaTotal = true;
        ListForwardersPage first = fwds.list(in1);
        assertEquals(1, first.pagination.page);
        assertEquals(1, first.pagination.size);
        assertEquals(2, first.pagination.total);
        assertEquals(2, first.pagination.totalPages);
        assertTrue(lastUri.get().contains("meta%5Btotal%5D=true"),
                "expected meta[total]=true in query: " + lastUri.get());
        ListForwardersInput in2 = new ListForwardersInput();
        in2.pageNumber = 2;
        in2.pageSize = 1;
        ListForwardersPage second = fwds.list(in2);
        assertEquals(2, second.pagination.page);
        assertNull(second.pagination.total);
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
                "{\"data\":" + forwarderResource("Renamed", "renamed sink") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        CreateForwarderInput in = new CreateForwarderInput("Renamed", ForwarderType.DATADOG,
                new HttpConfiguration("https://x"));
        Forwarder fwd = fwds.update(FWD_ID, in);
        assertEquals("Renamed", fwd.name);
    }

    @Test
    void delete_success() throws Exception {
        handler.set(ex -> respondJson(ex, 204, ""));
        new AuditForwarders(forwardersApi).delete(FWD_ID);
    }

    @Test
    void list_emptyDataAndMissingMeta_returnsNullPagination() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        ListForwardersPage page = new AuditForwarders(forwardersApi).list(new ListForwardersInput());
        assertEquals(0, page.forwarders.size());
        assertNull(page.pagination);
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
        HttpConfiguration cfg = new HttpConfiguration();
        assertEquals("x", cfi.name);
        assertNull(lfi.forwarderType);
        assertEquals("POST", cfg.method);
    }

    @Test
    void create_nullForwarderType_passesNull() throws Exception {
        handler.set(ex -> respondJson(ex, 201,
                "{\"data\":{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\","
                + "\"attributes\":{\"name\":\"x\","
                + "\"enabled\":true,"
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://x\","
                + "\"success_status\":\"2xx\"},"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        CreateForwarderInput in = new CreateForwarderInput("x", null, new HttpConfiguration("https://x"));
        Forwarder fwd = fwds.create(in);
        assertNull(fwd.forwarderType);
    }

    @Test
    void forwarder_configurationFromGen_nullSrc() throws Exception {
        // Create a forwarder response with null configuration — exercises
        // configurationFromGen(null).
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\","
                + "\"attributes\":{\"name\":\"x\","
                + "\"enabled\":false,"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertNotNull(fwd.configuration);
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
                + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":1000}}}"));
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesPage page = rt.list(new ListResourceTypesInput());
        assertEquals(2, page.resourceTypes.size());
        assertEquals("invoice", page.resourceTypes.get(0).id);
        assertEquals("invoice", page.resourceTypes.get(0).resourceType);
        assertNotNull(page.resourceTypes.get(0).createdAt);
        assertEquals(1, page.pagination.page);
        assertEquals(1000, page.pagination.size);
        assertNull(page.pagination.total);
        assertNull(page.pagination.totalPages);
    }

    @Test
    void resourceTypes_list_missingMeta_returnsNullPagination() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesPage page = rt.list(new ListResourceTypesInput());
        assertEquals(0, page.resourceTypes.size());
        assertNull(page.pagination);
    }

    @Test
    void resourceTypes_list_pageNumberAndMetaTotalForwardedToQuery() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> lastUri = new java.util.concurrent.atomic.AtomicReference<>();
        handler.set(ex -> {
            lastUri.set(ex.getRequestURI().getRawQuery());
            respondJson(ex, 200,
                    "{\"data\":[],\"meta\":{\"pagination\":{\"page\":2,\"size\":1,\"total\":3,\"total_pages\":3}}}");
        });
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesInput in = new ListResourceTypesInput();
        in.pageNumber = 2;
        in.pageSize = 1;
        in.metaTotal = true;
        ListResourceTypesPage page = rt.list(in);
        assertEquals(2, page.pagination.page);
        assertEquals(3, page.pagination.total);
        assertEquals(3, page.pagination.totalPages);
        assertTrue(lastUri.get().contains("page%5Bnumber%5D=2"),
                "expected page[number]=2: " + lastUri.get());
        assertTrue(lastUri.get().contains("page%5Bsize%5D=1"),
                "expected page[size]=1: " + lastUri.get());
        assertTrue(lastUri.get().contains("meta%5Btotal%5D=true"),
                "expected meta[total]=true: " + lastUri.get());
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
        in.pageNumber = 2;
        in.pageSize = 10;
        in.metaTotal = true;
        assertEquals(2, in.pageNumber);
        assertEquals(10, in.pageSize);
        assertTrue(in.metaTotal);
    }

    @Test
    void listResourceTypesPage_constructorPopulatesFields() {
        PageInfo info = new PageInfo(1, 1000, null, null);
        ListResourceTypesPage page = new ListResourceTypesPage(java.util.List.of(), info);
        assertEquals(0, page.resourceTypes.size());
        assertSame(info, page.pagination);
    }

    @Test
    void pageInfo_carriesTotalAndTotalPagesWhenRequested() {
        PageInfo info = new PageInfo(3, 50, 250, 5);
        assertEquals(3, info.page);
        assertEquals(50, info.size);
        assertEquals(250, info.total);
        assertEquals(5, info.totalPages);
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
                + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":1000}}}"));
        AuditActionsClient ac = new AuditActionsClient(actionsApi);
        ListActionsInput in = new ListActionsInput();
        in.filterResourceType = "invoice";
        ListActionsPage page = ac.list(in);
        assertEquals(2, page.actions.size());
        assertEquals("invoice.created", page.actions.get(0).id);
        assertEquals("invoice.created", page.actions.get(0).action);
        assertNotNull(page.actions.get(0).createdAt);
        assertEquals(1, page.pagination.page);
        assertEquals(1000, page.pagination.size);
    }

    @Test
    void actions_list_missingMeta_returnsNullPagination() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        AuditActionsClient ac = new AuditActionsClient(actionsApi);
        ListActionsPage page = ac.list(new ListActionsInput());
        assertEquals(0, page.actions.size());
        assertNull(page.pagination);
    }

    @Test
    void actions_list_filterAndPaginationForwardedToQuery() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> lastUri = new java.util.concurrent.atomic.AtomicReference<>();
        handler.set(ex -> {
            lastUri.set(ex.getRequestURI().getRawQuery());
            respondJson(ex, 200,
                    "{\"data\":[],\"meta\":{\"pagination\":{\"page\":2,\"size\":1,\"total\":3,\"total_pages\":3}}}");
        });
        AuditActionsClient ac = new AuditActionsClient(actionsApi);
        ListActionsInput in = new ListActionsInput();
        in.filterResourceType = "invoice";
        in.pageNumber = 2;
        in.pageSize = 1;
        in.metaTotal = true;
        ListActionsPage page = ac.list(in);
        assertEquals(2, page.pagination.page);
        assertEquals(3, page.pagination.total);
        assertTrue(lastUri.get().contains("filter%5Bresource_type%5D=invoice"),
                "expected filter[resource_type]=invoice: " + lastUri.get());
        assertTrue(lastUri.get().contains("page%5Bnumber%5D=2"),
                "expected page[number]=2: " + lastUri.get());
        assertTrue(lastUri.get().contains("meta%5Btotal%5D=true"),
                "expected meta[total]=true: " + lastUri.get());
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
        in.pageNumber = 1;
        in.pageSize = 5;
        in.metaTotal = false;
        assertEquals("invoice", in.filterResourceType);
        assertEquals(1, in.pageNumber);
        assertEquals(5, in.pageSize);
        assertFalse(in.metaTotal);
    }

    @Test
    void listActionsPage_constructorPopulatesFields() {
        PageInfo info = new PageInfo(1, 1000, null, null);
        ListActionsPage page = new ListActionsPage(java.util.List.of(), info);
        assertEquals(0, page.actions.size());
        assertSame(info, page.pagination);
    }
}
