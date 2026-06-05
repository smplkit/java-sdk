package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiClient;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Smpl Jobs management surface ({@code mgmt.jobs}).
 *
 * <p>Stubs the jobs service via the JDK's built-in HttpServer; no real
 * network. The wrapper layer here must reach 100% line coverage to satisfy
 * the SDK CI gate.</p>
 */
class JobsManagementClientTest {

    private static final String JOB_ID = "my-job";
    private static final String RUN_ID = "8f2b1c4a-0000-4a1b-9c3d-1e2f3a4b5c6d";

    private HttpServer server;
    private String baseUrl;
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
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private JobsManagementClient client() {
        return new JobsManagementClient("sk_test", Map.of(), Duration.ofSeconds(5), baseUrl);
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
        AtomicInteger jobWrites = new AtomicInteger();
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
            jobWrites.incrementAndGet();
        });
    }

    // -----------------------------------------------------------------
    // Active-record save / delete via Job
    // -----------------------------------------------------------------

    @Test
    void createThenUpdateViaSave() throws Exception {
        installFullHandler();
        JobsManagementClient c = client();
        HttpConfig cfg = new HttpConfig(HttpMethod.POST, "https://api.example.com/hook",
                List.of(new HttpHeader("X-Api-Key", "secret")));
        cfg.body = "{}";
        Job job = c.newJob(JOB_ID, "My Job", "0 * * * *", cfg);
        job.description = "does a thing";
        assertNull(job.createdAt);
        job.save();
        assertNotNull(job.createdAt);
        assertEquals(1, job.version);
        job.name = "renamed";
        job.save();
        assertEquals(2, job.version);
    }

    @Test
    void getListDelete() throws Exception {
        installFullHandler();
        JobsManagementClient c = client();
        assertEquals(HttpMethod.POST, c.get(JOB_ID).configuration.method);
        assertEquals(2, c.list().size());
        ListJobsInput in = new ListJobsInput();
        in.enabled = true;
        in.pageNumber = 1;
        in.pageSize = 10;
        assertEquals(2, c.list(in).size());
        c.delete(JOB_ID);
        c.get(JOB_ID).delete();  // active-record delete with bound client
    }

    @Test
    void runRunsUsage() throws Exception {
        installFullHandler();
        JobsManagementClient c = client();
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
        assertEquals("POST", got.request.get("method"));
        assertNotNull(got.scheduledFor);
        assertNotNull(got.startedAt);
        assertNotNull(got.finishedAt);
        Run canceled = c.runs.cancel(RUN_ID);
        assertEquals("CANCELED", canceled.status);
        assertEquals(RUN_ID, canceled.rerunOf);
        assertEquals("RERUN", c.runs.rerun(RUN_ID).trigger);
        assertEquals(12, c.usage().runsUsed);
    }

    @Test
    void errorMapping() {
        handler.set(ex -> {
            int code = ex.getRequestMethod().equals("GET") ? 404 : 409;
            respondJson(ex, code, "{\"errors\":[{\"detail\":\"x\"}]}");
        });
        JobsManagementClient c = client();
        ApiException notFound = assertThrows(ApiException.class, () -> c.get("missing"));
        assertEquals(404, notFound.getCode());
        Job dup = c.newJob("dup", "D", "now", new HttpConfig("https://x"));
        ApiException conflict = assertThrows(ApiException.class, dup::save);
        assertEquals(409, conflict.getCode());
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
        JobsManagementClient c = client();
        Job job = c.newJob("k", "x", "now", new HttpConfig("https://x"));
        job.id = null;
        assertThrows(IllegalStateException.class, job::delete);
    }

    @Test
    void create_withoutId_throws() {
        JobsManagementClient c = client();
        Job job = new Job(c, null, "x", "now", new HttpConfig("https://x"));
        assertThrows(IllegalStateException.class, job::save);
    }

    @Test
    void update_withoutId_throws() {
        JobsManagementClient c = client();
        Job job = c.newJob("k", "x", "now", new HttpConfig("https://x"));
        job.id = null;
        assertThrows(IllegalStateException.class, () -> c.update(job));
    }

    // -----------------------------------------------------------------
    // Conversions: defaults and wire round-trips
    // -----------------------------------------------------------------

    @Test
    void getJob_defaultsWhenWireOmitsOptionalFields() throws Exception {
        // A minimal job: no description, no enabled, no type, no
        // concurrency_policy, no timestamps, null configuration. Exercises the
        // default branches in fromResource / configurationFromGen.
        handler.set(ex -> respondJson(ex, 200,
                "{\"data\":{\"id\":\"" + JOB_ID + "\",\"type\":\"job\",\"attributes\":{"
                        + "\"name\":\"n\",\"schedule\":\"now\"}}}"));
        JobsManagementClient c = client();
        Job job = c.get(JOB_ID);
        assertTrue(job.enabled);
        assertEquals("http", job.type);
        assertEquals("ALLOW", job.concurrencyPolicy);
        assertNotNull(job.configuration);
        assertEquals(HttpMethod.POST, job.configuration.method);
        assertEquals(30, job.configuration.timeout);
        assertTrue(job.configuration.tlsVerify);
        assertEquals("", job.configuration.url);
        assertTrue(job.configuration.headers.isEmpty());
        assertNull(job.createdAt);
    }

    @Test
    void saveJob_sendsBodyTimeoutTlsAndCaCert_onWire() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        handler.set(ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            body.set(new String(in));
            respondJson(ex, 201, "{\"data\":" + jobResource(JOB_ID, true, 1, true) + "}");
        });
        JobsManagementClient c = client();
        HttpConfig cfg = new HttpConfig("https://x");
        cfg.body = "{\"k\":1}";
        cfg.timeout = 45;
        cfg.tlsVerify = false;
        cfg.caCert = "-----BEGIN CERTIFICATE-----\nfoo\n-----END CERTIFICATE-----";
        cfg.headers.add(new HttpHeader("Authorization", "Bearer t"));
        Job job = c.newJob(JOB_ID, "n", "now", cfg);
        job.save();
        String wire = body.get();
        assertTrue(wire.contains("\"timeout\":45"), wire);
        assertTrue(wire.contains("\"tls_verify\":false"), wire);
        assertTrue(wire.contains("\"ca_cert\":\"-----BEGIN CERTIFICATE-----"), wire);
        assertTrue(wire.contains("\"body\":\"{\\\"k\\\":1}\""), wire);
    }

    @Test
    void getJob_readsBodyAndTimeout_fromWire() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":" + jobResource(JOB_ID, true, 1, true) + "}"));
        Job job = client().get(JOB_ID);
        assertEquals("{}", job.configuration.body);
        assertEquals(30, job.configuration.timeout);
        assertEquals("X-Api-Key", job.configuration.headers.get(0).name);
        assertNotNull(job.nextRunAt);
    }

    @Test
    void listEmptyData_returnsEmptyList() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        assertTrue(client().list(new ListJobsInput()).isEmpty());
    }

    @Test
    void runsListEmptyData_returnsEmptyList() throws Exception {
        handler.set(ex -> respondJson(ex, 200, "{\"data\":[]}"));
        assertTrue(client().runs.list(new ListRunsInput()).isEmpty());
    }

    // -----------------------------------------------------------------
    // Model constructors, enums, toString
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
        assertEquals(30, empty.timeout);
        assertTrue(empty.tlsVerify);
        assertNull(empty.body);
        assertNull(empty.caCert);
        HttpConfig fromUrl = new HttpConfig("https://x");
        assertEquals("https://x", fromUrl.url);
        HttpConfig full = new HttpConfig(HttpMethod.GET, "https://y",
                List.of(new HttpHeader("k", "v")));
        assertEquals(HttpMethod.GET, full.method);
        assertEquals(1, full.headers.size());
    }

    @Test
    void modelToStringsAndInputs() {
        Job job = new Job(null, "id1", "name1", "now", new HttpConfig("https://x"));
        assertTrue(job.toString().contains("Job("));
        Run run = new Run("r1", "j1", 1, "MANUAL", null, null, "PENDING",
                null, null, null, null, null, null, null, null, null, null);
        assertTrue(run.toString().contains("Run("));
        Usage usage = new Usage("2026-06", 1, 2, 3, 4);
        assertTrue(usage.toString().contains("Usage("));
        assertEquals(3, usage.activeJobs);
        assertEquals(4, usage.activeJobsLimit);
        assertEquals(2, usage.runsIncluded);
        HttpHeader h = new HttpHeader("a", "b");
        assertEquals("a", h.name);
        assertEquals("b", h.value);
        ListJobsInput lji = new ListJobsInput();
        assertNull(lji.enabled);
        ListRunsInput lri = new ListRunsInput();
        assertNull(lri.job);
    }
}
