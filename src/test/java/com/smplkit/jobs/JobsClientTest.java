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
import java.util.ArrayList;
import java.util.HashMap;
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
 * {@link AsyncRunsClient}, including the environment-scoping surface
 * (per-environment enablement / configuration, the {@code X-Smplkit-Environment}
 * write header, and {@code filter[environment]} on run reads).
 *
 * <p>Stubs the jobs service via the JDK's built-in {@link HttpServer} bound to
 * {@code 127.0.0.1:0}; no real network. The clients are built through the wired
 * constructor pointed at the loopback stub. The wrapper layer here must reach
 * 100% line coverage to satisfy the SDK CI gate.</p>
 */
class JobsClientTest {

    private static final String JOB_ID = "my-job";
    private static final String RUN_ID = "8f2b1c4a-0000-4a1b-9c3d-1e2f3a4b5c6d";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private ExecutorService executor;

    // Last-request capture for asserting write headers, bodies, and read filters.
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastEnvHeader = new AtomicReference<>();
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
        // Wired constructor (no environment). ownsTransport is false.
        return new JobsClient("sk_test", Map.of(), Duration.ofSeconds(5), baseUrl);
    }

    private JobsClient clientWithEnv(String env) {
        return new JobsClient("sk_test", Map.of(), Duration.ofSeconds(5), baseUrl, env);
    }

    private AsyncJobsClient asyncClient() {
        return AsyncJobsClient.wrap(client(), executor);
    }

    private void capture(HttpExchange ex) throws IOException {
        lastEnvHeader.set(ex.getRequestHeaders().getFirst("X-Smplkit-Environment"));
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

    private static String jobResource(String id, boolean created, int version, boolean enabled) {
        String ts = created ? "\"2026-06-04T00:00:00Z\"" : "null";
        return "{\"id\":\"" + id + "\",\"type\":\"job\",\"attributes\":{"
                + "\"name\":\"My Job\",\"description\":\"does a thing\","
                + "\"enabled\":" + enabled + ",\"recurring\":true,\"type\":\"http\","
                + "\"schedule\":\"0 * * * *\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://api.example.com/hook\","
                + "\"headers\":[{\"name\":\"X-Api-Key\",\"value\":\"secret\"}],"
                + "\"body\":\"{}\",\"success_status\":\"2xx\",\"timeout\":30,"
                + "\"tls_verify\":true,\"ca_cert\":null},"
                + "\"environments\":{\"production\":{\"enabled\":" + enabled + "}},"
                + "\"concurrency_policy\":\"ALLOW\","
                + "\"next_run_at\":\"2026-06-05T00:00:00Z\","
                + "\"created_at\":" + ts + ",\"updated_at\":" + ts + ","
                + "\"deleted_at\":null,\"version\":" + version + "}}";
    }

    // A job carrying a rich environments map: production enabled (no override,
    // inherits base) and development disabled with a per-environment override.
    private static String jobResourceWithEnvs(String id, int version, boolean enabled, boolean recurring) {
        return "{\"id\":\"" + id + "\",\"type\":\"job\",\"attributes\":{"
                + "\"name\":\"My Job\",\"description\":\"does a thing\","
                + "\"enabled\":" + enabled + ",\"recurring\":" + recurring + ",\"type\":\"http\","
                + "\"schedule\":\"0 2 * * *\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://base.example.com/hook\","
                + "\"headers\":[],\"body\":null,\"success_status\":\"2xx\",\"timeout\":30,"
                + "\"tls_verify\":true,\"ca_cert\":null},"
                + "\"environments\":{"
                + "\"production\":{\"enabled\":true},"
                + "\"development\":{\"enabled\":false,\"configuration\":{\"method\":\"POST\","
                + "\"url\":\"https://dev.example.com/hook\",\"headers\":[],\"body\":null,"
                + "\"success_status\":\"2xx\",\"timeout\":30,\"tls_verify\":true,\"ca_cert\":null}}"
                + "},\"concurrency_policy\":\"ALLOW\","
                + "\"created_at\":\"2026-06-04T00:00:00Z\",\"updated_at\":\"2026-06-04T00:00:00Z\","
                + "\"deleted_at\":null,\"version\":" + version + "}}";
    }

    private static String runResource(String status, String trigger, String rerunOf, String environment) {
        String ro = rerunOf == null ? "null" : "\"" + rerunOf + "\"";
        return "{\"id\":\"" + RUN_ID + "\",\"type\":\"run\",\"attributes\":{"
                + "\"job\":\"" + JOB_ID + "\",\"job_version\":1,\"environment\":\"" + environment + "\","
                + "\"trigger\":\"" + trigger + "\","
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

    /** A handler implementing the full jobs surface; captures each request. */
    private void installFullHandler() {
        handler.set(ex -> {
            capture(ex);
            String m = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/v1/jobs") && m.equals("POST")) {
                respondJson(ex, 201, "{\"data\":" + jobResource(JOB_ID, true, 1, false) + "}");
            } else if (path.equals("/api/v1/jobs") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":[" + jobResource("a", true, 1, true)
                        + "," + jobResource("b", true, 1, true)
                        + "],\"meta\":{\"pagination\":{\"page\":1,\"size\":50}}}");
            } else if (path.endsWith("/actions/run")) {
                respondJson(ex, 200, "{\"data\":" + runResource("PENDING", "MANUAL", null, "production") + "}");
            } else if (path.startsWith("/api/v1/jobs/") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":" + jobResourceWithEnvs(JOB_ID, 1, true, true) + "}");
            } else if (path.startsWith("/api/v1/jobs/") && m.equals("PUT")) {
                respondJson(ex, 200, "{\"data\":" + jobResourceWithEnvs(JOB_ID, 2, true, true) + "}");
            } else if (path.startsWith("/api/v1/jobs/") && m.equals("DELETE")) {
                respondJson(ex, 204, "");
            } else if (path.equals("/api/v1/usage")) {
                respondJson(ex, 200, "{\"data\":" + USAGE + "}");
            } else if (path.equals("/api/v1/runs") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":[" + runResource("SUCCEEDED", "SCHEDULE", null, "production")
                        + "],\"meta\":{\"page_size\":50}}");
            } else if (path.endsWith("/actions/cancel")) {
                respondJson(ex, 200, "{\"data\":" + runResource("CANCELED", "RERUN", RUN_ID, "production") + "}");
            } else if (path.endsWith("/actions/rerun")) {
                respondJson(ex, 200, "{\"data\":" + runResource("PENDING", "RERUN", RUN_ID, "production") + "}");
            } else if (path.startsWith("/api/v1/runs/") && m.equals("GET")) {
                respondJson(ex, 200, "{\"data\":" + runResource("SUCCEEDED", "SCHEDULE", null, "production") + "}");
            } else {
                respondJson(ex, 500, "{}");
            }
        });
    }

    private static <T> T await(CompletableFuture<T> f) throws Exception {
        return f.get(5, TimeUnit.SECONDS);
    }

    private JsonNode attrs() throws Exception {
        return MAPPER.readTree(lastBody.get()).path("data").path("attributes");
    }

    // -----------------------------------------------------------------
    // new_ overloads — defaults / one-off birth env / full
    // -----------------------------------------------------------------

    @Test
    void newJob_fourArg_appliesDefaults() {
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            assertEquals(JOB_ID, job.id);
            assertEquals("My Job", job.name);
            assertEquals("0 * * * *", job.schedule);
            assertNull(job.description);
            assertFalse(job.enabled);                 // read-only roll-up defaults false
            assertTrue(job.environments.isEmpty());
            assertEquals("ALLOW", job.concurrencyPolicy);
            assertNull(job.createdAt);
            assertNull(job.birthEnvironment);         // no client env configured
        }
    }

    @Test
    void newJob_fiveArg_setsBirthEnvironment() {
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "My Job", "now", new HttpConfig("https://x"), "development");
            assertEquals("development", job.birthEnvironment);
        }
    }

    @Test
    void newJob_fiveArg_nullEnv_fallsBackToClientEnv() {
        try (JobsClient c = clientWithEnv("production")) {
            Job job = c.new_(JOB_ID, "My Job", "now", new HttpConfig("https://x"), null);
            assertEquals("production", job.birthEnvironment);
        }
    }

    @Test
    void newJob_fourArg_birthEnvDefaultsToClientEnv() {
        try (JobsClient c = clientWithEnv("production")) {
            Job job = c.new_(JOB_ID, "My Job", "now", new HttpConfig("https://x"));
            assertEquals("production", job.birthEnvironment);
        }
    }

    @Test
    void newJob_eightArg_setsEveryField() {
        try (JobsClient c = client()) {
            Map<String, JobEnvironment> envs = new HashMap<>();
            envs.put("production", new JobEnvironment(true));
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"),
                    "a description", envs, "ALLOW", "production");
            assertEquals("a description", job.description);
            assertTrue(job.isEnabled("production"));
            assertEquals("ALLOW", job.concurrencyPolicy);
            assertEquals("production", job.birthEnvironment);
        }
    }

    @Test
    void newJob_eightArg_nullEnvironments_startsEmpty() {
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"),
                    null, null, "ALLOW", null);
            assertTrue(job.environments.isEmpty());
        }
    }

    @Test
    void asyncNewJob_overloads() {
        try (AsyncJobsClient c = asyncClient()) {
            assertNull(c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x")).description);
            assertEquals("development",
                    c.new_(JOB_ID, "My Job", "now", new HttpConfig("https://x"), "development").birthEnvironment);
            Map<String, JobEnvironment> envs = new HashMap<>();
            envs.put("production", new JobEnvironment(true));
            Job full = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"),
                    "desc", envs, "ALLOW", "production");
            assertEquals("desc", full.description);
            assertTrue(full.isEnabled("production"));
            assertEquals("production", full.birthEnvironment);
        }
    }

    // -----------------------------------------------------------------
    // Per-environment enablement / configuration on Job
    // -----------------------------------------------------------------

    @Test
    void perEnvironmentMutatorsAndGetters() {
        try (JobsClient c = client()) {
            HttpConfig base = new HttpConfig("https://base");
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", base);

            // roll-up + per-env enablement
            assertFalse(job.isEnabled());
            assertFalse(job.isEnabled("production"));
            job.setEnabled(true, "production");
            assertTrue(job.isEnabled("production"));
            // flipping enabled preserves a previously-set configuration override
            HttpConfig prodCfg = new HttpConfig("https://prod");
            job.setConfiguration(prodCfg, "production");
            job.setEnabled(false, "production");
            assertFalse(job.isEnabled("production"));
            assertSame(prodCfg, job.environments.get("production").configuration);

            // base configuration via setter + getter
            HttpConfig base2 = new HttpConfig("https://base2");
            job.setConfiguration(base2);
            assertSame(base2, job.getConfiguration());
            job.setConfiguration(base, null); // environment == null sets base
            assertSame(base, job.getConfiguration());

            // per-env override resolution: present override wins
            assertSame(prodCfg, job.getConfiguration("production"));
            // environment with no override falls back to base
            assertSame(base, job.getConfiguration("staging"));
            // environment whose override has a null configuration falls back to base
            job.setEnabled(true, "development"); // creates an override with null config
            assertSame(base, job.getConfiguration("development"));
            // base when environment arg is null
            assertSame(base, job.getConfiguration(null));

            // schedule setter is environment-agnostic
            job.setSchedule("30 2 * * *");
            assertEquals("30 2 * * *", job.schedule);

            // toString lists the enabled environments, sorted
            job.setEnabled(true, "production");
            assertTrue(job.toString().contains("enabled_in=[development, production]"));
        }
    }

    // -----------------------------------------------------------------
    // Active-record save / build-to-wire
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
            assertEquals(HttpMethod.POST, job.configuration.method);
            assertNotNull(job.nextRunAt);
            assertEquals("http", job.type);
            assertEquals(Boolean.TRUE, job.recurring);  // parsed back
            job.name = "renamed";
            job.save();
            assertEquals(2, job.version);
        }
    }

    @Test
    void createDropsEnabled_andEmitsEnvironments() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Map<String, JobEnvironment> envs = new HashMap<>();
            envs.put("production", new JobEnvironment(true)); // no override -> inherit base
            envs.put("development", new JobEnvironment(false, new HttpConfig("https://dev")));
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://base"),
                    null, envs, "ALLOW", null);
            job.save();
            JsonNode a = attrs();
            // base ``enabled`` roll-up is read-only — never written
            assertFalse(a.has("enabled"), "write body must not include base 'enabled'");
            // environments emitted; production has no configuration, development does
            JsonNode envNode = a.path("environments");
            assertTrue(envNode.path("production").path("enabled").asBoolean());
            assertFalse(envNode.path("production").has("configuration"));
            assertTrue(envNode.path("development").has("configuration"));
            assertEquals("https://dev", envNode.path("development").path("configuration").path("url").asText());
        }
    }

    @Test
    void createWithoutEnvironments_omitsEnvironmentsKey() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://base"));
            job.save();
            assertFalse(attrs().has("environments"), "empty environments map must be omitted");
        }
    }

    @Test
    void createOneOff_sendsBirthEnvironmentHeader() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job oneoff = c.new_(JOB_ID, "One-shot", "now", new HttpConfig("https://x"), "development");
            oneoff.save();
            assertEquals("development", lastEnvHeader.get());
        }
    }

    @Test
    void createRecurring_sendsNoBirthEnvironmentHeader() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.new_(JOB_ID, "Recurring", "0 * * * *", new HttpConfig("https://x"));
            job.save();
            assertNull(lastEnvHeader.get());
        }
    }

    @Test
    void update_sendsClientEnvironmentHeader() throws Exception {
        installFullHandler();
        try (JobsClient c = clientWithEnv("production")) {
            Job job = c.get(JOB_ID);          // createdAt set -> next save is an update
            assertNotNull(job.createdAt);
            job.name = "renamed";
            job.save();
            assertEquals("production", lastEnvHeader.get());
        }
    }

    // -----------------------------------------------------------------
    // Parse-from-wire — environments / recurling / enabled roll-up
    // -----------------------------------------------------------------

    @Test
    void getParsesEnvironments() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.get(JOB_ID);
            assertTrue(job.isEnabled());                       // roll-up true
            assertTrue(job.isEnabled("production"));
            assertFalse(job.isEnabled("development"));
            assertEquals(Boolean.TRUE, job.recurring);
            // production has no override -> getConfiguration falls back to base
            assertEquals("https://base.example.com/hook", job.getConfiguration("production").url);
            // development has an override
            assertEquals("https://dev.example.com/hook", job.getConfiguration("development").url);
        }
    }

    @Test
    void getParsesNullEnvironmentEntry() throws Exception {
        // A null environment entry -> environmentsFromGen leaves the default.
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"n\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"N\",\"schedule\":\"0 * * * *\","
                + "\"configuration\":{\"url\":\"https://x\"},"
                + "\"environments\":{\"production\":null},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("n");
            assertTrue(job.environments.containsKey("production"));
            assertFalse(job.environments.get("production").enabled);
            assertNull(job.environments.get("production").configuration);
        }
    }

    @Test
    void listParsesAndGetDelete() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
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

    // -----------------------------------------------------------------
    // run / runs / usage + env header / filter[environment]
    // -----------------------------------------------------------------

    @Test
    void run_environmentHeaderResolution() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Run r = c.run(JOB_ID);                 // no client env -> no header
            assertEquals("MANUAL", r.trigger);
            assertEquals("production", r.environment);
            assertNull(lastEnvHeader.get());
            c.run(JOB_ID, "development");           // explicit env header
            assertEquals("development", lastEnvHeader.get());
        }
        try (JobsClient c = clientWithEnv("production")) {
            c.run(JOB_ID);                          // client default env header
            assertEquals("production", lastEnvHeader.get());
            c.run(JOB_ID, "staging");               // explicit overrides client default
            assertEquals("staging", lastEnvHeader.get());
        }
    }

    @Test
    void runsList_filterEnvironmentResolution() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            c.runs.list();                                  // neither explicit nor client default
            assertFalse(lastQuery.get() != null && lastQuery.get().contains("filter[environment]="));
            ListRunsInput explicit = new ListRunsInput();
            explicit.environments = List.of("production", "development");
            c.runs.list(explicit);                          // explicit comma-joined wins
            assertTrue(lastQuery.get().contains("filter[environment]=production,development"));
            ListRunsInput blank = new ListRunsInput();
            blank.environments = List.of(" ", "");           // blank-only -> filter omitted
            c.runs.list(blank);
            assertFalse(lastQuery.get() != null && lastQuery.get().contains("filter[environment]="));
        }
        try (JobsClient c = clientWithEnv("production")) {
            c.runs.list();                                  // client default scopes the read
            assertTrue(lastQuery.get().contains("filter[environment]=production"));
        }
    }

    @Test
    void runsGetCancelRerunUsage() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            assertEquals(1, c.runs.list().size());
            ListRunsInput in = new ListRunsInput();
            in.job = JOB_ID;
            in.pageSize = 2;
            in.after = "cur";
            assertEquals(1, c.runs.list(in).size());
            Run got = c.runs.get(RUN_ID);
            assertEquals("SUCCEEDED", got.status);
            assertEquals("production", got.environment);
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
    // Active-record run actions on Run / Job
    // -----------------------------------------------------------------

    @Test
    void run_activeRecord_rerunAndCancel() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Run run = c.run(JOB_ID);
            Run rerun = run.rerun();
            assertEquals("RERUN", rerun.trigger);
            assertEquals("production", rerun.environment);
            Run canceled = rerun.cancel();
            assertEquals("CANCELED", canceled.status);
        }
    }

    @Test
    void job_activeRecord_triggerAndListRuns() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.get(JOB_ID);
            Run viaDefault = job.trigger();
            assertEquals("MANUAL", viaDefault.trigger);
            Run viaEnv = job.trigger("production");
            assertEquals("production", viaEnv.environment);
            assertEquals("production", lastEnvHeader.get());

            assertEquals(1, job.listRuns().size());
            assertFalse(lastQuery.get() != null && lastQuery.get().contains("filter[environment]="));
            assertEquals(1, job.listRuns("production").size());
            assertTrue(lastQuery.get().contains("filter[job]=" + JOB_ID));
            assertTrue(lastQuery.get().contains("filter[environment]=production"));
            assertEquals(1, job.listRuns("production", 5, "cur").size());
            assertTrue(lastQuery.get().contains("page[size]=5"));
        }
    }

    // -----------------------------------------------------------------
    // Async surface
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
            assertEquals("MANUAL", await(c.run(JOB_ID, "development")).trigger);
            assertEquals("development", lastEnvHeader.get());
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
        installErrorHandler();
        try (AsyncJobsClient c = asyncClient()) {
            assertApiFailure(c.list());
            assertApiFailure(c.list(new ListJobsInput()));
            assertApiFailure(c.get("missing"));
            assertApiFailure(c.run("missing"));
            assertApiFailure(c.run("missing", "production"));
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
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> f.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, ee.getCause());
        CompletionException ce = assertThrows(CompletionException.class, f::join);
        assertInstanceOf(ApiException.class, ce.getCause());
    }

    // -----------------------------------------------------------------
    // Job.saveAsync / deleteAsync
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
    void trigger_withoutClient_throws() {
        Job job = new Job(null, JOB_ID, "x", "now", new HttpConfig("https://x"));
        assertThrows(IllegalStateException.class, job::trigger);
        assertThrows(IllegalStateException.class, () -> job.trigger("production"));
    }

    @Test
    void listRuns_withoutClient_throws() {
        Job job = new Job(null, JOB_ID, "x", "now", new HttpConfig("https://x"));
        assertThrows(IllegalStateException.class, job::listRuns);
        assertThrows(IllegalStateException.class, () -> job.listRuns("production"));
    }

    @Test
    void run_withoutClient_rerunCancelThrow() {
        Run run = new Run(RUN_ID, JOB_ID, 1, "production", "MANUAL", null, null, "PENDING",
                null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(IllegalStateException.class, run::rerun);
        assertThrows(IllegalStateException.class, run::cancel);
    }

    @Test
    void create_withoutId_throws() {
        try (JobsClient c = client()) {
            Job job = c.new_("k", "x", "now", new HttpConfig("https://x"));
            job.id = null;
            assertThrows(IllegalStateException.class, job::save);
        }
    }

    @Test
    void update_withoutId_throws() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
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
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"legacy\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"L\",\"schedule\":\"now\","
                + "\"configuration\":{\"url\":\"https://legacy\"},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("legacy");
            assertEquals("https://legacy", job.configuration.url);
            assertEquals(HttpMethod.POST, job.configuration.method);
            assertEquals(30, job.configuration.timeout);
            assertTrue(job.configuration.tlsVerify);
            assertNull(job.configuration.body);
            assertNull(job.configuration.caCert);
            assertEquals("2xx", job.configuration.successStatus);
            assertTrue(job.configuration.headers.isEmpty());
            assertFalse(job.enabled);            // default false when enabled absent
            assertNull(job.recurring);           // absent recurring -> null
            assertTrue(job.environments.isEmpty()); // absent environments -> empty
            assertEquals("ALLOW", job.concurrencyPolicy);
        }
    }

    @Test
    void conversions_nullConfiguration_returnsEmptyConfig() throws Exception {
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
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"" + RUN_ID + "\",\"type\":\"run\","
                + "\"attributes\":{\"job\":\"" + JOB_ID + "\",\"environment\":\"production\"}}}"));
        try (JobsClient c = client()) {
            Run run = c.runs.get(RUN_ID);
            assertEquals(JOB_ID, run.job);
            assertEquals("production", run.environment);
            assertNull(run.status);
            assertNull(run.trigger);
            assertNull(run.rerunOf);
            assertNull(run.failureReason);
            assertNull(run.scheduledFor);
        }
    }

    @Test
    void conversions_configurationWithNullUrl_defaultsToEmptyString() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"nourl\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"NoUrl\",\"schedule\":\"now\","
                + "\"configuration\":{\"method\":\"POST\",\"timeout\":7,\"tls_verify\":false},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("nourl");
            assertEquals("", job.configuration.url);
            assertEquals(7, job.configuration.timeout);
            assertFalse(job.configuration.tlsVerify);
        }
    }

    @Test
    void conversions_runWithFailureReason_mapsEnumValue() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"" + RUN_ID + "\",\"type\":\"run\","
                + "\"attributes\":{\"job\":\"" + JOB_ID + "\",\"environment\":\"production\",\"status\":\"FAILED\","
                + "\"failure_reason\":\"TIMEOUT\"}}}"));
        try (JobsClient c = client()) {
            Run run = c.runs.get(RUN_ID);
            assertEquals("TIMEOUT", run.failureReason);
        }
    }

    @Test
    void conversions_jobWithExplicitEnabledTrue_mapsTrue() throws Exception {
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

    @Test
    void conversions_envFilterHelpers() {
        // joinEnvironments: null / empty / blank-only -> null; valid -> comma-joined
        assertNull(JobsConversions.joinEnvironments(null));
        assertNull(JobsConversions.joinEnvironments(new ArrayList<>()));
        assertNull(JobsConversions.joinEnvironments(List.of(" ", "")));
        assertEquals("a,b", JobsConversions.joinEnvironments(List.of("a", "b")));
        // resolveEnvironmentFilter: explicit wins / falls back to default / null
        assertEquals("x,y", JobsConversions.resolveEnvironmentFilter(List.of("x", "y"), "prod"));
        assertEquals("prod", JobsConversions.resolveEnvironmentFilter(null, "prod"));
        assertNull(JobsConversions.resolveEnvironmentFilter(null, null));
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
    void jobEnvironmentConstructors() {
        JobEnvironment empty = new JobEnvironment();
        assertFalse(empty.enabled);
        assertNull(empty.configuration);
        JobEnvironment enabledOnly = new JobEnvironment(true);
        assertTrue(enabledOnly.enabled);
        assertNull(enabledOnly.configuration);
        HttpConfig cfg = new HttpConfig("https://z");
        JobEnvironment both = new JobEnvironment(false, cfg);
        assertFalse(both.enabled);
        assertSame(cfg, both.configuration);
    }

    @Test
    void modelToStringsAndInputs() {
        Job job = new Job(null, "id1", "name1", "now", new HttpConfig("https://x"));
        assertTrue(job.toString().contains("Job("));
        Run run = new Run("r1", "j1", 1, "production", "MANUAL", null, null, "PENDING",
                null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(run.toString().contains("Run("));
        assertEquals("r1", run.id);
        assertEquals("j1", run.job);
        assertEquals("production", run.environment);
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
        assertNull(lji.recurring);
        assertNull(lji.pageNumber);
        assertNull(lji.pageSize);
        ListRunsInput lri = new ListRunsInput();
        assertNull(lri.job);
        assertNull(lri.environments);
        assertNull(lri.pageSize);
        assertNull(lri.after);
    }
}
