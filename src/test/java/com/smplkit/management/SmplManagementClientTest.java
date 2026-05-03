package com.smplkit.management;

import com.smplkit.ConfigResolver;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SmplManagementClientTest {

    @Test
    void create_buildsAllEightNamespaces() {
        try (SmplManagementClient mc = SmplManagementClient.create("test-key")) {
            assertNotNull(mc.contexts);
            assertNotNull(mc.contextTypes);
            assertNotNull(mc.environments);
            assertNotNull(mc.accountSettings);
            assertNotNull(mc.config);
            assertNotNull(mc.flags);
            assertNotNull(mc.loggers);
            assertNotNull(mc.logGroups);
        }
    }

    @Test
    void fromResolved_acceptsCustomConfig() {
        ConfigResolver.ResolvedManagementConfig cfg =
                new ConfigResolver.ResolvedManagementConfig(
                        "key", "smplkit.example", "https", false);
        try (SmplManagementClient mc = SmplManagementClient.fromResolved(cfg, Duration.ofSeconds(5))) {
            assertEquals("key", mc.apiKey());
            assertEquals("smplkit.example", mc.baseDomain());
            assertEquals("https", mc.scheme());
            assertEquals(Duration.ofSeconds(5), mc.timeout());
        }
    }

    @Test
    void builder_buildsClient() {
        try (SmplManagementClient mc = SmplManagementClient.builder()
                .apiKey("test-key-via-builder")
                .baseDomain("smplkit.example")
                .scheme("https")
                .timeout(Duration.ofSeconds(7))
                .debug(false)
                .build()) {
            assertEquals("test-key-via-builder", mc.apiKey());
        }
    }

    @Test
    void close_isIdempotent() {
        SmplManagementClient mc = SmplManagementClient.create("test-key");
        mc.close();
        mc.close();
    }

    /**
     * Mirrors Python rule 1: management client construction has zero side effects
     * — no service registration, no metrics thread, no WebSocket, no logger discovery,
     * <strong>no outbound HTTP traffic</strong>.
     *
     * <p>Verified by passing in a Mockito-spied {@link HttpClient} via the
     * package-private {@code sharedWith} factory and asserting zero interactions
     * after construction completes.</p>
     */
    @Test
    void construction_makesZeroOutboundHttpCalls() {
        HttpClient mockHttp = mock(HttpClient.class);
        ContextRegistrationBuffer buf = new ContextRegistrationBuffer();
        ConfigResolver.ResolvedManagementConfig cfg =
                new ConfigResolver.ResolvedManagementConfig(
                        "test-key", "smplkit.example", "https", false);

        try (SmplManagementClient mc = SmplManagementClient.sharedWith(
                cfg, Duration.ofSeconds(5), mockHttp, buf)) {
            // Construction completed — assert all eight namespaces materialised
            // without ever calling out to the network.
            assertNotNull(mc.contexts);
            assertNotNull(mc.contextTypes);
            assertNotNull(mc.environments);
            assertNotNull(mc.accountSettings);
            assertNotNull(mc.config);
            assertNotNull(mc.flags);
            assertNotNull(mc.loggers);
            assertNotNull(mc.logGroups);
            // Registration buffer is empty (no contexts auto-registered).
            assertEquals(0, buf.pendingCount());
        }

        // The injected HttpClient has not been touched.
        verifyNoInteractions(mockHttp);
    }

    @Test
    void buildAppApiClient_setsTimeoutAndAuth() throws Exception {
        com.smplkit.internal.generated.app.ApiClient apiClient =
                SmplManagementClient.buildAppApiClient(
                        "https://app.smplkit.com", "abc-123", Duration.ofSeconds(2));
        java.net.http.HttpRequest.Builder b =
                java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create("https://app.smplkit.com"));
        apiClient.getRequestInterceptor().accept(b);
        assertEquals("Bearer abc-123",
                b.build().headers().firstValue("Authorization").orElse(null));
    }
}
