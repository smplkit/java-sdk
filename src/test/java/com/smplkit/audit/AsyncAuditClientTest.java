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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link AsyncAuditClient} and every async sub-client
 * ({@code events}, {@code resourceTypes}, {@code eventTypes},
 * {@code categories}, {@code forwarders}), plus the static factories and the
 * {@link java.util.concurrent.CompletionException}-wrapping error path.
 *
 * <p>Wraps a sync {@link AuditClient} pointed at an in-process JDK
 * HttpServer — no real network.</p>
 */
class AsyncAuditClientTest {

    private static final String FWD_ID = "datadog-prod";

    private HttpServer server;
    private AuditClient sync;
    private AsyncAuditClient async;
    private ExecutorService executor;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private CountDownLatch firstPostSeen;

    @BeforeEach
    void start() throws Exception {
        firstPostSeen = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", ex -> {
            if (ex.getRequestMethod().equals("POST")
                    && ex.getRequestURI().getPath().equals("/api/v1/events")) {
                firstPostSeen.countDown();
            }
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
        sync = new AuditClient(HttpClient.newHttpClient(), "sk_test", Map.of(),
                Duration.ofSeconds(5), baseUrl);
        executor = Executors.newSingleThreadExecutor();
        async = AsyncAuditClient.wrap(sync, executor);
    }

