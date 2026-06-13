package com.smplkit.logging;

import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;
import com.smplkit.LogLevel;
import com.smplkit.logging.adapters.DiscoveredLogger;
import com.smplkit.logging.adapters.LoggingAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coverage for the thin async wrappers — {@link AsyncLoggingClient},
 * {@link AsyncLoggersClient}, {@link AsyncLogGroupsClient}. Each wrapper delegates
 * to a wired sync {@link LoggingClient} backed by mocked generated {@code *Api}s;
 * the {@code CompletableFuture}s run on an inline executor so the delegation is
 * exercised synchronously and deterministically (no {@code Thread.sleep}).
 */
class AsyncLoggingClientTest {

    private static final Executor INLINE = Runnable::run;

    private LoggersApi mockLoggersApi;
    private LogGroupsApi mockLogGroupsApi;
    private LoggingClient sync;
    private AsyncLoggingClient async;

    @BeforeEach
    void setUp() {
        mockLoggersApi = mock(LoggersApi.class);
        mockLogGroupsApi = mock(LogGroupsApi.class);
        sync = new LoggingClient(mockLoggersApi, mockLogGroupsApi,
                HttpClient.newHttpClient(), "test-key");
        sync.setEnvironment("production");
        sync.setService("test-service");
        async = AsyncLoggingClient.wrap(sync, INLINE);
    }

    // -------------------------------------------------------- wrap / accessors

    @Test
    void wrap_exposesSyncAndExecutor() {
        assertSame(sync, async.sync());
        assertSame(INLINE, async.executor());
        assertNotNull(async.loggers);
        assertNotNull(async.logGroups);
    }

    @Test
    void wrap_defaultExecutor_usesCommonPool() {
        AsyncLoggingClient a = AsyncLoggingClient.wrap(sync);
        assertSame(sync, a.sync());
        assertNotNull(a.executor());
    }

    @Test
    void create_withApiKey_buildsStandaloneAsync() {
        try (AsyncLoggingClient a = AsyncLoggingClient.create("sk_test")) {
            assertNotNull(a.sync());
            assertNotNull(a.executor());
            assertFalse(a.isInstalled());
        }
    }

    @Test
    void create_default_resolvesFromEnv() throws Exception {
        // Inject an API key via the environment so the no-arg factory resolves
        // credentials hermetically (CI has no ~/.smplkit).
        setEnv("SMPLKIT_API_KEY", "sk_async_logging_create");
        try (AsyncLoggingClient a = AsyncLoggingClient.create()) {
            assertNotNull(a.sync());
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
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

    // -------------------------------------------------------- live surface

    @Test
    void install_thenIsInstalled_thenClose() throws Exception {
        stubEmptyResponses();
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of());
        async.registerAdapter(adapter);

        assertFalse(async.isInstalled());
        async.install().join();
        assertTrue(async.isInstalled());

        async.close();
        assertFalse(async.isInstalled());
        verify(adapter).uninstallHook();
    }

    @Test
    void onChange_afterInstall_firesViaDelegate() throws Exception {
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of(new DiscoveredLogger("com.acme", "INFO")));
        async.registerAdapter(adapter);
        stubManagedLogger("com.acme", "INFO");

        async.install().join();

        AtomicReference<LoggerChangeEvent> global = new AtomicReference<>();
        AtomicReference<LoggerChangeEvent> keyed = new AtomicReference<>();
        async.onChange(global::set);
        async.onChange("com.acme", keyed::set);

        when(mockLoggersApi.getLogger("com.acme"))
                .thenReturn(buildLoggerResponse("com.acme", "WARN", true));
        sync.simulateLoggerChanged(java.util.Map.of("id", "com.acme"));

        assertNotNull(global.get());
        assertEquals(LogLevel.WARN, global.get().level());
        assertNotNull(keyed.get());
        assertEquals("com.acme", keyed.get().id());
    }

    @Test
    void onChange_beforeInstall_throwsNotInstalled() {
        assertThrows(com.smplkit.errors.NotInstalledError.class, () -> async.onChange(e -> {}));
        assertThrows(com.smplkit.errors.NotInstalledError.class, () -> async.onChange("k", e -> {}));
    }

    @Test
    void refresh_afterInstall_refetches() throws Exception {
        stubEmptyResponses();
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of());
        async.registerAdapter(adapter);
        async.install().join();

        async.refresh().join();

