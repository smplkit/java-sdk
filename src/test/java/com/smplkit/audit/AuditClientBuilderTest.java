package com.smplkit.audit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link AuditClientBuilder} and the standalone
 * {@link AuditClient} construction path ({@code create}, {@code builder},
 * {@code fromResolved}, the transport-owning constructor, and the
 * {@code ownsTransport} branch of {@code close()}).
 *
 * <p>Construction performs no network I/O — the builder resolves credentials
 * and wires an {@code ApiClient} without dialing. The no-arg / profile cases
 * inject {@code SMPLKIT_API_KEY} via the environment (reflective override,
 * mirroring {@code SmplClientTest}) so they do not depend on a
 * {@code ~/.smplkit} file being present (CI runners have none).</p>
 */
class AuditClientBuilderTest {

    @Test
    void builder_allSetters_buildsTransportOwningClient() {
        try (AuditClient client = AuditClient.builder()
                .apiKey("sk_test")
                .environment("production")
                .baseDomain("example.com")
                .scheme("https")
                .timeout(Duration.ofSeconds(10))
                .debug(true)
                .header("X-Custom", "v")
                .extraHeaders(Map.of("X-Extra", "w"))
                .build()) {
            assertNotNull(client.events());
            assertNotNull(client.resourceTypes());
            assertNotNull(client.eventTypes());
            assertNotNull(client.categories());
            assertNotNull(client.forwarders());
        }
    }

    @Test
    void builder_profileSetter_resolvesCredentials() throws Exception {
        // Exercise the profile() setter. The env var supplies the api_key so
        // resolution succeeds without a ~/.smplkit file.
        setEnv("SMPLKIT_API_KEY", "sk_api_test_profile");
        try (AuditClient client = AuditClient.builder().profile("default").build()) {
            assertNotNull(client.forwarders());
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void builder_noEnvironment_omitsHeaderButStillBuilds() {
        try (AuditClient client = AuditClient.builder().apiKey("sk_test").build()) {
            assertNotNull(client.events());
        }
    }

    @Test
    void create_withApiKey_buildsStandaloneClient() {
        try (AuditClient client = AuditClient.create("sk_test")) {
            assertNotNull(client.forwarders());
        }
    }

    @Test
    void create_default_resolvesApiKeyFromEnv() throws Exception {
        // No-arg create() resolves credentials from the standard sources;
        // inject the api_key via the environment so the path is hermetic.
        setEnv("SMPLKIT_API_KEY", "sk_api_test_create");
        try (AuditClient client = AuditClient.create()) {
            assertNotNull(client.events());
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void standaloneClient_close_isIdempotentAndTearsDownBuffer() {
        AuditClient client = AuditClient.create("sk_test");
        client.close();
        // Second close must not throw (ownsTransport branch + buffer already closed).
        client.close();
    }

    // -----------------------------------------------------------------
    // builder null-guards
    // -----------------------------------------------------------------

    @Test
    void builder_setters_rejectNull() {
        AuditClientBuilder b = AuditClient.builder();
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
        AuditClientBuilder b = AuditClient.builder();
        assertSame(b, b.apiKey("sk_test"));
        assertSame(b, b.environment("staging"));
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
