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
import java.time.OffsetDateTime;
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
        // The production env carries a per-environment ``next_run_at`` (read-only)
        // when enabled, exercising the per-env nextRunAt mapping. Top-level
        // ``enabled`` / ``next_run_at`` are gone from the wire.
        String prodNextRun = enabled ? "\"2026-06-05T00:00:00Z\"" : "null";
        return "{\"id\":\"" + id + "\",\"type\":\"job\",\"attributes\":{"
                + "\"name\":\"My Job\",\"description\":\"does a thing\","
                + "\"kind\":\"recurring\",\"type\":\"http\","
                + "\"schedule\":\"0 * * * *\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://api.example.com/hook\","
                + "\"headers\":[{\"name\":\"X-Api-Key\",\"value\":\"secret\"}],"
                + "\"body\":\"{}\",\"success_status\":\"2xx\",\"timeout\":30,"
                + "\"tls_verify\":true,\"ca_cert\":null},"
                + "\"environments\":{\"production\":{\"enabled\":" + enabled
                + ",\"next_run_at\":" + prodNextRun + "}},"
                + "\"concurrency_policy\":\"ALLOW\","
                + "\"created_at\":" + ts + ",\"updated_at\":" + ts + ","
                + "\"deleted_at\":null,\"version\":" + version + "}}";
    }

    // A job carrying a rich environments map: production enabled (no override,
    // inherits base) and development disabled with a per-environment override.
    private static String jobResourceWithEnvs(String id, int version, boolean enabled, String kind) {
        // production: enabled, no config override (inherits base) but a per-env
        //   ``schedule`` override + read-only ``next_run_at``.
        // development: disabled, config override, no schedule override, null
        //   ``next_run_at`` (not enabled).
        return "{\"id\":\"" + id + "\",\"type\":\"job\",\"attributes\":{"
                + "\"name\":\"My Job\",\"description\":\"does a thing\","
                + "\"kind\":\"" + kind + "\",\"type\":\"http\","
                + "\"schedule\":\"0 2 * * *\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://base.example.com/hook\","
                + "\"headers\":[],\"body\":null,\"success_status\":\"2xx\",\"timeout\":30,"
                + "\"tls_verify\":true,\"ca_cert\":null},"
                + "\"environments\":{"
                + "\"production\":{\"enabled\":true,\"schedule\":\"30 3 * * *\","
                + "\"next_run_at\":\"2026-06-05T03:30:00Z\"},"
                + "\"development\":{\"enabled\":false,\"schedule\":null,\"next_run_at\":null,"
                + "\"configuration\":{\"method\":\"POST\","
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
                respondJson(ex, 200, "{\"data\":" + jobResourceWithEnvs(JOB_ID, 1, true, "recurring") + "}");
            } else if (path.startsWith("/api/v1/jobs/") && m.equals("PUT")) {
                respondJson(ex, 200, "{\"data\":" + jobResourceWithEnvs(JOB_ID, 2, true, "recurring") + "}");
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
    // newRecurringJob / newManualJob / schedule — defaults, full, birth env
    // -----------------------------------------------------------------

    @Test
    void newRecurringJob_fourArg_appliesDefaults() {
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            assertEquals(JOB_ID, job.id);
            assertEquals("My Job", job.name);
            assertEquals("0 * * * *", job.schedule);
            assertNull(job.description);
            assertFalse(job.enabled);                 // read-only roll-up defaults false
            assertTrue(job.environments.isEmpty());
            assertEquals("ALLOW", job.concurrencyPolicy);
            assertNull(job.createdAt);
            assertNull(job.kind);                     // unsaved -> no server kind yet
            assertNull(job.birthEnvironment);         // no client env configured
        }
    }

    @Test
    void newRecurringJob_sevenArg_setsEveryField() {
        try (JobsClient c = client()) {
            Map<String, JobEnvironment> envs = new HashMap<>();
            envs.put("production", new JobEnvironment(true));
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"),
                    "a description", envs, "ALLOW");
            assertEquals("a description", job.description);
            assertTrue(job.isEnabled("production"));
            assertEquals("ALLOW", job.concurrencyPolicy);
            assertNull(job.birthEnvironment);         // recurring jobs have no birth env
        }
    }

    @Test
    void newRecurringJob_sevenArg_nullEnvironments_startsEmpty() {
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"),
                    null, null, "ALLOW");
            assertTrue(job.environments.isEmpty());
        }
    }

    @Test
    void newManualJob_threeArg_hasNoScheduleAndDefaults() {
        try (JobsClient c = client()) {
            Job job = c.newManualJob(JOB_ID, "Manual", new HttpConfig("https://x"));
            assertNull(job.schedule);                 // a manual job has no schedule
            assertNull(job.description);
            assertTrue(job.environments.isEmpty());
            assertEquals("ALLOW", job.concurrencyPolicy);
            assertNull(job.birthEnvironment);
        }
    }

    @Test
    void newManualJob_sixArg_setsEveryField() {
        try (JobsClient c = client()) {
            Map<String, JobEnvironment> envs = new HashMap<>();
            envs.put("production", new JobEnvironment(true));
            Job job = c.newManualJob(JOB_ID, "Manual", new HttpConfig("https://x"),
                    "a description", envs, "ALLOW");
            assertNull(job.schedule);
            assertEquals("a description", job.description);
            assertTrue(job.isEnabled("production"));
            assertEquals("ALLOW", job.concurrencyPolicy);
        }
    }

    @Test
    void newManualJob_sixArg_nullEnvironments_startsEmpty() {
        try (JobsClient c = client()) {
            Job job = c.newManualJob(JOB_ID, "Manual", new HttpConfig("https://x"), null, null, "ALLOW");
            assertTrue(job.environments.isEmpty());
        }
    }

    @Test
    void schedule_fourArg_serializesDatetimeAndDefaultsBirthEnv() {
        OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
        try (JobsClient c = client()) {
            Job job = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"));
            assertEquals(when.toString(), job.schedule);   // datetime -> ISO-8601 string
            assertNull(job.birthEnvironment);              // no client env configured
        }
        try (JobsClient c = clientWithEnv("production")) {
            Job job = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"));
            assertEquals("production", job.birthEnvironment);  // falls back to client env
        }
    }

    @Test
    void schedule_fiveArg_setsBirthEnvironment() {
        OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
        try (JobsClient c = client()) {
            Job job = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"), "development");
            assertEquals("development", job.birthEnvironment);
        }
        try (JobsClient c = clientWithEnv("production")) {
            Job job = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"), null);
            assertEquals("production", job.birthEnvironment);  // null falls back to client env
        }
    }

    @Test
    void schedule_sevenArg_setsEveryField() {
        OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
        try (JobsClient c = client()) {
            Job job = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"),
                    "a description", "ALLOW", "staging");
            assertEquals(when.toString(), job.schedule);
            assertEquals("a description", job.description);
            assertEquals("ALLOW", job.concurrencyPolicy);
            assertEquals("staging", job.birthEnvironment);
        }
    }

    @Test
    void asyncConstructors_overloads() {
        OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
        try (AsyncJobsClient c = asyncClient()) {
            assertNull(c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x")).description);
            Map<String, JobEnvironment> envs = new HashMap<>();
            envs.put("production", new JobEnvironment(true));
            Job recFull = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"),
                    "desc", envs, "ALLOW");
            assertEquals("desc", recFull.description);
            assertTrue(recFull.isEnabled("production"));

            Job manual = c.newManualJob(JOB_ID, "Manual", new HttpConfig("https://x"));
            assertNull(manual.schedule);
            Job manualFull = c.newManualJob(JOB_ID, "Manual", new HttpConfig("https://x"),
                    "desc", envs, "ALLOW");
            assertEquals("desc", manualFull.description);
            assertTrue(manualFull.isEnabled("production"));

            assertEquals(when.toString(),
                    c.schedule(JOB_ID, "One", when, new HttpConfig("https://x")).schedule);
            assertEquals("development",
                    c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"), "development").birthEnvironment);
            Job oneoffFull = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"),
                    "desc", "ALLOW", "staging");
            assertEquals("staging", oneoffFull.birthEnvironment);
        }
    }

    @Test
    void kindPredicates() {
        // isRecurring / isManual / isOneOff reflect the parsed kind; all false
        // when kind is null (an unsaved or kind-less job).
        Job rec = new Job(null, "k", "n", "0 * * * *", new HttpConfig("https://x"));
        rec.kind = JobKind.RECURRING;
        assertTrue(rec.isRecurring());
        assertFalse(rec.isManual());
        assertFalse(rec.isOneOff());
        Job man = new Job(null, "k", "n", null, new HttpConfig("https://x"));
        man.kind = JobKind.MANUAL;
        assertTrue(man.isManual());
        assertFalse(man.isRecurring());
        assertFalse(man.isOneOff());
        Job off = new Job(null, "k", "n", "now", new HttpConfig("https://x"));
        off.kind = JobKind.ONE_OFF;
        assertTrue(off.isOneOff());
        assertFalse(off.isRecurring());
        assertFalse(off.isManual());
        Job none = new Job(null, "k", "n", null, new HttpConfig("https://x"));
        assertNull(none.kind);
        assertFalse(none.isRecurring() || none.isManual() || none.isOneOff());
    }

    // -----------------------------------------------------------------
    // Per-environment enablement / configuration on Job
    // -----------------------------------------------------------------

    @Test
    void perEnvironmentMutatorsAndGetters() {
        try (JobsClient c = client()) {
            HttpConfig base = new HttpConfig("https://base");
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", base);

            // roll-up + per-env enablement (the no-arg roll-up computes live)
            assertFalse(job.isEnabled());
            assertFalse(job.isEnabled("production"));
            job.setEnabled(true, "production");
            assertTrue(job.isEnabled("production"));
            assertTrue(job.isEnabled());          // any-env-enabled -> roll-up true
            // flipping enabled preserves a previously-set configuration override
            HttpConfig prodCfg = new HttpConfig("https://prod");
            job.setConfiguration(prodCfg, "production");
            job.setEnabled(false, "production");
            assertFalse(job.isEnabled("production"));
            assertFalse(job.isEnabled());         // back to no env enabled -> roll-up false
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

            // base timezone setter (one-arg) and the env-aware setter with a
            // null environment both set the base timezone
            job.setTimezone("America/New_York");
            assertEquals("America/New_York", job.timezone);
            job.setTimezone("America/Chicago", null);
            assertEquals("America/Chicago", job.timezone);
            // per-env timezone override is set on the environment, not the base,
            // and preserves the already-set per-env configuration
            job.setTimezone("Europe/London", "production");
            assertEquals("Europe/London", job.environments.get("production").timezone);
            assertSame(prodCfg, job.environments.get("production").configuration);
            assertEquals("America/Chicago", job.timezone);
            // getTimezone(env): override wins, else base (incl. unknown / null env)
            assertEquals("Europe/London", job.getTimezone("production"));
            assertEquals("America/Chicago", job.getTimezone("staging"));
            assertEquals("America/Chicago", job.getTimezone("unknown"));
            assertEquals("America/Chicago", job.getTimezone(null));
            // clearing a per-env override falls back to base on resolution
            job.setTimezone(null, "production");
            assertNull(job.environments.get("production").timezone);
            assertEquals("America/Chicago", job.getTimezone("production"));

            // toString lists the enabled environments, sorted
            job.setEnabled(true, "production");
            assertTrue(job.toString().contains("enabled_in=[development, production]"));
        }
    }

    @Test
    void enabledRollup_nullEnvironmentsMap_isFalse() {
        // Defensive: a caller that nulls the environments map yields a false
        // roll-up rather than an NPE.
        Job job = new Job(null, "k", "name", "0 * * * *", new HttpConfig("https://x"));
        job.environments = null;
        assertFalse(job.isEnabled());
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
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", cfg);
            job.description = "does a thing";
            assertNull(job.createdAt);
            job.save();
            assertNotNull(job.createdAt);
            assertEquals(1, job.version);
            assertEquals(HttpMethod.POST, job.configuration.method);
            assertEquals("http", job.type);
            assertEquals(JobKind.RECURRING, job.kind);  // parsed back
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
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://base"),
                    null, envs, "ALLOW");
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
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://base"));
            job.save();
            assertFalse(attrs().has("environments"), "empty environments map must be omitted");
        }
    }

    @Test
    void scheduleOneOff_sendsBirthEnvironmentHeaderAndDatetime() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
            Job oneoff = c.schedule(JOB_ID, "One-shot", when, new HttpConfig("https://x"), "development");
            oneoff.save();
            assertEquals("development", lastEnvHeader.get());                 // birth environment
            assertEquals(when.toString(), attrs().path("schedule").asText()); // datetime -> ISO-8601
        }
    }

    @Test
    void createManual_sendsNullScheduleOnTheWire() throws Exception {
        // A manual job has no schedule: ``newManualJob`` leaves schedule null and
        // the create body carries ``schedule: null`` (present, explicitly null).
        installFullHandler();
        try (JobsClient c = client()) {
            Job manual = c.newManualJob(JOB_ID, "Manual", new HttpConfig("https://x"));
            assertNull(manual.schedule);                       // no schedule supplied
            manual.setEnabled(true, "production");
            manual.save();
            JsonNode scheduleNode = attrs().path("schedule");
            assertTrue(scheduleNode.isNull(), "null schedule must be sent on the wire");
        }
    }

    @Test
    void createRecurring_sendsNoBirthEnvironmentHeader() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "Recurring", "0 * * * *", new HttpConfig("https://x"));
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
            assertTrue(job.isEnabled());                       // derived roll-up true
            assertTrue(job.enabled);                           // field mirrors the roll-up
            assertTrue(job.isEnabled("production"));
            assertFalse(job.isEnabled("development"));
            assertEquals(JobKind.RECURRING, job.kind);
            assertTrue(job.isRecurring());
            // production has no config override -> getConfiguration falls back to base
            assertEquals("https://base.example.com/hook", job.getConfiguration("production").url);
            // development has a config override
            assertEquals("https://dev.example.com/hook", job.getConfiguration("development").url);
            // per-env schedule override parsed for production; development inherits base
            assertEquals("30 3 * * *", job.environments.get("production").schedule);
            assertNull(job.environments.get("development").schedule);
            assertEquals("30 3 * * *", job.getSchedule("production"));
            assertEquals("0 2 * * *", job.getSchedule("development"));
            // read-only per-env next_run_at: present for enabled production, null otherwise
            assertNotNull(job.environments.get("production").nextRunAt);
            assertNull(job.environments.get("development").nextRunAt);
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
            assertNull(job.environments.get("production").schedule);
            assertNull(job.environments.get("production").configuration);
            assertNull(job.environments.get("production").nextRunAt);
        }
    }

    @Test
    void listParsesAndGetDelete() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            assertEquals(2, c.list().size());
            ListJobsInput in = new ListJobsInput();
            in.kind = JobKind.MANUAL;
            in.scheduled = true;
            in.name = "health";
            in.pageNumber = 1;
            in.pageSize = 10;
            assertEquals(2, c.list(in).size());
            assertTrue(lastQuery.get().contains("filter[name]=health"));
            assertTrue(lastQuery.get().contains("filter[kind]=manual"));   // JobKind -> wire value
            assertTrue(lastQuery.get().contains("filter[scheduled]=true"));
            // The dropped recurring filter is never emitted.
            assertFalse(lastQuery.get().contains("filter[recurring]"));
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
            // trigger is the raw wire string, equal to the RunTrigger constant's value
            assertEquals(RunTrigger.SCHEDULE.getValue(), got.trigger);
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
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            await(job.saveAsync(executor));
            assertEquals(1, job.version);
            await(job.deleteAsync(executor));
        }
    }

    @Test
    void jobSaveAsync_andDeleteAsync_commonPool() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            await(job.saveAsync());
            assertEquals(1, job.version);
            await(job.deleteAsync());
        }
    }

    @Test
    void jobSaveAsync_propagatesApiException() {
        installErrorHandler();
        try (JobsClient c = client()) {
            Job job = c.newManualJob("dup", "D", new HttpConfig("https://x"));
            assertApiFailure(job.saveAsync(executor));
        }
    }

    @Test
    void jobDeleteAsync_propagatesApiException() {
        installErrorHandler();
        try (JobsClient c = client()) {
            Job job = c.newManualJob(JOB_ID, "D", new HttpConfig("https://x"));
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
            Job dup = c.newManualJob("dup", "D", new HttpConfig("https://x"));
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
            Job job = c.newManualJob("k", "x", new HttpConfig("https://x"));
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
            Job job = c.newManualJob("k", "x", new HttpConfig("https://x"));
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
            assertNull(job.kind);                // absent kind -> null
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
                + "\"attributes\":{\"name\":\"D\",\"type\":\"http\","
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
    void conversions_enabledRollupDerivedFromEnvironments() throws Exception {
        // No top-level ``enabled`` on the wire; the roll-up is derived from the
        // environments map — true because ``production`` is enabled.
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"en\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"En\",\"schedule\":\"0 * * * *\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://en\"},"
                + "\"environments\":{\"staging\":{\"enabled\":false},"
                + "\"production\":{\"enabled\":true}},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("en");
            assertTrue(job.enabled);          // derived roll-up
            assertTrue(job.isEnabled());      // no-arg roll-up agrees
        }
    }

    @Test
    void conversions_perEnvironmentScheduleAndNextRunAt() throws Exception {
        // production: enabled, per-env schedule override + read-only next_run_at.
        // staging:    enabled, no schedule override (inherits base), null next run.
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"sched\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"Sched\",\"schedule\":\"0 2 * * *\","
                + "\"timezone\":\"America/New_York\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://x\"},"
                + "\"environments\":{"
                + "\"production\":{\"enabled\":true,\"schedule\":\"15 4 * * *\","
                + "\"timezone\":\"Europe/London\","
                + "\"next_run_at\":\"2026-06-05T04:15:00Z\"},"
                + "\"staging\":{\"enabled\":true,\"schedule\":null,\"timezone\":null,"
                + "\"next_run_at\":null}},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("sched");
            // per-env schedule override parsed; staging inherits (null override)
            assertEquals("15 4 * * *", job.environments.get("production").schedule);
            assertNull(job.environments.get("staging").schedule);
            // read-only next_run_at parsed where present, null otherwise
            assertNotNull(job.environments.get("production").nextRunAt);
            assertNull(job.environments.get("staging").nextRunAt);
            // getSchedule(env): override wins, else base, else base for unknown env
            assertEquals("15 4 * * *", job.getSchedule("production"));
            assertEquals("0 2 * * *", job.getSchedule("staging"));
            assertEquals("0 2 * * *", job.getSchedule("unknown"));
            assertEquals("0 2 * * *", job.getSchedule(null));
            // base + per-env timezone decode from the wire; staging inherits (null)
            assertEquals("America/New_York", job.timezone);
            assertEquals("Europe/London", job.environments.get("production").timezone);
            assertNull(job.environments.get("staging").timezone);
            // getTimezone(env): override wins, else base for everything else
            assertEquals("Europe/London", job.getTimezone("production"));
            assertEquals("America/New_York", job.getTimezone("staging"));
            assertEquals("America/New_York", job.getTimezone("unknown"));
            assertEquals("America/New_York", job.getTimezone(null));
        }
    }

    @Test
    void perEnvironmentScheduleSetter_baseAndOverride_emitsOnWrite() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://base"));
            // base schedule via env-aware setter (environment == null)
            job.setSchedule("30 1 * * *", null);
            assertEquals("30 1 * * *", job.schedule);
            // per-env schedule override is set in memory
            job.setEnabled(true, "production");
            job.setSchedule("45 6 * * *", "production");
            assertEquals("45 6 * * *", job.environments.get("production").schedule);
            // clearing a per-env override falls back to base on resolution
            job.setSchedule(null, "production");
            assertNull(job.environments.get("production").schedule);
            assertEquals("30 1 * * *", job.getSchedule("production"));
            // re-set the override, then save: it travels on the wire; next_run_at never does
            job.setSchedule("45 6 * * *", "production");
            // base timezone + a per-env timezone override on production
            job.setTimezone("America/Chicago");
            job.setTimezone("Europe/London", "production");
            // staging is enabled but carries no timezone override -> must be omitted
            job.setEnabled(true, "staging");
            job.save();
            JsonNode a = attrs();
            JsonNode envNode = a.path("environments");
            assertEquals("45 6 * * *", envNode.path("production").path("schedule").asText());
            assertFalse(envNode.path("production").has("next_run_at"),
                    "read-only next_run_at must never be written");
            // base timezone is sent when set
            assertEquals("America/Chicago", a.path("timezone").asText());
            // per-env timezone override travels on the wire for production
            assertEquals("Europe/London", envNode.path("production").path("timezone").asText());
            // staging has no timezone override; it must be omitted from the wire
            assertFalse(envNode.path("staging").has("timezone"),
                    "an environment without a timezone override must omit it");
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
            Job job = c.newManualJob(JOB_ID, "My Job", cfg);
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
        for (JobKind k : JobKind.values()) {
            assertEquals(k, JobKind.fromValue(k.getValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> JobKind.fromValue("nope"));
        for (RunTrigger t : RunTrigger.values()) {
            assertEquals(t, RunTrigger.fromValue(t.getValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> RunTrigger.fromValue("nope"));
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
        assertNull(empty.schedule);
        assertNull(empty.configuration);
        assertNull(empty.nextRunAt);
        JobEnvironment enabledOnly = new JobEnvironment(true);
        assertTrue(enabledOnly.enabled);
        assertNull(enabledOnly.schedule);
        assertNull(enabledOnly.configuration);
        assertNull(enabledOnly.nextRunAt);
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
        assertNull(lji.kind);
        assertNull(lji.scheduled);
        assertNull(lji.pageNumber);
        assertNull(lji.pageSize);
        ListRunsInput lri = new ListRunsInput();
        assertNull(lri.job);
        assertNull(lri.environments);
        assertNull(lri.pageSize);
        assertNull(lri.after);
    }
}