        verify(mockLoggersApi, times(2)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    // -------------------------------------------------------- AsyncLoggersClient

    @Test
    void loggers_factoryMethods() {
        assertEquals("svc.a", async.loggers.new_("svc.a").getId());
        assertTrue(async.loggers.new_("svc.a").isManaged());
        assertFalse(async.loggers.new_("svc.b", false).isManaged());
    }

    @Test
    void loggers_registerAndPendingAndFlush() throws Exception {
        async.loggers.register(new LoggerSource("a", LogLevel.INFO, "svc", "env"));
        async.loggers.register(List.of(new LoggerSource("b", LogLevel.WARN, "svc", "env")));
        assertEquals(2, async.loggers.pendingCount());

        async.loggers.flush().join();
        verify(mockLoggersApi).bulkRegisterLoggers(any());
        assertEquals(0, async.loggers.pendingCount());
    }

    @Test
    void loggers_listGetDelete_returnFutures() throws Exception {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggerList("a"));
        when(mockLoggersApi.getLogger("a")).thenReturn(buildLoggerResponse("a", "INFO", false));

        assertEquals(1, async.loggers.list().join().size());
        assertEquals(1, async.loggers.list(1, 10).join().size());
        assertEquals("a", async.loggers.get("a").join().getId());
        async.loggers.delete("a").join();
        verify(mockLoggersApi).deleteLogger("a");
    }

    // -------------------------------------------------------- AsyncLogGroupsClient

    @Test
    void logGroups_factoryMethods() {
        assertEquals("g1", async.logGroups.new_("g1").getId());
        LogGroup g2 = async.logGroups.new_("g2", "G Two", "parent");
        assertEquals("G Two", g2.getName());
        assertEquals("parent", g2.getGroup());
    }

    @Test
    void logGroups_listGetDelete_returnFutures() throws Exception {
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(groupList("g"));
        when(mockLogGroupsApi.getLogGroup("g")).thenReturn(buildGroupResponse("g", "INFO"));

        assertEquals(1, async.logGroups.list().join().size());
        assertEquals(1, async.logGroups.list(1, 10).join().size());
        assertEquals("g", async.logGroups.get("g").join().getId());
        async.logGroups.delete("g").join();
        verify(mockLogGroupsApi).deleteLogGroup("g");
    }

    // -------------------------------------------------------- executor usage

    @Test
    void asyncWrapper_usesSuppliedExecutor() throws Exception {
        AtomicInteger count = new AtomicInteger();
        Executor counting = r -> { count.incrementAndGet(); r.run(); };
        AsyncLoggingClient a = AsyncLoggingClient.wrap(sync, counting);

        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggerList());
        a.loggers.list().join();

        assertTrue(count.get() > 0);
    }

    // -------------------------------------------------------- helpers

    private void stubEmptyResponses() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggerList());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(groupList());
    }

    private void stubManagedLogger(String key, String level) throws ApiException {
        LoggerResource lr = buildLoggerResource(key, level);
        lr.getAttributes().setManaged(true);
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(resp);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupList());
    }

    private static com.smplkit.internal.generated.logging.model.LogLevel tolerantLogLevel(String value) {
        if (value == null) return null;
        try { return com.smplkit.internal.generated.logging.model.LogLevel.fromValue(value); }
        catch (IllegalArgumentException e) { return null; }
    }

    private LoggerResource buildLoggerResource(String id, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, null, now, now);
        attrs.setName(id);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));
        attrs.setManaged(false);

        LoggerResource r = new LoggerResource();
        r.setId(id);
        r.setType(LoggerResource.TypeEnum.LOGGER);
        r.setAttributes(attrs);
        return r;
    }

    private LoggerResponse buildLoggerResponse(String id, String level, boolean managed) {
        LoggerResource r = buildLoggerResource(id, level);
        r.getAttributes().setManaged(managed);
        LoggerResponse resp = new LoggerResponse();
        resp.setData(r);
        return resp;
    }

    private LoggerListResponse loggerList(String... ids) {
        LoggerListResponse resp = new LoggerListResponse();
        List<LoggerResource> data = new ArrayList<>();
        for (String id : ids) data.add(buildLoggerResource(id, "INFO"));
        resp.setData(data);
        return resp;
    }

    private LogGroupResource buildGroupResource(String id, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(now, now);
        attrs.setName(id);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));

        LogGroupResource r = new LogGroupResource();
        r.setId(id);
        r.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        r.setAttributes(attrs);
        return r;
    }

    private LogGroupResponse buildGroupResponse(String id, String level) {
        LogGroupResponse resp = new LogGroupResponse();
        resp.setData(buildGroupResource(id, level));
        return resp;
    }

    private LogGroupListResponse groupList(String... ids) {
        LogGroupListResponse resp = new LogGroupListResponse();
        List<LogGroupResource> data = new ArrayList<>();
        for (String id : ids) data.add(buildGroupResource(id, "INFO"));
        resp.setData(data);
        return resp;
    }
}
