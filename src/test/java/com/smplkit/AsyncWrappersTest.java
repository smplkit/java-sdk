package com.smplkit;

import com.smplkit.config.AsyncConfigManagement;
import com.smplkit.config.Config;
import com.smplkit.config.ConfigClient;
import com.smplkit.config.ConfigManagement;
import com.smplkit.flags.AsyncFlagsManagement;
import com.smplkit.flags.Flag;
import com.smplkit.flags.FlagsClient;
import com.smplkit.flags.FlagsManagement;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.logging.AsyncLogGroupsClient;
import com.smplkit.logging.AsyncLoggersClient;
import com.smplkit.logging.LogGroup;
import com.smplkit.logging.LogGroupsClient;
import com.smplkit.logging.LoggersClient;
import com.smplkit.logging.LoggingClient;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Cross-cutting coverage for the thin async wrappers
 * ({@code AsyncFlagsManagement}, {@code AsyncConfigManagement},
 * {@code AsyncLoggersClient}, {@code AsyncLogGroupsClient}). Each wrapper
 * exposes synchronous factory methods plus {@code CompletableFuture}-bearing
 * CRUD methods — we exercise both sides so the delegation is exercised end-to-end.
 */
class AsyncWrappersTest {

    private static final Executor INLINE = Runnable::run;

    // -------------------------------------------------------- FlagsManagement

    @Test
    void asyncFlagsManagement_factoryMethodsDelegateToSync() {
        AsyncFlagsManagement mgmt = newAsyncFlagsManagement();

        Flag<Boolean> b1 = mgmt.newBooleanFlag("b1", false);
        Flag<Boolean> b2 = mgmt.newBooleanFlag("b2", false, "Two", "desc");
        Flag<String> s1 = mgmt.newStringFlag("s1", "x");
        Flag<String> s2 = mgmt.newStringFlag("s2", "x", "Two", "desc");
        Flag<String> s3 = mgmt.newStringFlag("s3", "x", "Three", "desc",
                List.of(Map.of("name", "X", "value", "x")));
        Flag<Number> n1 = mgmt.newNumberFlag("n1", 0);
        Flag<Number> n2 = mgmt.newNumberFlag("n2", 0, "Two", "desc");
        Flag<Number> n3 = mgmt.newNumberFlag("n3", 0, "Three", "desc",
                List.of(Map.of("name", "Zero", "value", 0)));
        Flag<Object> j1 = mgmt.newJsonFlag("j1", Map.of());
        Flag<Object> j2 = mgmt.newJsonFlag("j2", Map.of(), "Two", "desc");
        Flag<Object> j3 = mgmt.newJsonFlag("j3", Map.of(), "Three", "desc",
                List.of(Map.of("name", "X", "value", Map.of())));

        for (Flag<?> f : List.of(b1, b2, s1, s2, s3, n1, n2, n3, j1, j2, j3)) {
            assertNotNull(f);
            assertNotNull(f.getId());
        }
    }

    @Test
    void asyncFlagsManagement_crudReturnsCompletableFuture() {
        AsyncFlagsManagement mgmt = newAsyncFlagsManagement();

        // We don't call get()/list()/delete() because they would hit the wire;
        // simply confirm the futures exist (delegated to executor) and have
        // the right shape.
        assertNotNull(mgmt.list());
        assertNotNull(mgmt.get("anything"));
        assertNotNull(mgmt.delete("anything"));
    }

    private AsyncFlagsManagement newAsyncFlagsManagement() {
        FlagsApi api = mock(FlagsApi.class);
        FlagsClient client = new FlagsClient(api, null, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        return new AsyncFlagsManagement(client.management(), INLINE);
    }

    // -------------------------------------------------------- ConfigManagement

    @Test
    void asyncConfigManagement_factoryMethodsDelegate() {
        AsyncConfigManagement mgmt = newAsyncConfigManagement();

        Config a = mgmt.new_("a");
        Config b = mgmt.new_("b", "B Display", "desc", "parent-slug");
        Config c = mgmt.new_("c", "C Display", "desc", a);

        assertEquals("a", a.getId());
        assertEquals("B Display", b.getName());
        assertEquals("a", c.getParent());
    }

    @Test
    void asyncConfigManagement_crudReturnsCompletableFuture() {
        AsyncConfigManagement mgmt = newAsyncConfigManagement();
        assertNotNull(mgmt.list());
        assertNotNull(mgmt.get("anything"));
        assertNotNull(mgmt.delete("anything"));
    }

    private AsyncConfigManagement newAsyncConfigManagement() {
        ConfigsApi api = mock(ConfigsApi.class);
        ConfigClient client = new ConfigClient(api, HttpClient.newHttpClient(), "test-key");
        ConfigManagement sync = client.management();
        return new AsyncConfigManagement(sync, INLINE);
    }

    // -------------------------------------------------------- LoggersClient

    @Test
    void asyncLoggersClient_factoryAndCrudFutures() {
        AsyncLoggersClient async = newAsyncLoggersClient();

        assertEquals("svc.a", async.new_("svc.a").getId());
        assertEquals("svc.b", async.new_("svc.b", false).getId());

        // CompletableFuture-returning CRUD
        assertNotNull(async.list());
        assertNotNull(async.get("anything"));
        assertNotNull(async.delete("anything"));
        assertNotNull(async.registerSources(List.of()));
    }

    @Test
    void asyncLogGroupsClient_factoryAndCrudFutures() {
        AsyncLogGroupsClient async = newAsyncLogGroupsClient();

        assertEquals("g1", async.new_("g1").getId());
        LogGroup g2 = async.new_("g2", "G Two", "parent");
        assertEquals("G Two", g2.getName());
        assertEquals("parent", g2.getGroup());

        assertNotNull(async.list());
        assertNotNull(async.get("anything"));
        assertNotNull(async.delete("anything"));
    }

    private AsyncLoggersClient newAsyncLoggersClient() {
        LoggersClient sync = newLoggersClient();
        return new AsyncLoggersClient(sync, INLINE);
    }

    private AsyncLogGroupsClient newAsyncLogGroupsClient() {
        LogGroupsClient sync = newLogGroupsClient();
        return new AsyncLogGroupsClient(sync, INLINE);
    }

    private LoggersClient newLoggersClient() {
        return new LoggersClient(newLoggingClient());
    }

    private LogGroupsClient newLogGroupsClient() {
        return new LogGroupsClient(newLoggingClient());
    }

    private LoggingClient newLoggingClient() {
        LoggersApi loggersApi = mock(LoggersApi.class);
        LogGroupsApi logGroupsApi = mock(LogGroupsApi.class);
        return new LoggingClient(loggersApi, logGroupsApi, HttpClient.newHttpClient(), "test-key");
    }

    // ---- Executor sanity: a CompletableFuture is supplied via the supplied executor

    @Test
    void asyncWrapper_usesSuppliedExecutor() throws Exception {
        AtomicInteger count = new AtomicInteger();
        Executor counting = r -> {
            count.incrementAndGet();
            r.run();
        };

        AsyncConfigManagement mgmt = new AsyncConfigManagement(
                new ConfigClient(mock(ConfigsApi.class), HttpClient.newHttpClient(), "test-key").management(),
                counting);

        CompletableFuture<List<Config>> fut = mgmt.list();
        try {
            fut.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // list() will fail because configs API mock returns null; we only
            // care that the executor was used to schedule the work.
        }
        assertTrue(count.get() > 0);
    }
}
