package com.smplkit.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smplkit.internal.generated.jobs.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the reusable retry-policy surface — {@link RetryPoliciesClient}
 * (sync) and {@link AsyncRetryPoliciesClient} (async), the {@link RetryPolicy}
 * active record, the {@link RetryOn} value type, and the {@link Backoff} /
 * {@link RetryReason} enums.
 *
 * <p>Stubs the jobs service via the JDK's built-in {@link HttpServer} bound to
 * {@code 127.0.0.1:0}; no real network. The wrapper layer here must reach 100%
 * line coverage to satisfy the SDK CI gate.</p>
 */
class RetryPoliciesClientTest {

    private static final String POLICY_ID = "my-policy";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private ExecutorService executor;

    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

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
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private JobsClient client() {
        return new JobsClient("sk_test", Map.of(), Duration.ofSeconds(5), baseUrl);
    }

    private AsyncJobsClient asyncClient() {
        return AsyncJobsClient.wrap(client(), executor);
    }

    private void capture(HttpExchange ex) throws IOException {
        String rawQuery = ex.getRequestURI().getRawQuery();
        lastQuery.set(rawQuery == null ? null : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8));
        byte[] b = ex.getRequestBody().readAllBytes();
        lastBody.set(b.length == 0 ? null : new String(b, StandardCharsets.UTF_8));
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

    // A retry-policy resource. ``maxDelay`` is either a JSON number or the
    // literal "null" so both the present and absent max_delay_seconds parse
    // paths are exercised.
    private static String policyResource(String id, boolean created, int version, String maxDelay) {
        String ts = created ? "\"2026-06-04T00:00:00Z\"" : "null";
        return "{\"id\":\"" + id + "\",\"type\":\"retry_policy\",\"attributes\":{"
                + "\"name\":\"My Policy\",\"max_retries\":5,\"backoff\":\"exponential\","
                + "\"delay_seconds\":2,\"max_delay_seconds\":" + maxDelay + ","
                + "\"retry_on\":{\"statuses\":[429,503],\"reasons\":[\"TIMEOUT\"]},"
                + "\"created_at\":" + ts + ",\"updated_at\":" + ts + ","
                + "\"deleted_at\":null,\"version\":" + version + "}}";
    }