    @AfterEach
    void stop() {
        if (async != null) async.close();
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

    private static String eventResource(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"event\",\"attributes\":{"
                + "\"event_type\":\"x.created\",\"resource_type\":\"x\",\"resource_id\":\"1\","
                + "\"occurred_at\":\"2026-05-06T12:00:00Z\","
                + "\"created_at\":\"2026-05-06T12:00:01Z\","
                + "\"actor_type\":\"API_KEY\",\"actor_id\":null,\"actor_label\":\"\","
                + "\"data\":{},\"idempotency_key\":\"\"}}";
    }

    // -----------------------------------------------------------------
    // factories / accessors
    // -----------------------------------------------------------------

    @Test
    void accessors_areNonNull() {
        assertSame(sync, async.sync());
        assertSame(executor, async.executor());
        assertNotNull(async.events());
        assertNotNull(async.resourceTypes());
        assertNotNull(async.eventTypes());
        assertNotNull(async.categories());
        assertNotNull(async.forwarders());
    }

    @Test
    void create_withApiKey_usesCommonPool() {
        try (AsyncAuditClient c = AsyncAuditClient.create("sk_test")) {
            assertSame(ForkJoinPool.commonPool(), c.executor());
            assertNotNull(c.sync());
        }
    }

    @Test
    void create_default_usesCommonPool() throws Exception {
        // No-arg create() resolves credentials from the standard sources;
        // inject the api_key via the environment so the path is hermetic
        // (CI runners have no ~/.smplkit). Construction performs no network I/O.
        setEnv("SMPLKIT_API_KEY", "sk_api_test_async_create");
        try (AsyncAuditClient c = AsyncAuditClient.create()) {
            assertSame(ForkJoinPool.commonPool(), c.executor());
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void wrap_delegateOnly_usesCommonPool() {
        AuditClient standalone = AuditClient.create("sk_test");
        try (AsyncAuditClient c = AsyncAuditClient.wrap(standalone)) {
            assertSame(ForkJoinPool.commonPool(), c.executor());
            assertSame(standalone, c.sync());
        }
    }

    // -----------------------------------------------------------------
    // events
    // -----------------------------------------------------------------

    @Test
    void events_recordAndFlush() throws Exception {
        handler.set(ex -> respondJson(ex, 201, "{\"data\":" + eventResource("00000000-0000-0000-0000-000000000001") + "}"));
        async.events().record(new CreateEventInput("x.created", "x", "1"));
        async.events().flush(2_000).get(5, TimeUnit.SECONDS);
        assertTrue(firstPostSeen.await(10, TimeUnit.SECONDS), "POST never reached the server");
    }

    @Test
    void events_get_success() throws Exception {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        handler.set(ex -> respondJson(ex, 200, "{\"data\":" + eventResource(id.toString()) + "}"));
        AuditEvent ev = async.events().get(id).get(5, TimeUnit.SECONDS);
        assertEquals(id, ev.id);
        assertEquals("x.created", ev.eventType);
    }

    @Test
    void events_get_apiError_wrapsCompletionException() {
        handler.set(ex -> respondJson(ex, 404, "{}"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> async.events().get(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    @Test
    void events_list_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[],\"meta\":{\"page_size\":1}}"));
        ListEventsPage page = async.events().list(new ListEventsInput()).get(5, TimeUnit.SECONDS);
        assertNotNull(page.events);
    }

    @Test
    void events_list_apiError_wrapsCompletionException() {
        handler.set(ex -> respondJson(ex, 500, "{}"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> async.events().list(new ListEventsInput()).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    // -----------------------------------------------------------------
    // resourceTypes / eventTypes / categories
    // -----------------------------------------------------------------

    @Test
    void resourceTypes_list_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        ListResourceTypesPage page = async.resourceTypes()
                .list(new ListResourceTypesInput()).get(5, TimeUnit.SECONDS);
        assertEquals(0, page.resourceTypes.size());
    }

    @Test
    void resourceTypes_list_apiError_wrapsCompletionException() {
        handler.set(ex -> respondJson(ex, 500, "{}"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> async.resourceTypes().list(new ListResourceTypesInput()).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    @Test
    void eventTypes_list_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        EventTypeListPage page = async.eventTypes()
                .list(new ListEventTypesInput()).get(5, TimeUnit.SECONDS);
        assertEquals(0, page.eventTypes.size());
    }

    @Test
    void eventTypes_list_apiError_wrapsCompletionException() {
        handler.set(ex -> respondJson(ex, 500, "{}"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> async.eventTypes().list(new ListEventTypesInput()).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    @Test
    void categories_list_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        ListCategoriesPage page = async.categories()
                .list(new ListCategoriesInput()).get(5, TimeUnit.SECONDS);
        assertEquals(0, page.categories.size());
    }

    @Test
    void categories_list_apiError_wrapsCompletionException() {
        handler.set(ex -> respondJson(ex, 500, "{}"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> async.categories().list(new ListCategoriesInput()).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    // -----------------------------------------------------------------
    // forwarders
    // -----------------------------------------------------------------

    @Test
    void forwarders_newForwarder_fourArg_isSynchronousFactory() {
        Forwarder fwd = async.forwarders().newForwarder(FWD_ID, "n", ForwarderType.HTTP,
                new HttpConfiguration("https://x"));
        assertEquals(FWD_ID, fwd.id);
        assertNull(fwd.transformType);
    }

    @Test
    void forwarders_newForwarder_sixArg_withTransform() {
        Forwarder fwd = async.forwarders().newForwarder(FWD_ID, "n", ForwarderType.HTTP,
                new HttpConfiguration("https://x"), TransformType.JSONATA, "$");
        assertEquals(TransformType.JSONATA, fwd.transformType);
        assertEquals("$", fwd.transform);
    }

    @Test
    void forwarders_listNoArg_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[" + forwarderResource() + "]}"));
        ListForwardersPage page = async.forwarders().list().get(5, TimeUnit.SECONDS);
        assertEquals(1, page.forwarders.size());
    }

    @Test
    void forwarders_listWithInput_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        ListForwardersInput in = new ListForwardersInput();
        in.forwarderType = ForwarderType.HTTP;
        ListForwardersPage page = async.forwarders().list(in).get(5, TimeUnit.SECONDS);
        assertEquals(0, page.forwarders.size());
    }

    @Test
    void forwarders_list_apiError_wrapsCompletionException() {
        handler.set(ex -> respondJson(ex, 500, "{}"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> async.forwarders().list().get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    @Test
    void forwarders_get_success() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":" + forwarderResource() + "}"));
        Forwarder fwd = async.forwarders().get(FWD_ID).get(5, TimeUnit.SECONDS);
        assertEquals(FWD_ID, fwd.id);
    }

    @Test
    void forwarders_get_apiError_wrapsCompletionException() {
        handler.set(ex -> respondJson(ex, 404, "{}"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> async.forwarders().get(FWD_ID).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    @Test
    void forwarders_delete_success() throws Exception {
        handler.set(ex -> respondJson(ex, 204, ""));
        async.forwarders().delete(FWD_ID).get(5, TimeUnit.SECONDS);
    }

    @Test
    void forwarders_delete_apiError_wrapsCompletionException() {
        handler.set(ex -> respondJson(ex, 500, "{}"));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> async.forwarders().delete(FWD_ID).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ex.getCause());
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(env)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearEnv(String key) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(env)).remove(key);
    }
}
