package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ForwardersApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the {@link Forwarder} active-record async paths
 * ({@code saveAsync} / {@code deleteAsync}, both overloads, success and the
 * {@link CompletionException}-wrapping failure path) and the in-memory
 * per-environment override surface ({@link Forwarder#environment(String)}
 * create/reuse, {@code toString}, and the derived {@code enabled} roll-up).
 *
 * <p>Stubs the audit service via the JDK's built-in HttpServer; no real
 * network.</p>
 */
class ForwarderAsyncTest {

    private static final String FWD_ID = "datadog-prod";

    private HttpServer server;
    private ForwardersApi forwardersApi;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private ExecutorService executor;

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
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri("http://127.0.0.1:" + server.getAddress().getPort());
        apiClient.setReadTimeout(Duration.ofSeconds(5));
        forwardersApi = new ForwardersApi(apiClient);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() {
        if (executor != null) executor.shutdownNow();
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

    private static String forwarderResource() {
        return "{\"id\":\"" + FWD_ID + "\",\"type\":\"forwarder\",\"attributes\":{"
                + "\"name\":\"n\",\"forwarder_type\":\"http\",\"enabled\":false,"
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://x\","
                + "\"success_status\":\"2xx\"},"
                + "\"created_at\":\"2026-05-07T12:00:00Z\","
                + "\"updated_at\":\"2026-05-07T12:00:00Z\",\"version\":1}}";
    }

    // -----------------------------------------------------------------
    // saveAsync / deleteAsync
    // -----------------------------------------------------------------

    @Test
    void saveAsync_commonPool_completesAndApplies() throws Exception {
        handler.set(ex -> respondJson(ex, 201, "{\"data\":" + forwarderResource() + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "n", ForwarderType.HTTP,
                new HttpConfiguration("https://x"));
        fwd.saveAsync().get(5, TimeUnit.SECONDS);
        assertEquals(FWD_ID, fwd.id);
        assertNotNull(fwd.createdAt);
    }

    @Test
    void saveAsync_customExecutor_completes() throws Exception {
        handler.set(ex -> respondJson(ex, 201, "{\"data\":" + forwarderResource() + "}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "n", ForwarderType.HTTP,
                new HttpConfiguration("https://x"));
        fwd.saveAsync((Executor) executor).get(5, TimeUnit.SECONDS);
        assertEquals(FWD_ID, fwd.id);
    }

    @Test
    void saveAsync_apiError_wrapsInCompletionException() {
        handler.set(ex -> respondJson(ex, 500, "{}"));
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "n", ForwarderType.HTTP,
                new HttpConfiguration("https://x"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> fwd.saveAsync((Executor) executor).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    @Test
    void deleteAsync_commonPool_completes() throws Exception {
        // First a GET (to obtain a bound forwarder), then the DELETE returns 204.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        handler.set(ex -> {
            int n = calls.incrementAndGet();
            if (n == 1) respondJson(ex, 200, "{\"data\":" + forwarderResource() + "}");
            else respondJson(ex, 204, "");
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        fwd.deleteAsync().get(5, TimeUnit.SECONDS);
    }

    @Test
    void deleteAsync_customExecutor_completes() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        handler.set(ex -> {
            int n = calls.incrementAndGet();
            if (n == 1) respondJson(ex, 200, "{\"data\":" + forwarderResource() + "}");
            else respondJson(ex, 204, "");
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        fwd.deleteAsync((Executor) executor).get(5, TimeUnit.SECONDS);
    }

    @Test
    void deleteAsync_apiError_wrapsInCompletionException() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        handler.set(ex -> {
            int n = calls.incrementAndGet();
            if (n == 1) respondJson(ex, 200, "{\"data\":" + forwarderResource() + "}");
            else respondJson(ex, 500, "{}");
        });
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.get(FWD_ID);
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> fwd.deleteAsync((Executor) executor).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    // -----------------------------------------------------------------
    // in-memory per-environment overrides + toString
    // -----------------------------------------------------------------

    @Test
    void environment_setsLeafOverride_andCreatesEntry() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "n", ForwarderType.HTTP,
                new HttpConfiguration("https://base"));
        ForwarderEnvironment prod = fwd.environment("production");
        prod.url = "https://prod";
        ForwarderEnvironment env = fwd.environments.get("production");
        assertNotNull(env);
        assertEquals("https://prod", env.url);
        assertFalse(env.enabled);
        // base configuration is untouched by per-environment overrides
        assertEquals("https://base", fwd.configuration.url);
    }

    @Test
    void environment_reusesExistingEntry_preservingLeaves() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "n", ForwarderType.HTTP,
                new HttpConfiguration("https://base"));
        // First set a leaf override...
        fwd.environment("production").url = "https://prod";
        // ...then flip enabled on the SAME environment — reuses the existing
        // entry and preserves its leaf override.
        fwd.environment("production").enabled = true;
        ForwarderEnvironment env = fwd.environments.get("production");
        assertTrue(env.enabled);
        assertEquals("https://prod", env.url);
        assertEquals(1, fwd.environments.size());
    }

    @Test
    void toString_listsEnabledEnvironmentsSorted() {
        AuditForwarders fwds = new AuditForwarders(forwardersApi);
        Forwarder fwd = fwds.newForwarder(FWD_ID, "Datadog", ForwarderType.DATADOG,
                new HttpConfiguration("https://x"));
        fwd.environment("staging").enabled = true;
        fwd.environment("production").enabled = true;
        fwd.environment("dev").enabled = false;
        fwd.environments.put("nullentry", null);
        String s = fwd.toString();
        assertTrue(s.contains("id=" + FWD_ID));
        assertTrue(s.contains("name=Datadog"));
        // Sorted alphabetically; only enabled environments listed.
        assertTrue(s.contains("enabled_in=[production, staging]"),
                "expected sorted enabled envs, got: " + s);
    }
}
