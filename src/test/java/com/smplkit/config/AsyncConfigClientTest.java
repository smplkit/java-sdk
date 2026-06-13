package com.smplkit.config;

import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigBulkRequest;
import com.smplkit.internal.generated.config.model.ConfigBulkResponse;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AsyncConfigClient}: every async method schedules its
 * synchronous counterpart on the supplied executor; subscribe / onChange stay
 * synchronous. Backed by a wired sync {@link ConfigClient} over a mocked
 * {@code ConfigsApi} so nothing touches the network.
 */
class AsyncConfigClientTest {

    private ConfigsApi mockApi;
    private ConfigClient sync;
    private AsyncConfigClient async;
    private AtomicInteger executorUses;

    @BeforeEach
    void setUp() throws ApiException {
        mockApi = Mockito.mock(ConfigsApi.class);
        sync = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        sync.setEnvironment("production");
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class)))
                .thenReturn(new ConfigBulkResponse());

        executorUses = new AtomicInteger();
        Executor inline = r -> {
            executorUses.incrementAndGet();
            r.run();
        };
        async = AsyncConfigClient.wrap(sync, inline);
    }

    @AfterEach
    void tearDown() {
        async.close();
    }

    private ConfigResponse singleResponse(String id, String name) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName(name);
        ConfigResource res = new ConfigResource();
        res.setId(id);
        res.setType(ConfigResource.TypeEnum.CONFIG);
        res.setAttributes(attrs);
        ConfigResponse resp = new ConfigResponse();
        resp.setData(res);
        return resp;
    }

    private static void seedCache(ConfigClient client, String id, Map<String, Object> values) {
        try {
            Field connected = ConfigClient.class.getDeclaredField("connected");
            connected.setAccessible(true);
            connected.setBoolean(client, true);
            Field f = ConfigClient.class.getDeclaredField("configCache");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) f.get(client);
            Map<String, Map<String, Object>> mutable = new HashMap<>(cache);
            mutable.put(id, values);
            f.set(client, mutable);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    @Test
    void sync_returnsUnderlyingClient() {
        assertSame(sync, async.sync());
    }

    @Test
    void executor_returnsBackingExecutor() {
        assertNotNull(async.executor());
    }

    // -----------------------------------------------------------------------
    // CRUD — synchronous new_ variants
    // -----------------------------------------------------------------------

    @Test
    void new_variants_delegateToSync() {
        assertEquals("svc", async.new_("svc").getId());
        assertEquals("My Name", async.new_("svc", "My Name", "d", (String) null).getName());
        Config parentRef = async.new_("p2", null, null, (String) null);
        assertNotNull(async.new_("c2", null, null, parentRef));
    }

    // -----------------------------------------------------------------------
    // CRUD — async get / list / delete
    // -----------------------------------------------------------------------

    @Test
    void get_schedulesOnExecutor() throws Exception {
        when(mockApi.getConfig("svc")).thenReturn(singleResponse("svc", "Svc"));
        Config c = async.get("svc").get();
        assertEquals("svc", c.getId());
        assertTrue(executorUses.get() >= 1);
    }

    @Test
    void list_noArgs_schedulesOnExecutor() throws Exception {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));
        List<Config> result = async.list().get();
        assertTrue(result.isEmpty());
        assertTrue(executorUses.get() >= 1);
    }

    @Test
    void delete_schedulesOnExecutor() throws Exception {
        async.delete("svc").get();
        verify(mockApi).deleteConfig("svc");
    }

    // -----------------------------------------------------------------------
    // Discovery passthroughs
    // -----------------------------------------------------------------------

    @Test
    void discovery_passthroughs() throws Exception {
        async.registerConfig("billing", "svc", "prod", null, "Billing", null);
        async.registerConfigItem("billing", "max_seats", "NUMBER", 5, "seats");
        assertEquals(1, async.pendingCount());
        async.flush().get();
        assertEquals(0, async.pendingCount());
        verify(mockApi).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    // -----------------------------------------------------------------------
    // Live surface — bind (async), subscribe (sync), getValue (async)
    // -----------------------------------------------------------------------

    @Test
    void bind_schedulesOnExecutor_andReturnsSameInstance() throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("k", "v");
        Map<String, Object> bound = async.bind("billing", cfg).get();
        assertSame(cfg, bound);
        assertTrue(executorUses.get() >= 1);
    }

    @Test
    void bind_withParent_schedulesOnExecutor() throws Exception {
        Map<String, Object> parent = new HashMap<>();
        parent.put("base", 1);
        async.bind("common", parent).get();
        Map<String, Object> child = new HashMap<>();
        child.put("own", 2);
        Map<String, Object> bound = async.bind("billing", child, parent).get();
        assertSame(child, bound);
    }

    @Test
    void subscribe_isSynchronous_readsCache() {
        seedCache(sync, "billing", Map.of("k", "v"));
        LiveConfigProxy proxy = async.subscribe("billing");
        assertEquals("v", proxy.get("k"));
    }

    @Test
    void getValue_twoArg_schedulesOnExecutor() throws Exception {
        seedCache(sync, "billing", Map.of("k", "v"));
        assertEquals("v", async.getValue("billing", "k").get());
        assertTrue(executorUses.get() >= 1);
    }

    @Test
    void getValue_withDefault_schedulesOnExecutor() throws Exception {
        seedCache(sync, "billing", Map.of("k", "v"));
        assertEquals("fallback", async.getValue("billing", "missing", "fallback").get());
    }

    @Test
    void refresh_schedulesOnExecutor() throws Exception {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));
        async.refresh().get();
        assertTrue(sync.isConnected());
    }

    // -----------------------------------------------------------------------
    // onChange — synchronous, records listeners without connecting
    // -----------------------------------------------------------------------

    @Test
    void onChange_allThreeForms_recordWithoutConnecting() {
        async.onChange(e -> {});
        async.onChange("cfg", e -> {});
        async.onChange("cfg", "key", e -> {});
        assertEquals(3, listenerCount(sync));
        assertFalse(sync.isConnected(), "onChange must not trigger a connection");
    }

    // -----------------------------------------------------------------------
    // Construction helpers (no I/O — build resolves creds only)
    // -----------------------------------------------------------------------

    @Test
    void create_withApiKey_buildsStandalone() {
        try (AsyncConfigClient c = AsyncConfigClient.create("standalone-key")) {
            assertNotNull(c.sync());
            assertNotNull(c.executor());
        }
    }

    @Test
    void wrap_sync_usesCommonPool() {
        ConfigsApi api = Mockito.mock(ConfigsApi.class);
        ConfigClient s = new ConfigClient(api, HttpClient.newHttpClient(), "k");
        try (AsyncConfigClient a = AsyncConfigClient.wrap(s)) {
            assertSame(s, a.sync());
            assertNotNull(a.executor());
        }
    }

    private static int listenerCount(ConfigClient client) {
        try {
            Field f = ConfigClient.class.getDeclaredField("listeners");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> listeners = (List<Object>) f.get(client);
            return listeners.size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
