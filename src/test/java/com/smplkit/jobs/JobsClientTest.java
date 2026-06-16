package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
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
 * Tests for the fused Smpl Jobs surface — {@link JobsClient} (sync) and
 * {@link AsyncJobsClient} (async), plus {@link RunsClient} /
 * {@link AsyncRunsClient}.
 *
 * <p>Stubs the jobs service via the JDK's built-in {@link HttpServer} bound to
 * {@code 127.0.0.1:0}; no real network. The clients are built through the
 * wired constructor {@code JobsClient(apiKey, extraHeaders, timeout, baseUrl)}
 * pointed at the loopback stub. The wrapper layer here must reach 100% line
 * coverage to satisfy the SDK CI gate.</p>
 */
class JobsClientTest {

    private static final String JOB_ID = "my-job";
    private static final String RUN_ID = "8f2b1c4a-0000-4a1b-9c3d-1e2f3a4b5c6d";

    private HttpServer server;
    private String baseUrl;
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
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private JobsClient client() {
        // Wired constructor — used in production by SmplClient. ownsTransport is
        // false, so close() is a no-op (exercised in builder test).
        return new JobsClient("sk_test", Map.of(), Duration.ofSeconds(5), baseUrl);
    }

    private AsyncJobsClient asyncClient() {
        return AsyncJobsClient.wrap(client(), executor);
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

    private static String jobResource(String id, boolean created, int version, boolean enabled) {
        String ts = created ? "\"2026-06-04T00:00:00Z\"" : "null";
        return "{\"id\":\"" + id + "\",\"type\":\"job\",\"attributes\":{"
                + "\"name\":\"My Job\",\"description\":\"does a thing\","
                + "\"enabled\":" + enabled + ",\"type\":\"http\","
                + "\"schedule\":\"0 * * * *\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://api.example.com/hook\","
                + "\"headers\":[{\"name\":\"X-Api-Key\",\"value\":\"secret\"}],"
                + "\"body\":\"{}\",\"success_status\":\"2xx\",\"timeout\":30,"
                + "\"tls_verify\":true,\"ca_cert\":null},"
                + "\"concurrency_policy\":\"ALLOW\","
                + "\"next_run_at\":\"2026-06-05T00:00:00Z\","
                + "\"created_at\":" + ts + ",\"updated_at\":" + ts + ","
                + "\"deleted_at\":null,\"version\":" + version + "}}";
    }

    private static String runResource(String status, String trigger, String rerunOf) {
        String ro = rerunOf == null ? "null" : "\"" + rerunOf + "\"";
        return "{\"id\":\"" + RUN_ID + "\",\"type\":\"run\",\"attributes\":{"
                + "\"job\":\"" + JOB_ID + "\",\"job_version\":1,\"trigger\":\"" + trigger + "\","
                + "\"rerun_of\":" + ro + ",\"scheduled_for\":\"2026-06-05T00:00:00Z\","
                + "\"status\":\"" + status + "\","
                + "\"started_at\":\"2026-06-05T00:00:00Z\",\"finished_at\":\"2026-06-05T00:00:01Z\","
                + "\"pending_duration_ms\":100,\"run_duration_ms\":300,\"total_duration_ms\":400,"
                + "\"failure_reason\":null,\"error\":null,"
                + "\"request\":{\"method\":\"POST\"},\"result\":{\"status\":200},"
                + "\"created_at\":\"2026-06-05T00:00:00Z\"}}";
    }

    private static final String USAGE =
            "{\"id\":\"current\",\"type\":\"usage\",\"attributes\":{"
                    + "\"period\":\"2026-06\",\"runs_used\":12,\"runs_included\":3000,"
                    + "\"active_jobs\":2,\"active_jobs_limit\":10}}";

    /** A handler implementing the full jobs surface, mirroring the python reference handler. */
    private void installFullHandler() {
        handler.set(ex -> {
            String m = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/v1/jobs") && m.equals("POST")) {
                respondJson(ex, 201, "{\"data\":" + jobResource(JOB_ID, true, 1, false) + "}");
            } else if (path.equals("/api/v1/jobs") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":[" + jobResource("a", true, 1, true)
                        + "," + jobResource("b", true, 1, true)
                        + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":50}}}");
            } else if (path.endsWith("/actions/run")) {
                respondJson(ex, 200, "{\"data\":" + runResource("PENDING", "MANUAL", null) + "}");
            } else if (path.startsWith("/api/v1/jobs/") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":" + jobResource(JOB_ID, true, 1, true) + "}");
            } else if (path.startsWith("/api/v1/jobs/") && m.equals("PUT")) {
                respondJson(ex, 200, "{\"data\":" + jobResource(JOB_ID, true, 2, true) + "}");
            } else if (path.startsWith("/api/v1/jobs/") && m.equals("DELETE")) {
                respondJson(ex, 204, "");
            } else if (path.equals("/api/v1/usage")) {
                respondJson(ex, 200, "{\"data\":" + USAGE + "}");
            } else if (path.equals("/api/v1/runs") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":[" + runResource("SUCCEEDED", "SCHEDULE", null)
                        + "],\"meta\":{\"page_size\":50}}");
            } else if (path.endsWith("/actions/cancel")) {
                respondJson(ex, 200, "{\"data\":" + runResource("CANCELED", "RERUN", RUN_ID) + "}");
            } else if (path.endsWith("/actions/rerun")) {
                respondJson(ex, 200, "{\"data\":" + runResource("PENDING", "RERUN", RUN_ID) + "}");
            } else if (path.startsWith("/api/v1/runs/") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":" + runResource("SUCCEEDED", "SCHEDULE", null) + "}");
            } else {
                respondJson(ex, 500, "{}");
            }
        });
    }

    private static <T> T await(CompletableFuture<T> f) throws Exception {
        return f.get(5, TimeUnit.SECONDS);
    }

    // -----------------------------------------------------------------
    // new_ overloads — defaults (4-arg) vs explicit (7-arg)
    // -----------------------------------------------------------------

    @Test
    void newJob_fourArg_appliesDefaults() {
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            assertEquals(JOB_ID, job.id);
            assertEquals("My Job", job.name);
            assertEquals("0 * * * *", job.schedule);
            assertNull(job.description);
            assertTrue(job.enabled);
            assertEquals("ALLOW", job.concurrencyPolicy);
            assertNull(job.createdAt);
        }
    }

    @Test
    void newJob_sevenArg_setsEveryField() {
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"),
                    "a description", false, "ALLOW");
            assertEquals("a description", job.description);
            assertFalse(job.enabled);
            assertEquals("ALLOW", job.concurrencyPolicy);
        }
    }

    @Test
    void asyncNewJob_fourArg_appliesDefaults() {
        try (AsyncJobsClient c = asyncClient()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            assertNull(job.description);
            assertTrue(job.enabled);
            assertEquals("ALLOW", job.concurrencyPolicy);
        }
    }

    @Test
    void asyncNewJob_sevenArg_setsEveryField() {
        try (AsyncJobsClient c = asyncClient()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"),
                    "desc", false, "ALLOW");
            assertEquals("desc", job.description);
            assertFalse(job.enabled);
            assertEquals("ALLOW", job.concurrencyPolicy);
        }
    }

    // -----------------------------------------------------------------
    // Active-record save / delete via Job (sync)
    // -----------------------------------------------------------------

    @Test
    void createThenUpdateViaSave() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            HttpConfig cfg = new HttpConfig(HttpMethod.POST, "https://api.example.com/hook",
                    List.of(new HttpHeader("X-Api-Key", "secret")));
            cfg.body = "{}";
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", cfg);
            job.description = "does a thing";
            assertNull(job.createdAt);
            job.save();
            assertNotNull(job.createdAt);
            assertEquals(1, job.version);
            // round-trip preserves the fetched configuration
            assertEquals(HttpMethod.POST, job.configuration.method);
            assertEquals(1, job.configuration.headers.size());
            assertEquals("X-Api-Key", job.configuration.headers.get(0).name);
            assertNotNull(job.nextRunAt);
            assertEquals("http", job.type);
            job.name = "renamed";
            job.save();
            assertEquals(2, job.version);
        }
    }

    @Test
    void getListDelete() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            assertEquals(HttpMethod.POST, c.get(JOB_ID).configuration.method);
            assertEquals(2, c.list().size());
            ListJobsInput in = new ListJobsInput();
            in.enabled = true;
            in.recurring = true;
            in.pageNumber = 1;
            in.pageSize = 10;
            assertEquals(2, c.list(in).size());
            c.delete(JOB_ID);
            c.get(JOB_ID).delete();  // active-record delete with bound client
        }
    }

    @Test
    void runRunsUsage() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            assertEquals("MANUAL", c.run(JOB_ID).trigger);
            assertEquals(1, c.runs.list().size());
            ListRunsInput in = new ListRunsInput();
            in.job = JOB_ID;
            in.pageSize = 2;
            in.after = "cur";
            assertEquals(1, c.runs.list(in).size());
            Run got = c.runs.get(RUN_ID);
            assertEquals("SUCCEEDED", got.status);
            assertEquals(1, got.jobVersion);
            assertEquals(400, got.totalDurationMs);
            assertEquals(100, got.pendingDurationMs);
            assertEquals(300, got.runDurationMs);
            assertEquals("POST", got.request.get("method"));
            assertEquals(200, got.result.get("status"));
            assertNotNull(got.scheduledFor);
            assertNotNull(got.startedAt);
            assertNotNull(got.finishedAt);
            assertNotNull(got.createdAt);
            Run canceled = c.runs.cancel(RUN_ID);
            assertEquals("CANCELED", canceled.status);
            assertEquals(RUN_ID, canceled.rerunOf);
            assertEquals("RERUN", c.runs.rerun(RUN_ID).trigger);
            Usage usage = c.usage();
            assertEquals(12, usage.runsUsed);
            assertEquals(3000, usage.runsIncluded);
            assertEquals(2, usage.activeJobs);
            assertEquals(10, usage.activeJobsLimit);
            assertEquals("2026-06", usage.period);
        }
    }

    // -----------------------------------------------------------------
    // Async surface — every CompletableFuture method
    // -----------------------------------------------------------------

    @Test
    void asyncSurface_happyPath() throws Exception {
        installFullHandler();
        try (AsyncJobsClient c = asyncClient()) {
            assertSame(c.sync(), c.sync());
            assertNotNull(c.sync());
            assertSame(executor, c.executor());

            assertEquals(2, await(c.list()).size());
            ListJobsInput in = new ListJobsInput();
            in.pageNumber = 1;
            assertEquals(2, await(c.list(in)).size());
            assertEquals(JOB_ID, await(c.get(JOB_ID)).id);
            assertEquals("MANUAL", await(c.run(JOB_ID)).trigger);
            assertEquals(12, await(c.usage()).runsUsed);
            await(c.delete(JOB_ID)); // CompletableFuture<Void>

            assertEquals(1, await(c.runs.list()).size());
            ListRunsInput rin = new ListRunsInput();
            rin.job = JOB_ID;
            assertEquals(1, await(c.runs.list(rin)).size());
            assertEquals("SUCCEEDED", await(c.runs.get(RUN_ID)).status);
            assertEquals("CANCELED", await(c.runs.cancel(RUN_ID)).status);
            assertEquals("PENDING", await(c.runs.rerun(RUN_ID)).status);
        }
    }

    @Test
    void asyncSurface_propagatesApiExceptionAsCompletionException() {
        // Every error path returns 404/409; assert the future completes
        // exceptionally with a CompletionException wrapping ApiException.
        installErrorHandler();
        try (AsyncJobsClient c = asyncClient()) {
            assertApiFailure(c.list());
            assertApiFailure(c.list(new ListJobsInput()));
            assertApiFailure(c.get("missing"));
            assertApiFailure(c.run("missing"));
            assertApiFailure(c.usage());
            assertApiFailure(c.delete("missing"));
            assertApiFailure(c.runs.list());
            assertApiFailure(c.runs.list(new ListRunsInput()));
            assertApiFailure(c.runs.get(RUN_ID));
            assertApiFailure(c.runs.cancel(RUN_ID));
            assertApiFailure(c.runs.rerun(RUN_ID));
        }
    }

    private void installErrorHandler() {
        handler.set(ex -> {
            int code = ex.getRequestMethod().equals("GET") ? 404 : 409;
            respondJson(ex, code, "{\"errors\":[{\"detail\":\"x\"}]}");
        });
    }

    private static void assertApiFailure(CompletableFuture<?> f) {
        // The async methods complete the future exceptionally with a
        // CompletionException wrapping ApiException. CompletableFuture.get()
        // unwraps that CompletionException layer, so ExecutionException.getCause()
        // is the ApiException itself; join() surfaces the CompletionException.
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> f.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ee.getCause());
        CompletionException ce = assertThrows(CompletionException.class, f::join);
        assertInstanceOf(ApiException.class, ce.getCause());
    }

    // -----------------------------------------------------------------
    // Job.saveAsync / deleteAsync (sync model's async helpers)
    // -----------------------------------------------------------------

    @Test
    void jobSaveAsync_andDeleteAsync_customExecutor() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            await(job.saveAsync(executor));
            assertEquals(1, job.version);
            await(job.deleteAsync(executor));
        }
    }

    @Test
    void jobSaveAsync_andDeleteAsync_commonPool() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            await(job.saveAsync());
            assertEquals(1, job.version);
            await(job.deleteAsync());
        }
    }

    @Test
    void jobSaveAsync_propagatesApiException() {
        installErrorHandler();
        try (JobsClient c = client()) {
            Job job = c.new_("dup", "D", "now", new HttpConfig("https://x"));
            assertApiFailure(job.saveAsync(executor));
        }
    }

    @Test
    void jobDeleteAsync_propagatesApiException() {
        installErrorHandler();
        try (JobsClient c = client()) {
            // A fetched (created) job, then mutate so save would PUT; but here we
            // delete a bound id against the 409 handler to drive the failure path.
            Job job = c.new_(JOB_ID, "D", "now", new HttpConfig("https://x"));
            assertApiFailure(job.deleteAsync(executor));
        }
    }

    // -----------------------------------------------------------------
    // Error mapping (sync)
    // -----------------------------------------------------------------

    @Test
    void errorMapping() {
        installErrorHandler();
        try (JobsClient c = client()) {
            ApiException notFound = assertThrows(ApiException.class, () -> c.get("missing"));
            assertEquals(404, notFound.getCode());
            Job dup = c.new_("dup", "D", "now", new HttpConfig("https://x"));
            ApiException conflict = assertThrows(ApiException.class, dup::save);
            assertEquals(409, conflict.getCode());
        }
    }

    // -----------------------------------------------------------------
    // Guards / edge cases
    // -----------------------------------------------------------------

    @Test
    void save_withoutClient_throws() {
        Job job = new Job(null, "k", "x", "now", new HttpConfig("https://x"));
        assertThrows(IllegalStateException.class, job::save);
    }

    @Test
    void delete_withoutClient_throws() {
        Job job = new Job(null, JOB_ID, "x", "now", new HttpConfig("https://x"));
        assertThrows(IllegalStateException.class, job::delete);
    }

    @Test
    void delete_withoutId_throws() {
        try (JobsClient c = client()) {
            Job job = c.new_("k", "x", "now", new HttpConfig("https://x"));
            job.id = null;
            assertThrows(IllegalStateException.class, job::delete);
        }
    }

    @Test
    void create_withoutId_throws() {
        try (JobsClient c = client()) {
            Job job = c.new_("k", "x", "now", new HttpConfig("https://x"));
            job.id = null;
            // createdAt is null, so save() dispatches to create(), which rejects null id.
            assertThrows(IllegalStateException.class, job::save);
        }
    }

    @Test
    void update_withoutId_throws() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            // Fetch sets createdAt, so save() dispatches to update(); null the id to
            // drive update()'s guard.
            Job job = c.get(JOB_ID);
            assertNotNull(job.createdAt);
            job.id = null;
            assertThrows(IllegalStateException.class, job::save);
        }
    }

    // -----------------------------------------------------------------
    // JobsConversions edge cases — absent/null configuration fields
    // -----------------------------------------------------------------

    @Test
    void conversions_nullAndMissingConfigurationFields_useDefaults() throws Exception {
        // A legacy job whose configuration omits timeout / tls_verify / headers,
        // and whose top-level attributes omit concurrency_policy / type / enabled.
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"legacy\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"L\",\"schedule\":\"now\","
                + "\"configuration\":{\"url\":\"https://legacy\"},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("legacy");
            assertEquals("https://legacy", job.configuration.url);
            assertEquals(HttpMethod.POST, job.configuration.method); // default
            assertEquals(30, job.configuration.timeout);            // default when absent
            assertTrue(job.configuration.tlsVerify);                // default when absent
            assertNull(job.configuration.body);
            assertNull(job.configuration.caCert);
            assertEquals("2xx", job.configuration.successStatus);
            assertTrue(job.configuration.headers.isEmpty());
            assertTrue(job.enabled);            // default when enabled absent
            assertEquals("ALLOW", job.concurrencyPolicy);
        }
    }

    @Test
    void conversions_nullConfiguration_returnsEmptyConfig() throws Exception {
        // configuration entirely absent → configurationFromGen(null) path.
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"noconf\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"N\",\"schedule\":\"now\","
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("noconf");
            assertNotNull(job.configuration);
            assertEquals("", job.configuration.url);
            assertEquals(HttpMethod.POST, job.configuration.method);
        }
    }

    @Test
    void conversions_disabledJobAndExplicitFalseTls() throws Exception {
        // enabled:false and tls_verify:false flow through the non-default branches.
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"d\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"D\",\"enabled\":false,\"type\":\"http\","
                + "\"schedule\":\"now\",\"concurrency_policy\":\"ALLOW\","
                + "\"configuration\":{\"method\":\"GET\",\"url\":\"https://d\","
                + "\"headers\":[{\"name\":\"H\",\"value\":\"v\"}],"
                + "\"body\":\"x\",\"success_status\":\"200\",\"timeout\":5,"
                + "\"tls_verify\":false,\"ca_cert\":\"PEM\"},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("d");
            assertFalse(job.enabled);
            assertEquals(HttpMethod.GET, job.configuration.method);
            assertEquals(5, job.configuration.timeout);
            assertFalse(job.configuration.tlsVerify);
            assertEquals("x", job.configuration.body);
            assertEquals("200", job.configuration.successStatus);
            assertEquals("PEM", job.configuration.caCert);
            assertEquals(1, job.configuration.headers.size());
        }
    }

    @Test
    void conversions_runWithNullEnumsAndFields() throws Exception {
        // A minimal run: status/trigger/failure_reason absent and rerun_of null —
        // drives the null-coalescing branches in runFromResource.
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"" + RUN_ID + "\",\"type\":\"run\","
                + "\"attributes\":{\"job\":\"" + JOB_ID + "\"}}}"));
        try (JobsClient c = client()) {
            Run run = c.runs.get(RUN_ID);
            assertEquals(JOB_ID, run.job);
            assertNull(run.status);
            assertNull(run.trigger);
            assertNull(run.rerunOf);
            assertNull(run.failureReason);
            assertNull(run.scheduledFor);
        }
    }

    @Test
    void conversions_configurationWithNullUrl_defaultsToEmptyString() throws Exception {
        // A configuration object is present but its url is absent. This drives the
        // ``: ""`` arm of JobsConversions.configurationFromGen line 46
        // (src.getUrl() != null ? ... : ""), which the legacy-config tests miss
        // because they each supply a url. timeout / tls_verify are also present
        // here so their non-default arms (lines 51 / 54) stay exercised.
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"nourl\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"NoUrl\",\"schedule\":\"now\","
                + "\"configuration\":{\"method\":\"POST\",\"timeout\":7,\"tls_verify\":false},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("nourl");
            assertEquals("", job.configuration.url);   // null url → ""
            assertEquals(7, job.configuration.timeout); // explicit timeout (non-default arm)
            assertFalse(job.configuration.tlsVerify);   // explicit tls (non-default arm)
        }
    }

    @Test
    void conversions_runWithFailureReason_mapsEnumValue() throws Exception {
        // A run carrying a non-null failure_reason drives the value-extracting arm
        // of JobsConversions.runFromResource line 80
        // (a.getFailureReason() == null ? null : a.getFailureReason().getValue()),
        // which the happy-path / minimal-run tests never hit (failure_reason null).
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"" + RUN_ID + "\",\"type\":\"run\","
                + "\"attributes\":{\"job\":\"" + JOB_ID + "\",\"status\":\"FAILED\","
                + "\"failure_reason\":\"TIMEOUT\"}}}"));
        try (JobsClient c = client()) {
            Run run = c.runs.get(RUN_ID);
            assertEquals("TIMEOUT", run.failureReason);
        }
    }

    @Test
    void conversions_jobWithExplicitEnabledTrue_mapsTrue() throws Exception {
        // enabled:true present on the wire drives the value-passing arm of
        // JobsClient.fromResource line 348 (a.getEnabled() != null ? ... : true).
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"en\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"En\",\"schedule\":\"now\",\"enabled\":true,"
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://en\"},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("en");
            assertTrue(job.enabled);
        }
    }

    @Test
    void conversions_configurationToGen_nullMethodAndNullHeaders() {
        // Build a Job whose configuration has a null method and null headers list,
        // then save it so configurationToGen runs against those nulls.
        installFullHandler();
        try (JobsClient c = client()) {
            HttpConfig cfg = new HttpConfig("https://x");
            cfg.method = null;
            cfg.headers = null;
            cfg.successStatus = null;
            Job job = c.new_(JOB_ID, "My Job", "now", cfg);
            assertDoesNotThrow(job::save);
        }
    }

    // -----------------------------------------------------------------
    // Model constructors, enums, toString, inputs
    // -----------------------------------------------------------------

    @Test
    void enumsFromValueRoundTripAndRejectUnknown() {
        for (HttpMethod m : HttpMethod.values()) {
            assertEquals(m, HttpMethod.fromValue(m.getValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> HttpMethod.fromValue("nope"));
    }

    @Test
    void httpConfigConstructorsAndDefaults() {
        HttpConfig empty = new HttpConfig();
        assertEquals(HttpMethod.POST, empty.method);
        assertEquals("", empty.url);
        assertEquals("2xx", empty.successStatus);
        assertEquals(30, empty.timeout);
        assertTrue(empty.tlsVerify);
        assertNull(empty.body);
        assertNull(empty.caCert);
        assertTrue(empty.headers.isEmpty());
        HttpConfig fromUrl = new HttpConfig("https://x");
        assertEquals("https://x", fromUrl.url);
        HttpConfig full = new HttpConfig(HttpMethod.GET, "https://y",
                List.of(new HttpHeader("k", "v")));
        assertEquals(HttpMethod.GET, full.method);
        assertEquals("https://y", full.url);
        assertEquals(1, full.headers.size());
    }

    @Test
    void modelToStringsAndInputs() {
        Job job = new Job(null, "id1", "name1", "now", new HttpConfig("https://x"));
        assertTrue(job.toString().contains("Job("));
        Run run = new Run("r1", "j1", 1, "MANUAL", null, null, "PENDING",
                null, null, null, null, null, null, null, null, null, null);
        assertTrue(run.toString().contains("Run("));
        assertEquals("r1", run.id);
        assertEquals("j1", run.job);
        Usage usage = new Usage("2026-06", 1, 2, 3, 4);
        assertTrue(usage.toString().contains("Usage("));
        assertEquals("2026-06", usage.period);
        assertEquals(1, usage.runsUsed);
        assertEquals(2, usage.runsIncluded);
        assertEquals(3, usage.activeJobs);
        assertEquals(4, usage.activeJobsLimit);
        HttpHeader h = new HttpHeader("a", "b");
        assertEquals("a", h.name);
        assertEquals("b", h.value);
        ListJobsInput lji = new ListJobsInput();
        assertNull(lji.enabled);
        assertNull(lji.pageNumber);
        assertNull(lji.pageSize);
        ListRunsInput lri = new ListRunsInput();
        assertNull(lri.job);
        assertNull(lri.pageSize);
        assertNull(lri.after);
    }
}
