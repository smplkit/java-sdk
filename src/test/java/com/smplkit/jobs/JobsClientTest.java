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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * {@link AsyncRunsClient}, including the ADR-056 flat per-environment override
 * surface (sparse leaf overlays, object headers, {@code headers.<name>}
 * leaves, the body-carried environment on writes — a one-off job's birth
 * environment in the {@code environments} map and the run-now request body —
 * and {@code filter[environment]} on run reads).
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
        // when enabled, exercising the per-env nextRunAt mapping. Headers travel
        // as a name->value object (ADR-056).
        String prodNextRun = enabled ? "\"2026-06-05T00:00:00Z\"" : "null";
        return "{\"id\":\"" + id + "\",\"type\":\"job\",\"attributes\":{"
                + "\"name\":\"My Job\",\"description\":\"does a thing\","
                + "\"kind\":\"recurring\",\"type\":\"http\","
                + "\"schedule\":\"0 * * * *\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://api.example.com/hook\","
                + "\"headers\":{\"X-Api-Key\":\"secret\"},"
                + "\"body\":\"{}\",\"success_status\":\"2xx\",\"timeout\":30,"
                + "\"tls_verify\":true,\"ca_cert\":null},"
                + "\"environments\":{\"production\":{\"enabled\":" + enabled
                + ",\"next_run_at\":" + prodNextRun + "}},"
                + "\"concurrency_policy\":\"ALLOW\","
                + "\"created_at\":" + ts + ",\"updated_at\":" + ts + ","
                + "\"deleted_at\":null,\"version\":" + version + "}}";
    }

    // A job carrying a rich environments map (flat ADR-056 overlays): production
    // enabled with a per-env schedule override and read-only next_run_at;
    // development disabled with a per-env url + header leaf override.
    private static String jobResourceWithEnvs(String id, int version, boolean enabled, String kind) {
        return "{\"id\":\"" + id + "\",\"type\":\"job\",\"attributes\":{"
                + "\"name\":\"My Job\",\"description\":\"does a thing\","
                + "\"kind\":\"" + kind + "\",\"type\":\"http\","
                + "\"schedule\":\"0 2 * * *\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://base.example.com/hook\","
                + "\"headers\":{},\"body\":null,\"success_status\":\"2xx\",\"timeout\":30,"
                + "\"tls_verify\":true,\"ca_cert\":null},"
                + "\"environments\":{"
                + "\"production\":{\"enabled\":true,\"schedule\":\"30 3 * * *\","
                + "\"next_run_at\":\"2026-06-05T03:30:00Z\"},"
                + "\"development\":{\"enabled\":false,"
                + "\"url\":\"https://dev.example.com/hook\",\"headers.X-Env\":\"dev\"}"
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

    /**
     * The captured run-now request body parsed as a plain object (not a JSON:API
     * envelope). An empty body is captured as {@code null}; return an empty
     * object node so callers can assert the {@code environment} key is absent.
     */
    private JsonNode runNowBody() throws Exception {
        String body = lastBody.get();
        return body == null ? MAPPER.createObjectNode() : MAPPER.readTree(body);
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
            assertTrue(job.environment("production").enabled);
            assertEquals("ALLOW", job.concurrencyPolicy);
            // recurring jobs carry only their supplied environments map; no
            // birth-environment entry is injected.
            assertEquals(1, job.environments.size());
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
            assertTrue(job.environment("production").enabled);
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
            // No client env configured -> no birth-environment entry in the map.
            assertTrue(job.environments.isEmpty());
        }
        try (JobsClient c = clientWithEnv("production")) {
            Job job = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"));
            // Falls back to the client env, placed as an enabled map entry.
            assertEquals(Set.of("production"), job.environments.keySet());
            assertTrue(job.environment("production").enabled);
        }
    }

    @Test
    void schedule_fiveArg_setsBirthEnvironment() {
        OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
        try (JobsClient c = client()) {
            Job job = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"), "development");
            assertEquals(Set.of("development"), job.environments.keySet());
            assertTrue(job.environment("development").enabled);
        }
        try (JobsClient c = clientWithEnv("production")) {
            Job job = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"), null);
            // null falls back to the client env.
            assertEquals(Set.of("production"), job.environments.keySet());
            assertTrue(job.environment("production").enabled);
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
            assertEquals(Set.of("staging"), job.environments.keySet());
            assertTrue(job.environment("staging").enabled);
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
            assertTrue(recFull.environment("production").enabled);

            Job manual = c.newManualJob(JOB_ID, "Manual", new HttpConfig("https://x"));
            assertNull(manual.schedule);
            Job manualFull = c.newManualJob(JOB_ID, "Manual", new HttpConfig("https://x"),
                    "desc", envs, "ALLOW");
            assertEquals("desc", manualFull.description);
            assertTrue(manualFull.environment("production").enabled);

            assertEquals(when.toString(),
                    c.schedule(JOB_ID, "One", when, new HttpConfig("https://x")).schedule);
            assertTrue(c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"), "development")
                    .environment("development").enabled);
            Job oneoffFull = c.schedule(JOB_ID, "One", when, new HttpConfig("https://x"),
                    "desc", "ALLOW", "staging");
            assertTrue(oneoffFull.environment("staging").enabled);
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
    // Per-environment overrides on Job (ADR-056 flat leaves)
    // -----------------------------------------------------------------

    @Test
    void environmentAccessor_createsAndReuses_andRollup() {
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://base"));

            // environment() lazily creates the entry and reuses it thereafter
            assertTrue(job.environments.isEmpty());
            JobEnvironment prod = job.environment("production");
            assertSame(prod, job.environment("production"));
            assertEquals(1, job.environments.size());

            // pure-override reads: a fresh entry overrides nothing (all null)
            assertNull(prod.url);
            assertNull(prod.method);
            assertNull(prod.schedule);
            assertNull(prod.timezone);
            assertNull(prod.retryPolicy);
            assertNull(prod.getHeader("Authorization"));

            // set per-env leaves directly; the base configuration is untouched
            prod.enabled = true;
            prod.url = "https://prod.example.com/warm";
            prod.method = HttpMethod.PUT;
            prod.schedule = "0 */6 * * *";
            prod.timezone = "America/New_York";
            prod.setHeader("Authorization", "Bearer prod");
            assertEquals("https://base", job.configuration.url);          // base unchanged
            assertEquals("Bearer prod", prod.getHeader("Authorization"));

            // the roll-up reflects enablement after a save round-trip; in memory
            // the per-env flag is the source of truth
            assertTrue(job.environment("production").enabled);
            assertEquals(1, job.environments.size());

            // toString lists the enabled environments, sorted
            job.environment("development").enabled = true;
            assertTrue(job.toString().contains("enabled_in=[development, production]"));
        }
    }

    @Test
    void setRetryPolicy_baseObject_andPerEnv() {
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://x"));
            // base by id (direct field assignment)
            job.retryPolicy = "policy-a";
            assertEquals("policy-a", job.retryPolicy);
            // base by RetryPolicy object (its id is used)
            RetryPolicy policy = c.retryPolicies.new_("policy-obj", "P", 3, Backoff.FIXED, 5);
            job.setRetryPolicy(policy);
            assertEquals("policy-obj", job.retryPolicy);
            // per-env by id (direct field assignment), preserving other env fields
            JobEnvironment prod = job.environment("production");
            prod.enabled = true;
            prod.retryPolicy = "policy-prod";
            assertEquals("policy-prod", prod.retryPolicy);
            assertTrue(prod.enabled);
            // per-env by RetryPolicy object
            job.environment("development").setRetryPolicy(policy);
            assertEquals("policy-obj", job.environment("development").retryPolicy);
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
                    Map.of("X-Api-Key", "secret"));
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
    void create_emitsObjectHeaders_andFlatEnvironmentOverlays() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            HttpConfig base = new HttpConfig("https://base");
            base.setHeader("X-Base", "b");
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", base);
            // production: enabled only (no leaf override -> inherit base)
            job.environment("production").enabled = true;
            // development: enabled=false plus url + header leaf overrides
            JobEnvironment dev = job.environment("development");
            dev.url = "https://dev";
            dev.method = HttpMethod.GET;
            dev.setHeader("X-Env", "dev");
            job.save();
            JsonNode a = attrs();
            // base ``enabled`` roll-up is read-only — never written
            assertFalse(a.has("enabled"), "write body must not include base 'enabled'");
            // base headers travel as an object, not a list
            assertTrue(a.path("configuration").path("headers").isObject());
            assertEquals("b", a.path("configuration").path("headers").path("X-Base").asText());

            JsonNode envNode = a.path("environments");
            // production overlay carries only ``enabled`` (no inherited leaves)
            assertTrue(envNode.path("production").path("enabled").asBoolean());
            assertFalse(envNode.path("production").has("url"));
            // development overlay carries the leaf overrides as flat keys
            assertEquals("https://dev", envNode.path("development").path("url").asText());
            assertEquals("GET", envNode.path("development").path("method").asText());
            // header override is a flat ``headers.<name>`` leaf, not a nested object
            assertEquals("dev", envNode.path("development").path("headers.X-Env").asText());
            assertFalse(envNode.path("development").path("headers").isObject());
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
    void scheduleOneOff_sendsBirthEnvironmentInBodyAndDatetime() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
            Job oneoff = c.schedule(JOB_ID, "One-shot", when, new HttpConfig("https://x"), "development");
            oneoff.save();
            assertNull(lastEnvHeader.get());                                  // no request header
            assertEquals(when.toString(), attrs().path("schedule").asText()); // datetime -> ISO-8601
            // the birth environment travels as an enabled entry in the body's
            // environments map
            assertTrue(attrs().path("environments").path("development").path("enabled").asBoolean());
        }
    }

    @Test
    void scheduleOneOff_withoutEnvironment_sendsEmptyMap() throws Exception {
        // No explicit environment and no client default -> the environments map
        // is empty (and thus omitted), so a single-environment credential implies
        // the birth environment server-side.
        installFullHandler();
        try (JobsClient c = client()) {
            OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
            c.schedule(JOB_ID, "One-shot", when, new HttpConfig("https://x")).save();
            assertNull(lastEnvHeader.get());
            assertFalse(attrs().has("environments"), "empty environments map must be omitted");
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
            manual.environment("production").enabled = true;
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
    void update_sendsNoEnvironmentHeader() throws Exception {
        installFullHandler();
        try (JobsClient c = clientWithEnv("production")) {
            Job job = c.get(JOB_ID);          // createdAt set -> next save is an update
            assertNotNull(job.createdAt);
            job.name = "renamed";
            job.save();
            assertNull(lastEnvHeader.get());  // update carries no environment header
        }
    }

    // -----------------------------------------------------------------
    // Parse-from-wire — environments / recurring / enabled roll-up
    // -----------------------------------------------------------------

    @Test
    void getParsesEnvironments() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.get(JOB_ID);
            assertTrue(job.enabled);                           // derived roll-up true
            assertTrue(job.environments.get("production").enabled);
            assertFalse(job.environments.get("development").enabled);
            assertEquals(JobKind.RECURRING, job.kind);
            assertTrue(job.isRecurring());
            // production overrides nothing in its configuration -> leaves read null
            assertNull(job.environments.get("production").url);
            // base configuration is read directly from the job
            assertEquals("https://base.example.com/hook", job.configuration.url);
            // development has a flat url + header leaf override
            assertEquals("https://dev.example.com/hook", job.environments.get("development").url);
            assertEquals("dev", job.environments.get("development").getHeader("X-Env"));
            // per-env schedule override parsed for production; development overrides none
            assertEquals("30 3 * * *", job.environments.get("production").schedule);
            assertNull(job.environments.get("development").schedule);
            // read-only per-env next_run_at: present for enabled production, null otherwise
            assertNotNull(job.environments.get("production").nextRunAt);
            assertNull(job.environments.get("development").nextRunAt);
        }
    }

    @Test
    void getParsesNullEnvironmentEntry() throws Exception {
        // A null environment entry -> fromOverlay(null) yields a default override.
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
            assertNull(job.environments.get("production").url);
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
    void run_environmentBodyResolution() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Run r = c.run(JOB_ID);                 // no client env -> empty body
            assertEquals("MANUAL", r.trigger);
            assertEquals("production", r.environment);
            assertNull(lastEnvHeader.get());       // no request header anymore
            assertFalse(runNowBody().has("environment"), "empty body when no env resolves");
            c.run(JOB_ID, "development");           // explicit env in body
            assertEquals("development", runNowBody().path("environment").asText());
        }
        try (JobsClient c = clientWithEnv("production")) {
            c.run(JOB_ID);                          // client default env in body
            assertEquals("production", runNowBody().path("environment").asText());
            c.run(JOB_ID, "staging");               // explicit overrides client default
            assertEquals("staging", runNowBody().path("environment").asText());
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
            assertNull(lastEnvHeader.get());                                   // no request header
            assertEquals("production", runNowBody().path("environment").asText());

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
            assertNull(lastEnvHeader.get());                                   // no request header
            assertEquals("development", runNowBody().path("environment").asText());
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
        Run run = new Run(RUN_ID, JOB_ID, 1, "production", "MANUAL", null, null, null, "PENDING",
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
            assertFalse(job.enabled);            // default false when no env enabled
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
            assertTrue(job.configuration.headers.isEmpty());
        }
    }

    @Test
    void conversions_objectHeadersAndExplicitFalseTls() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"d\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"D\",\"type\":\"http\","
                + "\"schedule\":\"now\",\"concurrency_policy\":\"ALLOW\","
                + "\"configuration\":{\"method\":\"GET\",\"url\":\"https://d\","
                + "\"headers\":{\"H\":\"v\"},"
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
            assertEquals("v", job.configuration.getHeader("H"));
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
            assertNull(run.retry);            // retry absent -> null
            assertNull(run.failureReason);
            assertNull(run.scheduledFor);
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
        }
    }

    @Test
    void perEnvironmentScheduleAndTimezone_emitOnWrite() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 * * * *", new HttpConfig("https://base"));
            // base schedule + timezone via direct field assignment
            job.schedule = "30 1 * * *";
            job.timezone = "America/Chicago";
            // per-env schedule + timezone override on production
            JobEnvironment prod = job.environment("production");
            prod.enabled = true;
            prod.schedule = "45 6 * * *";
            prod.timezone = "Europe/London";
            // staging is enabled but carries no schedule/timezone override -> omitted
            job.environment("staging").enabled = true;
            job.save();
            JsonNode a = attrs();
            assertEquals("30 1 * * *", a.path("schedule").asText());
            assertEquals("America/Chicago", a.path("timezone").asText());
            JsonNode envNode = a.path("environments");
            assertEquals("45 6 * * *", envNode.path("production").path("schedule").asText());
            assertEquals("Europe/London", envNode.path("production").path("timezone").asText());
            assertFalse(envNode.path("production").has("next_run_at"),
                    "read-only next_run_at must never be written");
            assertFalse(envNode.path("staging").has("schedule"),
                    "an environment without a schedule override must omit it");
            assertFalse(envNode.path("staging").has("timezone"),
                    "an environment without a timezone override must omit it");
        }
    }

    // -----------------------------------------------------------------
    // JobEnvironment overlay serialize / parse (ADR-056) — direct coverage
    // -----------------------------------------------------------------

    @Test
    void jobEnvironment_toOverlay_emitsEnabledOnlyLeavesAndHeaders() {
        JobEnvironment env = new JobEnvironment(true);
        env.schedule = "0 2 * * *";
        env.timezone = "UTC";
        env.retryPolicy = "rp";
        env.url = "https://o";
        env.method = HttpMethod.PATCH;
        env.timeout = 12;
        env.body = "{}";
        env.successStatus = "201";
        env.tlsVerify = false;
        env.caCert = "PEM";
        env.setHeader("X-Foo.Bar", "v");          // dotted header name preserved on the wire
        env.nextRunAt = OffsetDateTime.parse("2026-06-05T00:00:00Z"); // read-only, never written

        Map<String, Object> overlay = env.toOverlay();
        assertEquals(Boolean.TRUE, overlay.get("enabled"));
        assertEquals("0 2 * * *", overlay.get("schedule"));
        assertEquals("UTC", overlay.get("timezone"));
        assertEquals("rp", overlay.get("retry_policy"));
        assertEquals("https://o", overlay.get("url"));
        assertEquals("PATCH", overlay.get("method"));      // method -> wire string
        assertEquals(12, overlay.get("timeout"));
        assertEquals("{}", overlay.get("body"));
        assertEquals("201", overlay.get("success_status"));
        assertEquals(Boolean.FALSE, overlay.get("tls_verify"));
        assertEquals("PEM", overlay.get("ca_cert"));
        assertEquals("v", overlay.get("headers.X-Foo.Bar"));
        assertFalse(overlay.containsKey("next_run_at"));   // never serialized
    }

    @Test
    void jobEnvironment_toOverlay_omitsUnsetLeaves() {
        Map<String, Object> overlay = new JobEnvironment().toOverlay();
        assertEquals(Boolean.FALSE, overlay.get("enabled"));
        assertEquals(1, overlay.size());                   // only ``enabled``
    }

    @Test
    void jobEnvironment_fromOverlay_parsesEveryLeaf_dottedHeader_ignoresUnknown() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("enabled", true);
        raw.put("schedule", "0 2 * * *");
        raw.put("timezone", "UTC");
        raw.put("retry_policy", "rp");
        raw.put("url", "https://o");
        raw.put("method", "DELETE");
        raw.put("timeout", 7);
        raw.put("body", "{}");
        raw.put("success_status", "204");
        raw.put("tls_verify", false);
        raw.put("ca_cert", "PEM");
        raw.put("headers.X-Foo.Bar", "v");   // dotted header name kept (split on first dot)
        raw.put("headers.", "ignored");      // empty header name after the dot -> dropped
        raw.put("unknown_leaf", "whatever");  // unknown -> ignored
        raw.put("next_run_at", "2026-06-05T00:00:00Z");

        JobEnvironment env = JobEnvironment.fromOverlay(raw);
        assertTrue(env.enabled);
        assertEquals("0 2 * * *", env.schedule);
        assertEquals("UTC", env.timezone);
        assertEquals("rp", env.retryPolicy);
        assertEquals("https://o", env.url);
        assertEquals(HttpMethod.DELETE, env.method);
        assertEquals(7, env.timeout);
        assertEquals("{}", env.body);
        assertEquals("204", env.successStatus);
        assertEquals(Boolean.FALSE, env.tlsVerify);
        assertEquals("PEM", env.caCert);
        assertEquals("v", env.getHeader("X-Foo.Bar"));
        assertEquals(1, env.headers.size());  // empty-name header dropped
        assertNotNull(env.nextRunAt);
    }

    @Test
    void jobEnvironment_fromOverlay_nullLeavesAndNullMap() {
        // null map -> default override
        JobEnvironment def = JobEnvironment.fromOverlay(null);
        assertFalse(def.enabled);
        assertNull(def.method);

        // explicit null leaves parse to null (no override); next_run_at variants
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("enabled", false);
        raw.put("method", null);          // null method -> null
        raw.put("timeout", null);         // non-Number -> null
        raw.put("tls_verify", null);      // non-Boolean -> null
        raw.put("next_run_at", null);     // null -> null
        JobEnvironment env = JobEnvironment.fromOverlay(raw);
        assertFalse(env.enabled);
        assertNull(env.method);
        assertNull(env.timeout);
        assertNull(env.tlsVerify);
        assertNull(env.nextRunAt);
    }

    @Test
    void jobEnvironment_fromOverlay_nextRunAt_emptyAndOffsetForms() {
        // empty string -> null
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("next_run_at", "");
        assertNull(JobEnvironment.fromOverlay(empty).nextRunAt);
        // explicit +00:00 offset (no trailing Z) -> parsed
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("next_run_at", "2026-06-05T00:00:00+00:00");
        assertNotNull(JobEnvironment.fromOverlay(offset).nextRunAt);
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
    void httpConfigConstructorsDefaultsAndHeaderHelpers() {
        HttpConfig empty = new HttpConfig();
        assertEquals(HttpMethod.POST, empty.method);
        assertEquals("", empty.url);
        assertEquals("2xx", empty.successStatus);
        assertEquals(30, empty.timeout);
        assertTrue(empty.tlsVerify);
        assertNull(empty.body);
        assertNull(empty.caCert);
        assertTrue(empty.headers.isEmpty());
        // header helpers
        empty.setHeader("Authorization", "Bearer t");
        assertEquals("Bearer t", empty.getHeader("Authorization"));
        assertNull(empty.getHeader("Missing"));

        HttpConfig fromUrl = new HttpConfig("https://x");
        assertEquals("https://x", fromUrl.url);

        HttpConfig full = new HttpConfig(HttpMethod.GET, "https://y", Map.of("k", "v"));
        assertEquals(HttpMethod.GET, full.method);
        assertEquals("https://y", full.url);
        assertEquals("v", full.getHeader("k"));

        // null headers map -> empty (defensive)
        HttpConfig nullHeaders = new HttpConfig(HttpMethod.GET, "https://z", null);
        assertTrue(nullHeaders.headers.isEmpty());
    }

    @Test
    void jobEnvironmentConstructorsAndHeaderHelpers() {
        JobEnvironment empty = new JobEnvironment();
        assertFalse(empty.enabled);
        assertNull(empty.schedule);
        assertNull(empty.url);
        assertNull(empty.method);
        assertNull(empty.nextRunAt);
        assertTrue(empty.headers.isEmpty());
        assertNull(empty.getHeader("X"));
        empty.setHeader("X", "y");
        assertEquals("y", empty.getHeader("X"));

        JobEnvironment enabledOnly = new JobEnvironment(true);
        assertTrue(enabledOnly.enabled);
        assertNull(enabledOnly.url);
        assertNull(enabledOnly.nextRunAt);
    }

    @Test
    void modelToStringsAndInputs() {
        Job job = new Job(null, "id1", "name1", "now", new HttpConfig("https://x"));
        assertTrue(job.toString().contains("Job("));
        Run run = new Run("r1", "j1", 1, "production", "MANUAL", null, null, null, "PENDING",
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
        ListJobsInput lji = new ListJobsInput();
        assertNull(lji.kind);
        assertNull(lji.scheduled);
        assertNull(lji.pageNumber);
        assertNull(lji.pageSize);
        ListRunsInput lri = new ListRunsInput();
        assertNull(lri.job);
        assertNull(lri.environments);
        assertNull(lri.triggers);
        assertFalse(lri.lastRunOnly);
        assertNull(lri.pageSize);
        assertNull(lri.after);
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
        // joinTriggers: null / empty -> null; valid -> comma-joined wire values
        assertNull(JobsConversions.joinTriggers(null));
        assertNull(JobsConversions.joinTriggers(List.of()));
        assertEquals("RETRY,SCHEDULE",
                JobsConversions.joinTriggers(List.of(RunTrigger.RETRY, RunTrigger.SCHEDULE)));
    }

    @Test
    void newConstructors_withTimezoneAndRetryPolicy() {
        OffsetDateTime when = OffsetDateTime.parse("2030-01-01T12:30:00Z");
        try (JobsClient c = client()) {
            Map<String, JobEnvironment> envs = new HashMap<>();
            envs.put("production", new JobEnvironment(true));
            Job rec = c.newRecurringJob(JOB_ID, "Rec", "0 2 * * *", new HttpConfig("https://x"),
                    "desc", envs, "ALLOW", "America/New_York", "policy-a");
            assertEquals("America/New_York", rec.timezone);
            assertEquals("policy-a", rec.retryPolicy);
            assertTrue(rec.environment("production").enabled);
            Job man = c.newManualJob(JOB_ID, "Man", new HttpConfig("https://x"),
                    "desc", envs, "ALLOW", "policy-b");
            assertNull(man.schedule);
            assertNull(man.timezone);
            assertEquals("policy-b", man.retryPolicy);
            Job off = c.schedule(JOB_ID, "Off", when, new HttpConfig("https://x"),
                    "desc", "ALLOW", "policy-c", "development");
            assertNull(off.timezone);
            assertEquals("policy-c", off.retryPolicy);
            assertTrue(off.environment("development").enabled);
        }
        // async delegating overloads (null environments -> empty map)
        try (AsyncJobsClient c = asyncClient()) {
            Job rec = c.newRecurringJob(JOB_ID, "Rec", "0 2 * * *", new HttpConfig("https://x"),
                    "desc", null, "ALLOW", "America/New_York", "policy-a");
            assertEquals("America/New_York", rec.timezone);
            assertEquals("policy-a", rec.retryPolicy);
            Job man = c.newManualJob(JOB_ID, "Man", new HttpConfig("https://x"),
                    "desc", null, "ALLOW", "policy-b");
            assertEquals("policy-b", man.retryPolicy);
            Job off = c.schedule(JOB_ID, "Off", when, new HttpConfig("https://x"),
                    "desc", "ALLOW", "policy-c", "development");
            assertEquals("policy-c", off.retryPolicy);
        }
    }

    // -----------------------------------------------------------------
    // retry_policy on the wire (base + per-env) and parse-back
    // -----------------------------------------------------------------

    @Test
    void retryPolicy_serializesOnJobAndPerEnv() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 2 * * *", new HttpConfig("https://base"));
            job.retryPolicy = "base-policy";
            JobEnvironment prod = job.environment("production");
            prod.enabled = true;
            prod.retryPolicy = "prod-policy";
            // staging is enabled but carries no retry-policy override -> must be omitted
            job.environment("staging").enabled = true;
            job.save();
            JsonNode a = attrs();
            assertEquals("base-policy", a.path("retry_policy").asText());
            JsonNode envNode = a.path("environments");
            assertEquals("prod-policy", envNode.path("production").path("retry_policy").asText());
            assertFalse(envNode.path("staging").has("retry_policy"),
                    "an environment without a retry-policy override must omit it");
        }
    }

    @Test
    void retryPolicy_omittedFromWireWhenUnset() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.newRecurringJob(JOB_ID, "My Job", "0 2 * * *", new HttpConfig("https://base"));
            job.save();
            assertFalse(attrs().has("retry_policy"), "null retry_policy must be omitted from the wire");
        }
    }

    @Test
    void getParsesRetryPolicy() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"rp\",\"type\":\"job\","
                + "\"attributes\":{\"name\":\"RP\",\"schedule\":\"0 2 * * *\","
                + "\"retry_policy\":\"base-policy\","
                + "\"configuration\":{\"method\":\"POST\",\"url\":\"https://x\"},"
                + "\"environments\":{\"production\":{\"enabled\":true,\"retry_policy\":\"prod-policy\"},"
                + "\"staging\":{\"enabled\":true}},"
                + "\"created_at\":\"2026-06-04T00:00:00Z\"}}}"));
        try (JobsClient c = client()) {
            Job job = c.get("rp");
            assertEquals("base-policy", job.retryPolicy);
            assertEquals("prod-policy", job.environments.get("production").retryPolicy);
            assertNull(job.environments.get("staging").retryPolicy);
        }
    }

    // -----------------------------------------------------------------
    // Run-list trigger / lastRunOnly filters + RunRetry parse
    // -----------------------------------------------------------------

    @Test
    void runsList_triggerAndLastRunOnlyFilters() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            // default: neither filter on the wire
            c.runs.list();
            assertFalse(lastQuery.get() != null && lastQuery.get().contains("filter[trigger]="));
            assertFalse(lastQuery.get() != null && lastQuery.get().contains("last_run_only="));
            // triggers comma-joined (any-of) + last_run_only=true
            ListRunsInput in = new ListRunsInput();
            in.triggers = List.of(RunTrigger.RETRY, RunTrigger.SCHEDULE);
            in.lastRunOnly = true;
            c.runs.list(in);
            assertTrue(lastQuery.get().contains("filter[trigger]=RETRY,SCHEDULE"));
            assertTrue(lastQuery.get().contains("last_run_only=true"));
            // empty triggers list -> filter omitted
            ListRunsInput empty = new ListRunsInput();
            empty.triggers = List.of();
            c.runs.list(empty);
            assertFalse(lastQuery.get() != null && lastQuery.get().contains("filter[trigger]="));
        }
    }

    @Test
    void job_listRuns_triggerAndLastRunOnly() throws Exception {
        installFullHandler();
        try (JobsClient c = client()) {
            Job job = c.get(JOB_ID);
            // listRuns(environment, lastRunOnly)
            assertEquals(1, job.listRuns("production", true).size());
            assertTrue(lastQuery.get().contains("filter[job]=" + JOB_ID));
            assertTrue(lastQuery.get().contains("filter[environment]=production"));
            assertTrue(lastQuery.get().contains("last_run_only=true"));
            // full overload with triggers + paging
            assertEquals(1, job.listRuns("production", List.of(RunTrigger.RETRY), true, 5, "cur").size());
            assertTrue(lastQuery.get().contains("filter[trigger]=RETRY"));
            assertTrue(lastQuery.get().contains("page[size]=5"));
            assertTrue(lastQuery.get().contains("last_run_only=true"));
        }
    }

    @Test
    void conversions_runWithRetry_parsesRunRetry() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":{\"id\":\"" + RUN_ID + "\",\"type\":\"run\","
                + "\"attributes\":{\"job\":\"" + JOB_ID + "\",\"environment\":\"production\","
                + "\"trigger\":\"RETRY\",\"status\":\"PENDING\","
                + "\"retry\":{\"of\":\"" + RUN_ID + "\",\"attempt\":2}}}}"));
        try (JobsClient c = client()) {
            Run run = c.runs.get(RUN_ID);
            assertEquals("RETRY", run.trigger);
            assertNotNull(run.retry);
            assertEquals(RUN_ID, run.retry.of);
            assertEquals(2, run.retry.attempt);
            assertTrue(run.retry.toString().contains("RunRetry("));
        }
    }
}
