package com.smplkit.config;

import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Targeted coverage for residual line-gaps in the config area that the broad
 * functional suites do not yet drive: the no-arg {@code create()} factories
 * (resolved via the {@code SMPLKIT_API_KEY} env seam, so hermetic on CI),
 * the double-checked {@code ensureConnected()} fast-return, {@code refreshAsync()},
 * the {@code ensureAncestorsCached} already-cached / fetch-returns-null
 * {@code continue} arms, the static/transient field skip in {@code allInstanceFields},
 * and the null arms of the {@link Config} constructor / {@code toChainEntry} /
 * {@code parseResource} ternaries.
 *
 * <p>WebSocket-free: a Mockito {@link ConfigsApi} backs every client; WS events
 * are driven through the {@code simulate*} seams.</p>
 */
class ConfigCoverageGapsTest {

    private static final String CHILD_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PARENT_ID = "660e8400-e29b-41d4-a716-446655440001";

    private ConfigsApi mockApi;
    private ConfigClient client;

    @BeforeEach
    void setUp() throws ApiException {
        mockApi = Mockito.mock(ConfigsApi.class);
        client = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        client.setEnvironment("production");
    }

    private ConfigResource resource(String id, String name, String parent,
                                    Map<String, ConfigItemDefinition> items) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName(name != null ? name : "");
        if (parent != null) attrs.setParent(parent);
        if (items != null) attrs.setItems(items);
        ConfigResource r = new ConfigResource();
        r.setId(id);
        r.setType(ConfigResource.TypeEnum.CONFIG);
        r.setAttributes(attrs);
        return r;
    }

    private static ConfigItemDefinition itemDef(Object value, ConfigItemDefinition.TypeEnum type) {
        ConfigItemDefinition d = new ConfigItemDefinition();
        d.setValue(value);
        d.setType(type);
        return d;
    }

    private ConfigResponse single(ConfigResource r) {
        ConfigResponse resp = new ConfigResponse();
        resp.setData(r);
        return resp;
    }

    private ConfigListResponse listOf(ConfigResource... rs) {
        ConfigListResponse resp = new ConfigListResponse();
        resp.setData(List.of(rs));
        return resp;
    }

    // -----------------------------------------------------------------------
    // ConfigClient.create() — no-arg factory (line 175), via env seam
    // -----------------------------------------------------------------------

    @Test
    void create_noArg_resolvesFromEnv() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_config_create_noarg");
        try (ConfigClient c = ConfigClient.create()) {
            assertNotNull(c);
            assertFalse(c.isConnected());
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    // -----------------------------------------------------------------------
    // AsyncConfigClient.create() — no-arg factory (line 40), via env seam
    // -----------------------------------------------------------------------

    @Test
    void asyncCreate_noArg_resolvesFromEnv() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_async_config_create_noarg");
        try (AsyncConfigClient c = AsyncConfigClient.create()) {
            assertNotNull(c.sync());
            assertNotNull(c.executor());
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    // -----------------------------------------------------------------------
    // ensureConnected() double-checked fast-return (line 584)
    // -----------------------------------------------------------------------

    @Test
    void ensureConnected_concurrentCallersHitInnerDoubleCheckReturn() throws Exception {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));

        // The inner double-checked `return` (line 584) is reached only when a
        // second caller passes the outer `connected` guard while it is still
        // false, then observes `connected == true` after taking the lock. Force
        // that race deterministically by gating BOTH callers on the client's
        // own `cacheLock`:
        //
        //   1. The test holds `cacheLock`.
        //   2. Two worker threads call ensureConnected. With `connected` still
        //      false they sail past the outer guard (579) and then block trying
        //      to enter `synchronized (cacheLock)` (582) because the test owns
        //      the monitor.
        //   3. Once both workers are confirmed BLOCKED on the monitor, the test
        //      releases the lock. One worker enters, connects (sets
        //      connected=true), and exits; the other then enters and observes
        //      the inner `connected` re-check as true -> line 584 return.
        Object cacheLock = getCacheLock(client);

        java.util.concurrent.CountDownLatch bothPastOuterGuard =
                new java.util.concurrent.CountDownLatch(2);
        // The ensure-started hook runs before the outer guard; use it only to
        // signal that a worker has reached ensureConnected.
        client.setEnsureStarted(bothPastOuterGuard::countDown);

        Runnable call = client::ensureConnected;
        Thread t1 = new Thread(call, "ensure-1");
        Thread t2 = new Thread(call, "ensure-2");

        synchronized (cacheLock) {
            t1.start();
            t2.start();
            // Wait until both workers have entered ensureConnected (run the hook
            // and passed the outer guard with connected==false).
            assertTrue(bothPastOuterGuard.await(5, TimeUnit.SECONDS),
                    "both workers should reach ensureConnected");
            // Spin until both are actually BLOCKED on the cacheLock monitor.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline
                    && !(t1.getState() == Thread.State.BLOCKED
                         && t2.getState() == Thread.State.BLOCKED)) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, t1.getState(), "t1 should block on cacheLock");
            assertEquals(Thread.State.BLOCKED, t2.getState(), "t2 should block on cacheLock");
        } // release cacheLock -> one worker connects, the other hits line 584

        t1.join(5000);
        t2.join(5000);
        assertFalse(t1.isAlive());
        assertFalse(t2.isAlive());
        assertTrue(client.isConnected());
        // listConfigs ran exactly once: the winner connected, the loser took the
        // inner double-checked return without re-fetching.
        verify(mockApi, times(1))
                .listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    // -----------------------------------------------------------------------
    // refreshAsync() (lines 1272-1273)
    // -----------------------------------------------------------------------

    @Test
    void refreshAsync_completesAndReFetches() throws Exception {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));

        client.ensureConnected(); // first list
        client.refreshAsync().get(5, TimeUnit.SECONDS); // second list via async

        verify(mockApi, times(2))
                .listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    // -----------------------------------------------------------------------
    // ensureAncestorsCached: parent already cached -> continue (line 1490)
    // -----------------------------------------------------------------------

    @Test
    void wsConfigChanged_parentAlreadyCached_skipsRefetch() throws ApiException {
        // Initial connect caches BOTH child (parent=PARENT_ID) and the parent.
        ConfigResource child = resource(CHILD_ID, "Child", PARENT_ID,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)));
        ConfigResource parent = resource(PARENT_ID, "Parent", null,
                Map.of("b", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)));
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listOf(child, parent));
        // The scoped fetch for the changed child returns the same child.
        when(mockApi.getConfig(CHILD_ID)).thenReturn(single(child));

        client.ensureConnected();
        client.simulateConfigChanged(Map.of("id", CHILD_ID));

        // Child re-fetched once (the scoped change). Parent never re-fetched
        // because it was already present in the raw cache (the line-1490
        // `continue`): getConfig(PARENT_ID) must not be called.
        verify(mockApi, times(1)).getConfig(CHILD_ID);
        verify(mockApi, never()).getConfig(PARENT_ID);
    }

    // -----------------------------------------------------------------------
    // ensureAncestorsCached: fetchConfig(parent) == null -> continue (line 1494)
    // -----------------------------------------------------------------------

    @Test
    void wsConfigChanged_missingParentFetchReturnsNull_continues() throws ApiException {
        // Initial connect caches only a resolvable root config (no parent), so the
        // child with a dangling parent arrives ONLY via the config_changed event.
        ConfigResource root = resource("root-cfg", "Root", null,
                Map.of("r", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)));
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listOf(root));
        // The changed child declares a parent that does not exist on the server.
        ConfigResource child = resource(CHILD_ID, "Child", PARENT_ID,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)));
        when(mockApi.getConfig(CHILD_ID)).thenReturn(single(child));
        // The absent parent is fetched but the server returns null -> ensureAncestorsCached
        // hits the `parent == null` continue, and the subsequent re-resolve is caught.
        when(mockApi.getConfig(PARENT_ID)).thenReturn(null);

        client.ensureConnected();
        // Must not throw even though the parent resolves to null.
        assertDoesNotThrow(() -> client.simulateConfigChanged(Map.of("id", CHILD_ID)));

        verify(mockApi, atLeastOnce()).getConfig(PARENT_ID);
    }

    // -----------------------------------------------------------------------
    // allInstanceFields: static / transient field is skipped (line 1102)
    // -----------------------------------------------------------------------

    /** POJO carrying a static and a transient field that must NOT be registered. */
    public static final class WithStaticAndTransient {
        public static int SHARED = 99;     // static -> skipped (line 1102)
        public transient int scratch = 7;  // transient -> skipped (line 1102)
        public int kept = 1;               // instance, non-transient -> registered
    }

    @Test
    void bind_pojoWithStaticAndTransientFields_skipsThem() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));
        when(mockApi.bulkRegisterConfigs(any())).thenReturn(
                new com.smplkit.internal.generated.config.model.ConfigBulkResponse());
        client.setService("svc");

        WithStaticAndTransient bound = client.bind("flags", new WithStaticAndTransient());
        client.flush();

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.smplkit.internal.generated.config.model.ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        Map<String, ConfigItemDefinition> items =
                captor.getValue().getConfigs().get(0).getItems();

        assertTrue(items.containsKey("kept"));
        assertFalse(items.containsKey("SHARED"), "static field must be skipped");
        assertFalse(items.containsKey("scratch"), "transient field must be skipped");
        assertNotNull(bound);
    }

    // -----------------------------------------------------------------------
    // Config constructor: null items / environments arms (lines 55, 56)
    // -----------------------------------------------------------------------

    @Test
    void config_constructor_nullItemsAndEnvironments_defaultToEmpty() {
        Config cfg = new Config(client, CHILD_ID, "Name", null, null, null, null, null, null);
        assertTrue(cfg.items().isEmpty());
        assertTrue(cfg.environments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // parseResource: null id with non-null name (line 1607 mixed arm)
    // -----------------------------------------------------------------------

    @Test
    void parseResource_nullIdNonNullName_idEmptyNameKept() throws ApiException {
        ConfigResource r = resource(null, "Kept Name", null, Map.of());
        when(mockApi.getConfig(any())).thenReturn(single(r));

        Config cfg = client.get("whatever");
        assertEquals("", cfg.getId());
        assertEquals("Kept Name", cfg.getName());
    }

    @Test
    void parseResource_nonNullIdNullName_idKeptNameEmpty() throws ApiException {
        ConfigResource r = resource(CHILD_ID, null, null, Map.of());
        when(mockApi.getConfig(any())).thenReturn(single(r));

        Config cfg = client.get("whatever");
        assertEquals(CHILD_ID, cfg.getId());
        assertEquals("", cfg.getName());
    }

    // -----------------------------------------------------------------------
    // toChainEntry: null id arm (Config line 539) via buildChain over a
    // bound chain whose entry has a null id.
    // -----------------------------------------------------------------------

    @Test
    void buildChain_configWithNullId_usesEmptyStringId() {
        // A Config with a null id, with itself as the only chain entry: buildChain
        // returns [self] and toChainEntry must coalesce the null id to "".
        Config noId = new Config(client, null, "NoId", null, null,
                new HashMap<>(Map.of("k", Map.of("value", 1))), new HashMap<>(), null, null);
        List<Resolver.ChainEntry> chain = noId.buildChain(List.of(noId));
        assertEquals(1, chain.size());
        assertEquals("", chain.get(0).id);
    }

    // -----------------------------------------------------------------------
    // Reflective seams
    // -----------------------------------------------------------------------

    private static Object getCacheLock(ConfigClient c) throws Exception {
        var f = ConfigClient.class.getDeclaredField("cacheLock");
        f.setAccessible(true);
        return f.get(c);
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((Map<String, String>) field.get(env)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearEnv(String key) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((Map<String, String>) field.get(env)).remove(key);
    }
}
