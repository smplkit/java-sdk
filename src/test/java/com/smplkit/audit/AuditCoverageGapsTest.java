package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.CategoriesApi;
import com.smplkit.internal.generated.audit.api.EventTypesApi;
import com.smplkit.internal.generated.audit.api.EventsApi;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage-targeted tests for the audit wrapper's null/empty-fallback arms and
 * the buffer give-up log line — branches the happy-path suites in
 * {@link AuditForwardersTest}, {@link AuditClientTest}, and
 * {@link AuditBufferCoverageTest} don't reach because they always supply
 * non-null wire fields.
 *
 * <p>WS-free: the forwarder / resource-type / event-type / category clients are
 * driven through the JDK {@link HttpServer} on {@code 127.0.0.1:0}; the buffer
 * give-up path uses a mocked {@link EventsApi} plus a {@link CountDownLatch}, no
 * bare {@code Thread.sleep} as the synchronization primitive.</p>
 */
class AuditCoverageGapsTest {

    private static final String FWD_ID = "datadog-prod";

    private HttpServer server;
    private ForwardersApi forwardersApi;
    private ResourceTypesApi resourceTypesApi;
    private EventTypesApi eventTypesApi;
    private CategoriesApi categoriesApi;
    private EventsApi eventsApi;
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
        eventsApi = new EventsApi(apiClient);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private void respondJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] resp = body.getBytes();
        ex.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
        ex.sendResponseHeaders(status, resp.length);
        ex.getResponseBody().write(resp);
        ex.close();
    }

    // -----------------------------------------------------------------
    // AuditForwarders.fromResource / configurationFromGen fallback arms
    // -----------------------------------------------------------------

    @Test
    void getForwarder_emptyIdAndAbsentEnabledAndAbsentUrl_useFallbacks() throws Exception {
        // Empty id              -> AuditForwarders.fromResource line 235 ``: null`` arm
        // enabled absent        -> line 245 ``: false`` arm
        // forward_smplkit absent-> line 252 ``: false`` arm
        // configuration url absent -> configurationFromGen line 317 ``: ""`` arm
        String resource = "{\"id\":\"\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"n\",\"forwarder_type\":\"http\","
                + "\"configuration\":{\"method\":\"POST\",\"success_status\":\"2xx\"},"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}";
        handler.set(ex -> respondJson(ex, 200, "{\"data\":" + resource + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get("anything");
        assertNull(fwd.id);                       // empty wire id -> null
        assertFalse(fwd.enabled);                 // absent enabled -> false
        assertFalse(fwd.forwardSmplkitEvents);    // absent flag -> false
        assertEquals("", fwd.configuration.url);  // absent url -> ""
    }

    @Test
    void getForwarder_environmentWithAbsentEnabled_defaultsFalse() throws Exception {
        // A wire environment object whose ``enabled`` key is omitted drives the
        // ``: false`` arm of AuditForwarders.environmentsFromGen line 292.
        String resource = "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"n\",\"forwarder_type\":\"http\",\"enabled\":false,"
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://x\",\"success_status\":\"2xx\"},"
                + "\"environments\":{\"production\":{}},"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}";
        handler.set(ex -> respondJson(ex, 200, "{\"data\":" + resource + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        ForwarderEnvironment prod = fwd.environments.get("production");
        assertNotNull(prod);
        assertFalse(prod.enabled);          // absent enabled -> false
        assertNull(prod.configuration);     // absent configuration -> null
    }

    // -----------------------------------------------------------------
    // ResourceType / EventType / Category fromResource fallback arms
    // -----------------------------------------------------------------

    @Test
    void resourceTypes_emptyIdAndAbsentAttributes_fallBackToIdAndNullCreatedAt() throws Exception {
        // id ""               -> AuditResourceTypesClient.fromResource line 53 ``: ""``
        // attributes absent   -> line 55 ``: id`` (resourceType falls back to id)
        //                     -> line 57 ``: null`` (createdAt null when attributes absent)
        // second row: attributes present but resource_type absent -> still falls back to id.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":["
                + "{\"id\":\"\",\"type\":\"resource_type\"},"
                + "{\"id\":\"user\",\"type\":\"resource_type\",\"attributes\":{}}"
                + "]}"));
        AuditResourceTypesClient rt = new AuditResourceTypesClient(resourceTypesApi);
        ListResourceTypesPage page = rt.list(new ListResourceTypesInput());
        assertEquals(2, page.resourceTypes.size());
        // Row 0: empty id, no attributes block.
        assertEquals("", page.resourceTypes.get(0).id);
        assertEquals("", page.resourceTypes.get(0).resourceType); // resource_type -> id ""
        assertNull(page.resourceTypes.get(0).createdAt);
        // Row 1: attributes present but resource_type omitted -> falls back to id.
        assertEquals("user", page.resourceTypes.get(1).id);
        assertEquals("user", page.resourceTypes.get(1).resourceType);
        assertNull(page.resourceTypes.get(1).createdAt);
    }

    @Test
    void eventTypes_emptyIdAndAbsentAttributes_fallBackToIdAndNullCreatedAt() throws Exception {
        // Mirrors AuditEventTypesClient.fromResource lines 53 / 55 / 57.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":["
                + "{\"id\":\"\",\"type\":\"event_type\"},"
                + "{\"id\":\"user.updated\",\"type\":\"event_type\",\"attributes\":{}}"
                + "]}"));
        AuditEventTypesClient ac = new AuditEventTypesClient(eventTypesApi);
        EventTypeListPage page = ac.list(new ListEventTypesInput());
        assertEquals(2, page.eventTypes.size());
        assertEquals("", page.eventTypes.get(0).id);
        assertEquals("", page.eventTypes.get(0).eventType);
        assertNull(page.eventTypes.get(0).createdAt);
        assertEquals("user.updated", page.eventTypes.get(1).id);
        assertEquals("user.updated", page.eventTypes.get(1).eventType);
        assertNull(page.eventTypes.get(1).createdAt);
    }

    @Test
    void categories_emptyIdAndAbsentAttributes_fallBackToIdAndNullCreatedAt() throws Exception {
        // Mirrors AuditCategoriesClient.fromResource lines 51 / 53 / 55.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":["
                + "{\"id\":\"\",\"type\":\"category\"},"
                + "{\"id\":\"auth\",\"type\":\"category\",\"attributes\":{}}"
                + "]}"));
        AuditCategoriesClient cc = new AuditCategoriesClient(categoriesApi);
        ListCategoriesPage page = cc.list(new ListCategoriesInput());
        assertEquals(2, page.categories.size());
        assertEquals("", page.categories.get(0).id);
        assertEquals("", page.categories.get(0).category);
        assertNull(page.categories.get(0).createdAt);
        assertEquals("auth", page.categories.get(1).id);
        assertEquals("auth", page.categories.get(1).category);
        assertNull(page.categories.get(1).createdAt);
    }

    // -----------------------------------------------------------------
    // AuditEvents.fromResource do_not_forward absent -> false arm
    // -----------------------------------------------------------------

    @Test
    void getEvent_absentDoNotForward_defaultsFalse() throws Exception {
        // get() with a response that omits do_not_forward drives the ``: false``
        // arm of AuditEvents.fromResource line 190.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":{\"id\":\"11111111-2222-3333-4444-555555555555\",\"type\":\"event\","
                + "\"attributes\":{\"event_type\":\"u.created\",\"resource_type\":\"u\","
                + "\"resource_id\":\"1\",\"occurred_at\":\"2026-05-06T12:00:00Z\","
                + "\"created_at\":\"2026-05-06T12:00:01Z\"}}}"));
        AuditEvents events = new AuditEvents(eventsApi);
        AuditEvent ev = events.get(java.util.UUID.fromString("11111111-2222-3333-4444-555555555555"));
        assertFalse(ev.doNotForward);
        events.close();
    }

    // -----------------------------------------------------------------
    // AuditEventBuffer give-up log line (handleOutcome line 237)
    // -----------------------------------------------------------------

    @Test
    void buffer_givesUp_logsAfterMaxAttempts_deterministic() throws Exception {
        // Mock the EventsApi so every POST throws a transient 503 ApiException.
        // The buffer retries up to MAX_ATTEMPTS, then logs the give-up message
        // (handleOutcome line 237). We attach a logging Handler to the
        // ``smplkit.audit`` logger and count down a latch when the give-up
        // record is published, so the assertion is on the log line itself and
        // does not rely on a bare Thread.sleep as a synchronization primitive.
        EventsApi mockApi = mock(EventsApi.class);
        when(mockApi.recordEvent(any(), nullable(String.class)))
                .thenThrow(new ApiException(503, "service unavailable"));

        CountDownLatch gaveUp = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();
        Logger auditLog = Logger.getLogger("smplkit.audit");
        Handler probe = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING
                        && record.getMessage() != null
                        && record.getMessage().contains("gave up after")) {
                    message.set(record.getMessage());
                    gaveUp.countDown();
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        auditLog.addHandler(probe);
        AuditEventBuffer buf = new AuditEventBuffer(mockApi);
        try {
            buf.enqueue(new com.smplkit.internal.generated.audit.model.EventRequest()
                    .data(new com.smplkit.internal.generated.audit.model.EventResource()
                            .id("").type("event")
                            .attributes(new com.smplkit.internal.generated.audit.model.Event()
                                    .eventType("x.created").resourceType("x").resourceId("1"))),
                    null);
            // The worker only retries when it is signalled; flush() signals every
            // 50ms, driving the backed-off retries through to give-up (which drops
            // the item, leaving the queue idle so flush returns). 5 attempts with
            // 250/500/1000/2000ms backoff ~= 3.75s of real waiting; allow 15s.
            buf.flush(15_000);
            assertTrue(gaveUp.await(15, TimeUnit.SECONDS),
                    "buffer never logged the give-up message");
            // error != null arm of line 237 -> the ApiException message is interpolated.
            assertTrue(message.get().contains("service unavailable"),
                    "expected the ApiException message in the give-up log, got: " + message.get());
            assertTrue(message.get().contains("status=503"),
                    "expected status=503 in the give-up log, got: " + message.get());
        } finally {
            auditLog.removeHandler(probe);
            buf.close();
        }
    }
}