    private void installPolicyHandler() {
        handler.set(ex -> {
            capture(ex);
            String m = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/v1/retry-policies") && m.equals("POST")) {
                respondJson(ex, 201, "{\"data\":" + policyResource(POLICY_ID, true, 1, "60") + "}");
            } else if (path.equals("/api/v1/retry-policies") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":[" + policyResource("a", true, 1, "60")
                        + "," + policyResource("b", true, 1, "null")
                        + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":50}}}");
            } else if (path.startsWith("/api/v1/retry-policies/") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":" + policyResource(POLICY_ID, true, 1, "60") + "}");
            } else if (path.startsWith("/api/v1/retry-policies/") && m.equals("PUT")) {
                respondJson(ex, 200, "{\"data\":" + policyResource(POLICY_ID, true, 2, "60") + "}");
            } else if (path.startsWith("/api/v1/retry-policies/") && m.equals("DELETE")) {
                respondJson(ex, 204, "");
            } else {
                respondJson(ex, 500, "{}");
            }
        });
    }

    private void installErrorHandler() {
        handler.set(ex -> {
            int code = ex.getRequestMethod().equals("GET") ? 404 : 409;
            respondJson(ex, code, "{\"errors\":[{\"detail\":\"x\"}]}");
        });
    }

    private static <T> T await(CompletableFuture<T> f) throws Exception {
        return f.get(5, TimeUnit.SECONDS);
    }

    private static void assertApiFailure(CompletableFuture<?> f) {
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> f.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ee.getCause());
        CompletionException ce = assertThrows(CompletionException.class, f::join);
        assertInstanceOf(ApiException.class, ce.getCause());
    }

    private JsonNode attrs() throws Exception {
        return MAPPER.readTree(lastBody.get()).path("data").path("attributes");
    }

    // -----------------------------------------------------------------
    // Active-record save / build-to-wire
    // -----------------------------------------------------------------

    @Test
    void createThenUpdateViaSave() throws Exception {
        installPolicyHandler();
        try (JobsClient c = client()) {
            RetryPolicy p = c.retryPolicies.new_(POLICY_ID, "My Policy", 5, Backoff.EXPONENTIAL, 2,
                    60, new RetryOn(List.of(429, 503), List.of(RetryReason.TIMEOUT)));
            assertNull(p.createdAt);
            p.save();   // create (createdAt null -> POST)
            assertNotNull(p.createdAt);
            assertEquals(1, p.version);
            assertEquals(Backoff.EXPONENTIAL, p.backoff);
            assertEquals(60, p.maxDelaySeconds);
            assertEquals(List.of(429, 503), p.retryOn.statuses);
            assertEquals(List.of(RetryReason.TIMEOUT), p.retryOn.reasons);
            // create body: every attribute, retry_on, and max_delay_seconds
            JsonNode a = attrs();
            assertEquals("My Policy", a.path("name").asText());
            assertEquals(5, a.path("max_retries").asInt());
            assertEquals("exponential", a.path("backoff").asText());
            assertEquals(2, a.path("delay_seconds").asInt());
            assertEquals(60, a.path("max_delay_seconds").asInt());
            assertEquals(429, a.path("retry_on").path("statuses").get(0).asInt());
            assertEquals(503, a.path("retry_on").path("statuses").get(1).asInt());
            assertEquals("TIMEOUT", a.path("retry_on").path("reasons").get(0).asText());
            p.name = "renamed";
            p.save();   // update (createdAt set -> PUT)
            assertEquals(2, p.version);
        }
    }

    @Test
    void createWithoutMaxDelay_omitsIt_andRetryOnAlwaysPresent() throws Exception {
        installPolicyHandler();
        try (JobsClient c = client()) {
            RetryPolicy p = c.retryPolicies.new_(POLICY_ID, "Fixed", 3, Backoff.FIXED, 5);
            p.save();
            JsonNode a = attrs();
            assertFalse(a.has("max_delay_seconds"), "unset max_delay_seconds must be omitted");
            // retry_on is always sent — an empty RetryOn serializes as empty lists
            assertTrue(a.path("retry_on").path("statuses").isArray());
            assertEquals(0, a.path("retry_on").path("statuses").size());
            assertEquals(0, a.path("retry_on").path("reasons").size());
        }
    }

    @Test
    void new_withNullRetryOn_defaultsEmpty() {
        try (JobsClient c = client()) {
            RetryPolicy p = c.retryPolicies.new_(POLICY_ID, "P", 3, Backoff.FIXED, 5, null, null);
            assertNull(p.maxDelaySeconds);
            assertNotNull(p.retryOn);
            assertTrue(p.retryOn.statuses.isEmpty());
            assertTrue(p.retryOn.reasons.isEmpty());
        }
    }

    @Test
    void listGetDelete() throws Exception {
        installPolicyHandler();
        try (JobsClient c = client()) {
            assertEquals(2, c.retryPolicies.list().size());
            ListRetryPoliciesInput in = new ListRetryPoliciesInput();
            in.name = "server";
            in.pageNumber = 1;
            in.pageSize = 10;
            List<RetryPolicy> listed = c.retryPolicies.list(in);
            assertEquals(2, listed.size());
            // one listed policy carries a null max_delay_seconds (fixed), one a value
            assertTrue(listed.stream().anyMatch(p -> p.maxDelaySeconds == null));
            assertTrue(listed.stream().anyMatch(p -> p.maxDelaySeconds != null));
            assertTrue(lastQuery.get().contains("filter[name]=server"));
            assertTrue(lastQuery.get().contains("page[number]=1"));
            assertTrue(lastQuery.get().contains("page[size]=10"));
            RetryPolicy got = c.retryPolicies.get(POLICY_ID);
            assertEquals(POLICY_ID, got.id);
            assertEquals("My Policy", got.name);
            assertEquals(List.of(429, 503), got.retryOn.statuses);
            assertEquals(List.of(RetryReason.TIMEOUT), got.retryOn.reasons);
            c.retryPolicies.delete(POLICY_ID);
            got.delete();   // active-record delete bound to the client
        }
    }

    // -----------------------------------------------------------------
    // RetryOn conversions
    // -----------------------------------------------------------------

    @Test
    void retryOnConversions_roundTripAndEmpty() {
        // toGen: empty RetryOn -> empty lists
        com.smplkit.internal.generated.jobs.model.RetryOn genEmpty =
                JobsConversions.retryOnToGen(new RetryOn());
        assertTrue(genEmpty.getStatuses().isEmpty());
        assertTrue(genEmpty.getReasons().isEmpty());
        // toGen: populated, then fromGen round-trips
        com.smplkit.internal.generated.jobs.model.RetryOn gen = JobsConversions.retryOnToGen(
                new RetryOn(List.of(429, 503), List.of(RetryReason.TIMEOUT, RetryReason.CONNECTION_ERROR)));
        assertEquals(List.of(429, 503), gen.getStatuses());
        assertEquals(2, gen.getReasons().size());
        RetryOn back = JobsConversions.retryOnFromGen(gen);
        assertEquals(List.of(429, 503), back.statuses);
        assertEquals(List.of(RetryReason.TIMEOUT, RetryReason.CONNECTION_ERROR), back.reasons);
        // fromGen: null -> empty
        RetryOn fromNull = JobsConversions.retryOnFromGen(null);
        assertTrue(fromNull.statuses.isEmpty());
        assertTrue(fromNull.reasons.isEmpty());
        // RetryOn(null, null) constructor defaults to empty lists
        RetryOn nulls = new RetryOn(null, null);
        assertTrue(nulls.statuses.isEmpty());
        assertTrue(nulls.reasons.isEmpty());
    }

    // -----------------------------------------------------------------
    // Async surface
    // -----------------------------------------------------------------

    @Test
    void asyncSurface_happyPath() throws Exception {
        installPolicyHandler();
        try (AsyncJobsClient c = asyncClient()) {
            RetryPolicy p = c.retryPolicies.new_(POLICY_ID, "My Policy", 5, Backoff.EXPONENTIAL, 2,
                    60, new RetryOn(List.of(429), List.of(RetryReason.TIMEOUT)));
            await(p.saveAsync(executor));
            assertEquals(1, p.version);
            assertEquals(2, await(c.retryPolicies.list()).size());
            assertEquals(2, await(c.retryPolicies.list(new ListRetryPoliciesInput())).size());
            assertEquals(POLICY_ID, await(c.retryPolicies.get(POLICY_ID)).id);
            // new_(5-arg) on the async client
            RetryPolicy simple = c.retryPolicies.new_("simple", "S", 1, Backoff.FIXED, 1);
            assertEquals("simple", simple.id);
            await(p.deleteAsync(executor));
            await(c.retryPolicies.delete(POLICY_ID));
        }
    }

    @Test
    void asyncSurface_commonPoolAndErrors() throws Exception {
        installPolicyHandler();
        try (JobsClient c = client()) {
            // common-pool saveAsync / deleteAsync
            RetryPolicy p = c.retryPolicies.new_(POLICY_ID, "P", 1, Backoff.FIXED, 1);
            await(p.saveAsync());
            await(p.deleteAsync());
        }
        installErrorHandler();
        try (AsyncJobsClient c = asyncClient()) {
            assertApiFailure(c.retryPolicies.list());
            assertApiFailure(c.retryPolicies.list(new ListRetryPoliciesInput()));
            assertApiFailure(c.retryPolicies.get("missing"));
            assertApiFailure(c.retryPolicies.delete("missing"));
            RetryPolicy dup = c.retryPolicies.new_("dup", "D", 1, Backoff.FIXED, 1);
            assertApiFailure(dup.saveAsync(executor));
            assertApiFailure(dup.deleteAsync(executor));
        }
    }

    // -----------------------------------------------------------------
    // Error mapping + guards
    // -----------------------------------------------------------------

    @Test
    void errorMapping() {
        installErrorHandler();
        try (JobsClient c = client()) {
            ApiException notFound = assertThrows(ApiException.class, () -> c.retryPolicies.get("missing"));
            assertEquals(404, notFound.getCode());
            RetryPolicy dup = c.retryPolicies.new_("dup", "D", 1, Backoff.FIXED, 1);
            ApiException conflict = assertThrows(ApiException.class, dup::save);
            assertEquals(409, conflict.getCode());
        }
    }

    @Test
    void guards_withoutClientOrId_throw() throws Exception {
        // No client at all.
        RetryPolicy orphan = new RetryPolicy(null, "k", "n", 1, Backoff.FIXED, 1);
        assertThrows(IllegalStateException.class, orphan::save);
        assertThrows(IllegalStateException.class, orphan::delete);
        // Bound client but null id, on both create and delete paths.
        try (JobsClient c = client()) {
            RetryPolicy p = c.retryPolicies.new_("k", "n", 1, Backoff.FIXED, 1);
            p.id = null;
            assertThrows(IllegalStateException.class, p::save);     // create path, id null
            assertThrows(IllegalStateException.class, p::delete);   // delete path, id null
        }
        // Bound client, existing policy, null id on the update path.
        installPolicyHandler();
        try (JobsClient c = client()) {
            RetryPolicy p = c.retryPolicies.get(POLICY_ID);   // createdAt set -> next save updates
            assertNotNull(p.createdAt);
            p.id = null;
            assertThrows(IllegalStateException.class, p::save);     // update path, id null
        }
    }

    // -----------------------------------------------------------------
    // Enums, models, inputs
    // -----------------------------------------------------------------

    @Test
    void enumsAndModelsAndInputs() {
        for (Backoff b : Backoff.values()) {
            assertEquals(b, Backoff.fromValue(b.getValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> Backoff.fromValue("nope"));
        for (RetryReason r : RetryReason.values()) {
            assertEquals(r, RetryReason.fromValue(r.getValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> RetryReason.fromValue("nope"));

        RetryPolicy p = new RetryPolicy(null, "id1", "name1", 3, Backoff.FIXED, 5);
        assertTrue(p.toString().contains("RetryPolicy("));
        assertEquals("id1", p.id);
        assertEquals(3, p.maxRetries);

        ListRetryPoliciesInput in = new ListRetryPoliciesInput();
        assertNull(in.name);
        assertNull(in.pageNumber);
        assertNull(in.pageSize);

        RetryOn empty = new RetryOn();
        assertTrue(empty.statuses.isEmpty());
        assertTrue(empty.reasons.isEmpty());
    }
}
