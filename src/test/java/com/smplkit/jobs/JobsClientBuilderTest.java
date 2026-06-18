package com.smplkit.jobs;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link JobsClientBuilder} and the standalone construction paths
 * of {@link JobsClient} / {@link AsyncJobsClient} ({@code create},
 * {@code builder}, {@code fromResolved}, the transport-owning constructor, and
 * the {@code ownsTransport} branch of {@code close()}).
 *
 * <p>Construction performs no network I/O — the builder resolves credentials
 * and wires an {@code ApiClient} without dialing. The no-arg / profile cases
 * inject {@code SMPLKIT_API_KEY} via the environment (reflective override,
 * mirroring {@code SmplClientTest} / {@code AuditClientBuilderTest}) so they do
 * not depend on a {@code ~/.smplkit} file being present (CI runners have
 * none).</p>
 */
class JobsClientBuilderTest {

    @Test
    void builder_allSetters_buildsTransportOwningClient() {
        try (JobsClient client = JobsClient.builder()
                .apiKey("sk_test")
                .environment("production")
                .baseDomain("example.com")
                .scheme("https")
                .timeout(Duration.ofSeconds(10))
                .debug(true)
                .header("X-Custom", "v")
                .extraHeaders(Map.of("X-Extra", "w"))
                .build()) {
            assertNotNull(client.runs);
        }
    }

    @Test
    void builder_profileSetter_resolvesCredentials() throws Exception {
        // Exercise the profile() setter. The env var supplies the api_key so
        // resolution succeeds without a ~/.smplkit file.
        setEnv("SMPLKIT_API_KEY", "sk_api_test_profile");
        try (JobsClient client = JobsClient.builder().profile("default").build()) {
            assertNotNull(client.runs);
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void create_withApiKey_buildsStandaloneClient() {
        try (JobsClient client = JobsClient.create("sk_test")) {
            assertNotNull(client.runs);
        }
    }

    @Test
    void create_default_resolvesApiKeyFromEnv() throws Exception {
        // No-arg create() resolves credentials from the standard sources; inject
        // the api_key via the environment so the path is hermetic.
        setEnv("SMPLKIT_API_KEY", "sk_api_test_create");
        try (JobsClient client = JobsClient.create()) {
            assertNotNull(client.runs);
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void standaloneClient_close_isIdempotent() {
        JobsClient client = JobsClient.create("sk_test");
        client.close();
        // Second close must not throw (ownsTransport branch, no persistent resources).
        client.close();
    }

    @Test
    void asyncCreate_withApiKey_andWrap() {
        try (AsyncJobsClient client = AsyncJobsClient.create("sk_test")) {
            assertNotNull(client.runs);
            assertNotNull(client.sync());
            assertNotNull(client.executor());
        }
    }

    @Test
    void asyncCreate_default_resolvesApiKeyFromEnv() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_api_test_async_create");
        try (AsyncJobsClient client = AsyncJobsClient.create()) {
            assertNotNull(client.runs);
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void asyncWrap_defaultExecutor() {
        JobsClient sync = JobsClient.create("sk_test");
        try (AsyncJobsClient client = AsyncJobsClient.wrap(sync)) {
            assertSame(sync, client.sync());
            assertNotNull(client.executor());
        }
    }

    // -----------------------------------------------------------------
    // builder null-guards
    // -----------------------------------------------------------------

    @Test
    void builder_setters_rejectNull() {
        JobsClientBuilder b = JobsClient.builder();
        assertThrows(NullPointerException.class, () -> b.profile(null));
        assertThrows(NullPointerException.class, () -> b.apiKey(null));
        assertThrows(NullPointerException.class, () -> b.environment(null));
        assertThrows(NullPointerException.class, () -> b.baseDomain(null));
        assertThrows(NullPointerException.class, () -> b.scheme(null));
        assertThrows(NullPointerException.class, () -> b.timeout(null));
        assertThrows(NullPointerException.class, () -> b.extraHeaders(null));
        assertThrows(NullPointerException.class, () -> b.header(null, "v"));
        assertThrows(NullPointerException.class, () -> b.header("n", null));
    }

    @Test
    void builder_settersReturnSameInstanceForChaining() {
        JobsClientBuilder b = JobsClient.builder();
        assertSame(b, b.profile("default"));
        assertSame(b, b.apiKey("sk_test"));
        assertSame(b, b.environment("production"));
        assertSame(b, b.baseDomain("example.com"));
        assertSame(b, b.scheme("https"));
        assertSame(b, b.timeout(Duration.ofSeconds(5)));
        assertSame(b, b.debug(false));
        assertSame(b, b.header("k", "v"));
        assertSame(b, b.extraHeaders(Map.of("a", "b")));
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
