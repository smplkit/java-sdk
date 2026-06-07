package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.CategoriesApi;
import com.smplkit.internal.generated.audit.api.EventTypesApi;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SIEM forwarders management surface, resource types, and
 * event types clients.
 *
 * <p>Stubs the audit service via the JDK's built-in HttpServer; no
 * real network. The wrapper layer here must reach 100% line coverage
 * to satisfy the SDK CI gate.</p>
 */
class AuditForwardersTest {

    private static final String FWD_ID = "datadog-prod";

    private HttpServer server;
    private ForwardersApi forwardersApi;
    private ResourceTypesApi resourceTypesApi;
    private EventTypesApi eventTypesApi;
    private CategoriesApi categoriesApi;
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
        eventTypesApi = new EventTypesApi(apiClient);
        categoriesApi = new CategoriesApi(apiClient);
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
        return forwarderResource(name, description, true, "2026-05-07T12:00:00Z");
    }

    private static String forwarderResource(String name, String description,
                                            boolean enabled, String createdAt) {
        return "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"" + name + "\",\"description\":\"" + description + "\","
                + "\"forwarder_type\":\"datadog\",\"enabled\":" + enabled + ","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://siem.example.com/in\","
                + "\"headers\":[{\"name\":\"DD-API-KEY\",\"value\":\"<redacted>\"}],"
                + "\"success_status\":\"2xx\"},"
                + "\"data\":{},\"created_at\":\"" + createdAt + "\","
                + "\"updated_at\":\"" + createdAt + "\",\"version\":1}}";
    }

    // -----------------------------------------------------------------
    // Active-record save / delete via Forwarder
    // -----------------------------------------------------------------

    @Test
    void newForwarderSave_postsAndAppliesResponse() throws Exception {
        handler.set(ex -> respondJson(ex, 201,
                "{\"data\":" + forwarderResource("Datadog production", "prod sink") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(
                FWD_ID, "Datadog production", ForwarderType.DATADOG,
                new HttpConfiguration("https://siem.example.com/in"),
                TransformType.JSONATA, "$");
        fwd.description = "prod sink";
        fwd.configuration.headers.add(new HttpHeader("DD-API-KEY", "real-secret"));
        fwd.filter = Map.of("==", java.util.List.of(1, 1));
        fwd.save();
        assertEquals(FWD_ID, fwd.id);
        assertEquals("prod sink", fwd.description);
        assertEquals(1, fwd.configuration.headers.size());
        assertEquals("<redacted>", fwd.configuration.headers.get(0).value);
        assertNotNull(fwd.createdAt);
    }

    @Test
    void newForwarderWithTransform_nullTransformIsOk() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        // Passing both as null is valid (no transform configured).
        Forwarder fwd = fwds.newForwarder("k", "x", ForwarderType.HTTP,
                new HttpConfiguration("https://x"), null, null);
        assertNull(fwd.transformType);
        assertNull(fwd.transform);
    }

    @Test
    void newForwarderWithTransform_throwsWhenTypeMissing() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        assertThrows(IllegalArgumentException.class,
                () -> fwds.newForwarder("k", "x", ForwarderType.HTTP,
                        new HttpConfiguration("https://x"), null, "$"));
    }

    @Test
    void save_throwsWhenTransformSetButTypeMissing() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder("k", "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        fwd.transform = "$";
        assertThrows(IllegalArgumentException.class, fwd::save);
    }

    @Test
    void newForwarderWithTransform_throwsWhenTransformMissing() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        // transformType set without transform → reject (both-or-neither).
        assertThrows(IllegalArgumentException.class,
                () -> fwds.newForwarder("k", "x", ForwarderType.HTTP,
                        new HttpConfiguration("https://x"), TransformType.JSONATA, null));
    }

    @Test
    void newForwarderWithTransform_throwsWhenJsonataAndNonString() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        // JSONATA requires transform to be a String.
        assertThrows(IllegalArgumentException.class,
                () -> fwds.newForwarder("k", "x", ForwarderType.HTTP,
                        new HttpConfiguration("https://x"),
                        TransformType.JSONATA, Map.of("template", "$")));
    }

    @Test
    void save_throwsWhenTransformTypeSetButTransformMissing() throws Exception {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder("k", "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        fwd.transformType = TransformType.JSONATA;
        assertThrows(IllegalArgumentException.class, fwd::save);
    }

    @Test
    void save_throwsWhenJsonataAndNonStringTransform() throws Exception {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder("k", "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        fwd.transformType = TransformType.JSONATA;
        fwd.transform = Map.of("template", "$");
        assertThrows(IllegalArgumentException.class, fwd::save);
    }

    @Test
    void create_withoutId_throws() {
        // Create requires a caller-supplied id; the wrapper rejects null
        // before hitting the API so we can give a clear error.
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = new Forwarder(fwds, null, "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        assertThrows(IllegalStateException.class, fwd::save);
    }

    @Test
    void existingForwarderSave_putsAndAppliesResponse() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":" + forwarderResource("Renamed", "renamed sink", false, "2026-05-07T12:00:00Z") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);  // first GET handler also returns the same body
        fwd.name = "Renamed";
        fwd.save();
        assertEquals("Renamed", fwd.name);
        // The base ``enabled`` is server-pinned false (read-only).
        assertFalse(fwd.enabled);
    }

    @Test
    void forwarderDelete_callsApi() throws Exception {
        // First GET returns a saved forwarder; the DELETE follow-up returns 204.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        handler.set(ex -> {
            int n = calls.incrementAndGet();
            if (n == 1) respondJson(ex, 200, "{\"data\":" + forwarderResource("x", "x") + "}");
            else respondJson(ex, 204, "");
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        fwd.delete();
    }

    @Test
    void save_withoutClient_throws() {
        Forwarder fwd = new Forwarder(null, "k", "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        assertThrows(IllegalStateException.class, fwd::save);
    }

    @Test
    void delete_withoutClient_throws() {
        Forwarder fwd = new Forwarder(null, FWD_ID, "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        assertThrows(IllegalStateException.class, fwd::delete);
    }

    @Test
    void delete_withoutId_throws() throws Exception {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        // Mimic a Forwarder that's bound to a client but lost its id (e.g. someone cleared the field).
        Forwarder fwd = fwds.newForwarder("k", "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        fwd.id = null;
        assertThrows(IllegalStateException.class, fwd::delete);
    }

    @Test
    void update_withoutId_throws() throws Exception {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder("k", "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        fwd.id = null;
        assertThrows(IllegalStateException.class, () -> fwds.update(fwd));
    }

    // -----------------------------------------------------------------
    // AuditForwarders list / get / delete
    // -----------------------------------------------------------------

    @Test
    void list_noArg_usesDefaults() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":[" + forwarderResource("a", "a")
                        + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":1000}}}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        ListForwardersPage page = fwds.list();
        assertEquals(1, page.forwarders.size());
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
    void delete_byId_success() throws Exception {
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
    void enumsFromValueRoundTripAndRejectUnknown() {
        for (ForwarderType t : ForwarderType.values()) {
            assertEquals(t, ForwarderType.fromValue(t.getValue()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ForwarderType.fromValue("definitely-not-a-real-type"));
        for (HttpMethod m : HttpMethod.values()) {
            assertEquals(m, HttpMethod.fromValue(m.getValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> HttpMethod.fromValue("nope"));
        for (TransformType t : TransformType.values()) {
            assertEquals(t, TransformType.fromValue(t.getValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> TransformType.fromValue("nope"));
    }

    @Test
    void modelDefaultConstructorsCoverable() {
        ListForwardersInput lfi = new ListForwardersInput();
        HttpConfiguration cfg = new HttpConfiguration();
        HttpConfiguration cfg2 = new HttpConfiguration(HttpMethod.GET, "https://x",
                java.util.List.of(new HttpHeader("k", "v")));
        assertNull(lfi.forwarderType);
        assertEquals(HttpMethod.POST, cfg.method);
        assertEquals(HttpMethod.GET, cfg2.method);
        assertEquals(1, cfg2.headers.size());
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
        Forwarder fwd = fwds.newForwarder("k", "x", null, new HttpConfiguration("https://x"));
        fwd.save();
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

    @Test
    void forwarder_transformType_roundTrips() throws Exception {
        // Server returns a forwarder with transform + transform_type — exercises
        // the gen-to-wrapper TransformType conversion path.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\","
                + "\"attributes\":{\"name\":\"x\","
                + "\"forwarder_type\":\"http\","
                + "\"enabled\":true,"
                + "\"transform\":\"$\",\"transform_type\":\"JSONATA\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://x\","
                + "\"success_status\":\"2xx\"},"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertEquals(TransformType.JSONATA, fwd.transformType);
        assertEquals("$", fwd.transform);
    }

    @Test
    void forwarder_explicitTransformType_passesThrough() throws Exception {
        // Set transformType explicitly via fields — exercises the wrap-request
        // branch that copies transformType into the wire model.
        handler.set(ex -> respondJson(ex, 201,
                "{\"data\":" + forwarderResource("x", "x") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder("k", "x", ForwarderType.HTTP, new HttpConfiguration("https://x"));
        fwd.transform = "$";
        fwd.transformType = TransformType.JSONATA;
        fwd.save();
        // Round-trip succeeds (server response is forwarderResource which carries no transform fields).
    }

    // -----------------------------------------------------------------
    // Per-environment enablement (ADR-055)
    // -----------------------------------------------------------------

    @Test
    void forwarderEnvironment_constructors_populateFields() {
        ForwarderEnvironment defaults = new ForwarderEnvironment();
        assertFalse(defaults.enabled);
        assertNull(defaults.configuration);

        ForwarderEnvironment enabledOnly = new ForwarderEnvironment(true);
        assertTrue(enabledOnly.enabled);
        assertNull(enabledOnly.configuration);

        HttpConfiguration cfg = new HttpConfiguration("https://override.example.com");
        ForwarderEnvironment withCfg = new ForwarderEnvironment(true, cfg);
        assertTrue(withCfg.enabled);
        assertSame(cfg, withCfg.configuration);
    }

    @Test
    void saveForwarder_sendsEnvironmentsMap_onWire() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        handler.set(ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            body.set(new String(in));
            respondJson(ex, 201,
                    "{\"data\":" + forwarderResource("Datadog production", "prod sink") + "}");
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "Datadog production",
                ForwarderType.DATADOG, new HttpConfiguration("https://siem.example.com/in"));
        // production: enabled with a per-environment configuration override.
        HttpConfiguration prodOverride = new HttpConfiguration("https://prod-override.example.com");
        fwd.environments.put("production", new ForwarderEnvironment(true, prodOverride));
        // staging: enabled, no override (inherits base configuration).
        fwd.environments.put("staging", new ForwarderEnvironment(true));
        fwd.save();
        assertTrue(body.get().contains("\"environments\""),
                "expected environments map on wire, got: " + body.get());
        assertTrue(body.get().contains("\"production\""),
                "expected production entry on wire, got: " + body.get());
        assertTrue(body.get().contains("https://prod-override.example.com"),
                "expected per-environment config override on wire, got: " + body.get());
        // Per-environment enablement IS sent (inside the environments map).
        assertTrue(body.get().contains("\"enabled\":true"),
                "expected per-environment enabled=true on wire, got: " + body.get());
        // The wrapper never sets the base ``enabled`` (it's server-pinned false
        // and read-only); the generated model leaves it at its false default, so
        // the base attributes carry at most ``"enabled":false`` and never
        // ``enabled:true`` outside the environments map.
        String wire = body.get();
        int envIdx = wire.indexOf("\"environments\"");
        assertTrue(envIdx > 0, "environments must be present");
        assertFalse(wire.substring(0, envIdx).contains("\"enabled\":true"),
                "base attributes must not carry enabled=true before environments: " + wire);
    }

    @Test
    void getForwarder_readsEnvironmentsMap_fromWire() throws Exception {
        String resource = "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"n\",\"description\":\"d\","
                + "\"forwarder_type\":\"datadog\",\"enabled\":false,"
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://base.example.com\","
                + "\"headers\":[],\"success_status\":\"2xx\"},"
                + "\"environments\":{"
                + "\"production\":{\"enabled\":true,\"configuration\":{\"method\":\"POST\","
                + "\"url\":\"https://prod-override.example.com\",\"headers\":[],\"success_status\":\"2xx\"}},"
                + "\"staging\":{\"enabled\":false}"
                + "},"
                + "\"data\":{},\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}";
        handler.set(ex -> respondJson(ex, 200, "{\"data\":" + resource + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertFalse(fwd.enabled);
        assertEquals(2, fwd.environments.size());
        ForwarderEnvironment prod = fwd.environments.get("production");
        assertTrue(prod.enabled);
        assertNotNull(prod.configuration);
        assertEquals("https://prod-override.example.com", prod.configuration.url);
        ForwarderEnvironment staging = fwd.environments.get("staging");
        assertFalse(staging.enabled);
        assertNull(staging.configuration);
    }

    @Test
    void getForwarder_absentEnvironments_yieldsEmptyMap() throws Exception {
        // forwarderResource() emits no environments key — exercises the
        // environmentsFromGen(null) path.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":" + forwarderResource("n", "d") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertNotNull(fwd.environments);
        assertTrue(fwd.environments.isEmpty());
    }

    @Test
    void forwarder_fullArgsConstructor_nullEnvironmentsDefaultsToEmptyMap() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = new Forwarder(fwds, FWD_ID, "n", "d", ForwarderType.HTTP, false,
                null, null, null, null, false, new HttpConfiguration("https://x"),
                null, null, null, null);
        assertNotNull(fwd.environments);
        assertTrue(fwd.environments.isEmpty());
    }

    // -----------------------------------------------------------------
    // forward_smplkit_events (base-level platform-event opt-in)
    // -----------------------------------------------------------------

    @Test
    void forwarder_defaultsForwardSmplkitEventsFalse() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "x", ForwarderType.HTTP,
                new HttpConfiguration("https://x"));
        assertFalse(fwd.forwardSmplkitEvents);
    }

    @Test
    void saveForwarder_defaultsForwardSmplkitEventsFalse_onWire() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        handler.set(ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            body.set(new String(in));
            respondJson(ex, 201, "{\"data\":" + forwarderResource("x", "x") + "}");
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        // Caller leaves forwardSmplkitEvents untouched — wire carries the false default.
        Forwarder fwd = fwds.newForwarder(FWD_ID, "x", ForwarderType.HTTP,
                new HttpConfiguration("https://x"));
        fwd.save();
        assertTrue(body.get().contains("\"forward_smplkit_events\":false"),
                "expected forward_smplkit_events=false on wire, got: " + body.get());
    }

    @Test
    void saveForwarder_sendsForwardSmplkitEventsTrue_onWire() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        handler.set(ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            body.set(new String(in));
            respondJson(ex, 201,
                    "{\"data\":" + forwarderResourceWithSmplkitEvents("x", true) + "}");
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "x", ForwarderType.HTTP,
                new HttpConfiguration("https://x"));
        fwd.forwardSmplkitEvents = true;
        fwd.save();
        assertTrue(body.get().contains("\"forward_smplkit_events\":true"),
                "expected forward_smplkit_events=true on wire, got: " + body.get());
        // create applied the server response back onto the instance.
        assertTrue(fwd.forwardSmplkitEvents);
    }

    @Test
    void updateForwarder_changesForwardSmplkitEvents_onWire() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        handler.set(ex -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                // initial GET: forwarder currently has forward_smplkit_events=false.
                try {
                    respondJson(ex, 200,
                            "{\"data\":" + forwarderResourceWithSmplkitEvents("x", false) + "}");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                // the PUT: capture method + body, return the toggled value.
                method.set(ex.getRequestMethod());
                byte[] in;
                try {
                    in = ex.getRequestBody().readAllBytes();
                    body.set(new String(in));
                    respondJson(ex, 200,
                            "{\"data\":" + forwarderResourceWithSmplkitEvents("x", true) + "}");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertFalse(fwd.forwardSmplkitEvents);
        fwd.forwardSmplkitEvents = true;
        fwd.save();
        assertEquals("PUT", method.get());
        assertTrue(body.get().contains("\"forward_smplkit_events\":true"),
                "expected forward_smplkit_events=true on update wire, got: " + body.get());
        assertTrue(fwd.forwardSmplkitEvents);
    }

    @Test
    void getForwarder_readsForwardSmplkitEventsTrue_fromWire() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":" + forwarderResourceWithSmplkitEvents("x", true) + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertTrue(fwd.forwardSmplkitEvents);
    }

    @Test
    void getForwarder_defaultsForwardSmplkitEventsFalseWhenWireOmitsIt() throws Exception {
        // forwarderResource() emits no forward_smplkit_events key — a forwarder
        // persisted before the field landed reads back as false.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":" + forwarderResource("n", "d") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertFalse(fwd.forwardSmplkitEvents);
    }

    private static String forwarderResourceWithSmplkitEvents(String name, boolean forwardSmplkitEvents) {
        return "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"" + name + "\",\"forwarder_type\":\"http\",\"enabled\":false,"
                + "\"forward_smplkit_events\":" + forwardSmplkitEvents + ","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://x\","
                + "\"success_status\":\"2xx\"},"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}";
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
    // AuditEventTypesClient
    // -----------------------------------------------------------------

    @Test
    void eventTypes_list_returnsRows() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":["
                + "{\"id\":\"invoice.created\",\"type\":\"event_type\","
                + "\"attributes\":{\"event_type\":\"invoice.created\",\"created_at\":\"2026-04-01T00:00:00Z\"}},"
                + "{\"id\":\"user.updated\",\"type\":\"event_type\","
                + "\"attributes\":{\"event_type\":\"user.updated\",\"created_at\":\"2026-04-02T00:00:00Z\"}}"
                + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":1000}}}"));
        AuditEventTypesClient ac = new AuditEventTypesClient(eventTypesApi);
        ListEventTypesInput in = new ListEventTypesInput();
        in.filterResourceType = "invoice";
        EventTypeListPage page = ac.list(in);
        assertEquals(2, page.eventTypes.size());
        assertEquals("invoice.created", page.eventTypes.get(0).id);
        assertEquals("invoice.created", page.eventTypes.get(0).eventType);
        assertNotNull(page.eventTypes.get(0).createdAt);
        assertEquals(1, page.pagination.page);
        assertEquals(1000, page.pagination.size);
    }

    @Test
    void eventTypes_list_missingMeta_returnsNullPagination() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        AuditEventTypesClient ac = new AuditEventTypesClient(eventTypesApi);
        EventTypeListPage page = ac.list(new ListEventTypesInput());
        assertEquals(0, page.eventTypes.size());
        assertNull(page.pagination);
    }

    @Test
    void eventTypes_list_filterAndPaginationForwardedToQuery() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> lastUri = new java.util.concurrent.atomic.AtomicReference<>();
        handler.set(ex -> {
            lastUri.set(ex.getRequestURI().getRawQuery());
            respondJson(ex, 200,
                    "{\"data\":[],\"meta\":{\"pagination\":{\"page\":2,\"size\":1,\"total\":3,\"total_pages\":3}}}");
        });
        AuditEventTypesClient ac = new AuditEventTypesClient(eventTypesApi);
        ListEventTypesInput in = new ListEventTypesInput();
        in.filterResourceType = "invoice";
        in.pageNumber = 2;
        in.pageSize = 1;
        in.metaTotal = true;
        EventTypeListPage page = ac.list(in);
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
    void auditEventType_constructorPopulatesFields() {
        AuditEventType eventType = new AuditEventType("invoice.created", "invoice.created",
                java.time.OffsetDateTime.parse("2026-04-01T00:00:00Z"));
        assertEquals("invoice.created", eventType.id);
        assertEquals("invoice.created", eventType.eventType);
        assertNotNull(eventType.createdAt);
    }

    @Test
    void listEventTypesInput_isInstantiable() {
        ListEventTypesInput in = new ListEventTypesInput();
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
    void eventTypeListPage_constructorPopulatesFields() {
        PageInfo info = new PageInfo(1, 1000, null, null);
        EventTypeListPage page = new EventTypeListPage(java.util.List.of(), info);
        assertEquals(0, page.eventTypes.size());
        assertSame(info, page.pagination);
    }

    // -----------------------------------------------------------------
    // TLS verification + custom CA cert per forwarder.
    // -----------------------------------------------------------------

    @Test
    void httpConfiguration_defaultsTlsVerifyTrueAndCaCertNull() {
        HttpConfiguration cfg = new HttpConfiguration("https://x");
        assertTrue(cfg.tlsVerify);
        assertNull(cfg.caCert);
    }

    @Test
    void saveForwarder_sendsTlsVerifyAndCaCert_onWire() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        handler.set(ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            body.set(new String(in));
            respondJson(ex, 201,
                    "{\"data\":" + forwarderResource("Datadog production", "prod sink") + "}");
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        HttpConfiguration cfg = new HttpConfiguration("https://siem.example.com/in");
        cfg.tlsVerify = false;
        cfg.caCert = "-----BEGIN CERTIFICATE-----\nfoo\n-----END CERTIFICATE-----";
        Forwarder fwd = fwds.newForwarder(FWD_ID, "Datadog production",
                ForwarderType.DATADOG, cfg);
        fwd.save();
        assertTrue(body.get().contains("\"tls_verify\":false"),
                "expected tls_verify on wire, got: " + body.get());
        assertTrue(body.get().contains("\"ca_cert\":\"-----BEGIN CERTIFICATE-----"),
                "expected ca_cert on wire, got: " + body.get());
    }

    @Test
    void getForwarder_readsTlsVerifyAndCaCert_fromWire() throws Exception {
        String resource = "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"n\",\"description\":\"d\","
                + "\"forwarder_type\":\"datadog\",\"enabled\":true,"
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://x\","
                + "\"headers\":[],\"success_status\":\"2xx\","
                + "\"tls_verify\":false,"
                + "\"ca_cert\":\"-----BEGIN CERTIFICATE-----\\nfoo\\n-----END CERTIFICATE-----\"},"
                + "\"data\":{},\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}";
        handler.set(ex -> respondJson(ex, 200, "{\"data\":" + resource + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertFalse(fwd.configuration.tlsVerify);
        assertTrue(fwd.configuration.caCert.contains("BEGIN CERTIFICATE"));
    }

    @Test
    void getForwarder_defaultsTlsVerifyTrueWhenWireOmitsIt() throws Exception {
        // Forwarders persisted before the field landed must read back as
        // tls_verify=true so they keep their prior secure default.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":" + forwarderResource("n", "d") + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        assertTrue(fwd.configuration.tlsVerify);
        assertNull(fwd.configuration.caCert);
    }

    // -----------------------------------------------------------------
    // AuditCategoriesClient
    // -----------------------------------------------------------------

    @Test
    void categories_list_returnsRows() throws Exception {
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":["
                + "{\"id\":\"auth\",\"type\":\"category\","
                + "\"attributes\":{\"category\":\"auth\",\"created_at\":\"2026-04-01T00:00:00Z\"}},"
                + "{\"id\":\"billing\",\"type\":\"category\","
                + "\"attributes\":{\"category\":\"billing\",\"created_at\":\"2026-04-02T00:00:00Z\"}}"
                + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":1000}}}"));
        AuditCategoriesClient cc = new AuditCategoriesClient(categoriesApi);
        ListCategoriesPage page = cc.list(new ListCategoriesInput());
        assertEquals(2, page.categories.size());
        assertEquals("auth", page.categories.get(0).id);
        assertEquals("auth", page.categories.get(0).category);
        assertNotNull(page.categories.get(0).createdAt);
        assertEquals(1, page.pagination.page);
        assertEquals(1000, page.pagination.size);
    }

    @Test
    void categories_list_missingMeta_returnsNullPagination() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        AuditCategoriesClient cc = new AuditCategoriesClient(categoriesApi);
        ListCategoriesPage page = cc.list(new ListCategoriesInput());
        assertEquals(0, page.categories.size());
        assertNull(page.pagination);
    }

    @Test
    void categories_list_pageNumberAndMetaTotalForwardedToQuery() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> lastUri = new java.util.concurrent.atomic.AtomicReference<>();
        handler.set(ex -> {
            lastUri.set(ex.getRequestURI().getRawQuery());
            respondJson(ex, 200,
                    "{\"data\":[],\"meta\":{\"pagination\":{\"page\":2,\"size\":1,\"total\":3,\"total_pages\":3}}}");
        });
        AuditCategoriesClient cc = new AuditCategoriesClient(categoriesApi);
        ListCategoriesInput in = new ListCategoriesInput();
        in.pageNumber = 2;
        in.pageSize = 1;
        in.metaTotal = true;
        ListCategoriesPage page = cc.list(in);
        assertEquals(2, page.pagination.page);
        assertEquals(3, page.pagination.total);
        assertTrue(lastUri.get().contains("page%5Bnumber%5D=2"));
    }

    @Test
    void auditCategory_constructorPopulatesFields() {
        AuditCategory c = new AuditCategory("auth", "auth",
                java.time.OffsetDateTime.parse("2026-04-01T00:00:00Z"));
        assertEquals("auth", c.id);
        assertEquals("auth", c.category);
        assertNotNull(c.createdAt);
    }

    @Test
    void listCategoriesInput_isInstantiable() {
        ListCategoriesInput in = new ListCategoriesInput();
        in.pageNumber = 2;
        in.pageSize = 10;
        in.metaTotal = true;
        assertEquals(2, in.pageNumber);
    }

    @Test
    void listCategoriesPage_constructorPopulatesFields() {
        PageInfo info = new PageInfo(1, 1000, null, null);
        ListCategoriesPage page = new ListCategoriesPage(java.util.List.of(), info);
        assertEquals(0, page.categories.size());
        assertSame(info, page.pagination);
    }

    // -----------------------------------------------------------------
    // filter[environment] forwarding (additive, comma-separated)
    // -----------------------------------------------------------------

    @Test
    void resourceTypes_list_environmentsForwardedAsCsvFilter() throws Exception {
        AtomicReference<String> lastUri = new AtomicReference<>();
        handler.set(ex -> {
            lastUri.set(ex.getRequestURI().getRawQuery());
            respondJson(ex, 200, "{\"data\":[]}");
        });
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesInput in = new ListResourceTypesInput();
        in.environments = java.util.List.of("production", "staging");
        rt.list(in);
        assertTrue(lastUri.get().contains("filter%5Benvironment%5D=production%2Cstaging"),
                "expected filter[environment]=production,staging: " + lastUri.get());
    }

    @Test
    void eventTypes_list_environmentsForwardedAsCsvFilter() throws Exception {
        AtomicReference<String> lastUri = new AtomicReference<>();
        handler.set(ex -> {
            lastUri.set(ex.getRequestURI().getRawQuery());
            respondJson(ex, 200, "{\"data\":[]}");
        });
        AuditEventTypesClient ac = new AuditEventTypesClient(eventTypesApi);
        ListEventTypesInput in = new ListEventTypesInput();
        in.environments = java.util.List.of("production", "smplkit");
        ac.list(in);
        assertTrue(lastUri.get().contains("filter%5Benvironment%5D=production%2Csmplkit"),
                "expected filter[environment]=production,smplkit: " + lastUri.get());
    }

    @Test
    void categories_list_environmentsForwardedAsCsvFilter() throws Exception {
        AtomicReference<String> lastUri = new AtomicReference<>();
        handler.set(ex -> {
            lastUri.set(ex.getRequestURI().getRawQuery());
            respondJson(ex, 200, "{\"data\":[]}");
        });
        AuditCategoriesClient cc = new AuditCategoriesClient(categoriesApi);
        ListCategoriesInput in = new ListCategoriesInput();
        in.environments = java.util.List.of("production");
        cc.list(in);
        assertTrue(lastUri.get().contains("filter%5Benvironment%5D=production"),
                "expected filter[environment]=production: " + lastUri.get());
    }

    @Test
    void joinEnvironments_nullList_returnsNull() {
        assertNull(AuditResourceTypesClient.joinEnvironments(null));
    }

    @Test
    void joinEnvironments_emptyList_returnsNull() {
        assertNull(AuditResourceTypesClient.joinEnvironments(java.util.List.of()));
    }

    @Test
    void joinEnvironments_allBlankEntries_returnsNull() {
        assertNull(AuditResourceTypesClient.joinEnvironments(
                java.util.Arrays.asList(null, "", "   ")));
    }

    @Test
    void joinEnvironments_dropsBlankEntriesAndJoinsRest() {
        assertEquals("production,staging", AuditResourceTypesClient.joinEnvironments(
                java.util.Arrays.asList("production", "", null, "staging")));
    }
}
