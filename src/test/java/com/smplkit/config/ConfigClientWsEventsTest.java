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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ConfigClient WS event behaviors driven through the simulate seams:
 * <ul>
 *   <li>{@code config_changed}: scoped fetch, transitive ancestor fetch, diff-based listener firing.</li>
 *   <li>{@code config_deleted}: remove from cache, re-resolve (no scoped fetch).</li>
 *   <li>{@code configs_changed}: full list fetch, diff-based firing.</li>
 * </ul>
 */
class ConfigClientWsEventsTest {

    private ConfigsApi mockApi;
    private ConfigClient configClient;

    @BeforeEach
    void setUp() throws ApiException {
        mockApi = Mockito.mock(ConfigsApi.class);
        configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        configClient.setEnvironment("production");

        ConfigListResponse emptyList = new ConfigListResponse();
        emptyList.setData(List.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(emptyList);
    }

    // -----------------------------------------------------------------------
    // config_changed — scoped fetch, diff-based
    // -----------------------------------------------------------------------

    @Test
    void configChanged_contentChanged_scopedFetch_listenerFires() throws ApiException {
        ConfigResource initial = makeResource("my-config", "My Config",
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(initial));
        configClient.ensureConnected();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("my-config", received::set);

        ConfigResource updated = makeResource("my-config", "My Config",
                Map.of("timeout", itemDef(60, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        when(mockApi.getConfig("my-config")).thenReturn(singleResponse(updated));

        configClient.simulateConfigChanged(Map.of("id", "my-config"));

        assertNotNull(received.get(), "Listener should fire when content changes");
        assertEquals("my-config", received.get().configId());
        assertEquals("websocket", received.get().source());
        assertEquals(30, received.get().oldValue());
        assertEquals(60, received.get().newValue());
        verify(mockApi, times(1)).getConfig("my-config");
        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void configChanged_contentUnchanged_listenerDoesNotFire() throws ApiException {
        ConfigResource initial = makeResource("my-config", "My Config",
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(initial));
        configClient.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange("my-config", e -> count.incrementAndGet());

        when(mockApi.getConfig("my-config")).thenReturn(singleResponse(initial));
        configClient.simulateConfigChanged(Map.of("id", "my-config"));

        assertEquals(0, count.get(), "Listener should not fire when content is unchanged");
    }

    @Test
    void configChanged_missingId_fallsBackToFullRefresh() throws ApiException {
        configClient.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        // No "id" → routed to handleConfigsChanged (full refresh), no scoped fetch.
        configClient.simulateConfigChanged(Map.of());

        assertEquals(0, count.get());
        verify(mockApi, never()).getConfig(any());
        // listConfigs: once for connect + once for the fallback full refresh.
        verify(mockApi, times(2)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void configChanged_fetchReturnsNull_isNoOp() throws ApiException {
        configClient.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        ConfigResponse nullData = new ConfigResponse();
        nullData.setData(null);
        when(mockApi.getConfig("gone")).thenReturn(nullData);

        configClient.simulateConfigChanged(Map.of("id", "gone"));

        assertEquals(0, count.get());
        verify(mockApi, times(1)).getConfig("gone");
    }

    // -----------------------------------------------------------------------
    // MANDATORY regression: ensureAncestorsCached transitively fetches an
    // uncached parent so the child's inherited value survives the rebuild.
    // -----------------------------------------------------------------------

    @Test
    void configChanged_uncachedAncestor_isTransitivelyFetched_inheritanceSurvives() throws Exception {
        // Initial connect: only the child is present, and (at this point) it has
        // no parent so the initial resolution is self-contained. Its ancestors
        // are NOT in the raw cache.
        ConfigResource childInitial = makeResource("child", "Child",
                Map.of("own", itemDef("a", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(childInitial));
        configClient.ensureConnected();

        // A deterministic barrier the listener counts down when the inherited
        // value lands — no Thread.sleep.
        CountDownLatch inheritedSeen = new CountDownLatch(1);
        AtomicReference<Object> resolvedBase = new AtomicReference<>();
        configClient.onChange("child", evt -> {
            Object base = configClient.getValue("child", "base", null);
            if (base != null) {
                resolvedBase.set(base);
                inheritedSeen.countDown();
            }
        });

        // The config_changed event fetches the child (still parented to "parent"),
        // whose parent "parent" is itself parented to "grandparent" — neither is
        // in the raw cache. ensureAncestorsCached must fetch both on demand.
        ConfigResource childChanged = makeResourceWithParent("child", "Child",
                Map.of("own", itemDef("b", ConfigItemDefinition.TypeEnum.STRING)), Map.of(), "parent");
        ConfigResource parent = makeResourceWithParent("parent", "Parent",
                Map.of("mid", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of(), "grandparent");
        ConfigResource grandparent = makeResource("grandparent", "Grandparent",
                Map.of("base", itemDef(100, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        when(mockApi.getConfig("child")).thenReturn(singleResponse(childChanged));
        when(mockApi.getConfig("parent")).thenReturn(singleResponse(parent));
        when(mockApi.getConfig("grandparent")).thenReturn(singleResponse(grandparent));

        configClient.simulateConfigChanged(Map.of("id", "child"));

        assertTrue(inheritedSeen.await(5, TimeUnit.SECONDS),
                "Child should have re-resolved with the transitively-fetched ancestor value");
        assertEquals(100, resolvedBase.get(),
                "Inherited grandparent 'base' must survive after ancestors are fetched");
        // The child's resolved view now folds in every ancestor.
        assertEquals("b", configClient.getValue("child", "own"));
        assertEquals(2, configClient.getValue("child", "mid"));
        assertEquals(100, configClient.getValue("child", "base"));
        // Each uncached ancestor was fetched exactly once on demand.
        verify(mockApi, times(1)).getConfig("parent");
        verify(mockApi, times(1)).getConfig("grandparent");
    }

    @Test
    void configChanged_ancestorFetchThrows_isNoOp() throws ApiException {
        ConfigResource childInitial = makeResource("child", "Child",
                Map.of("own", itemDef("a", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(childInitial));
        configClient.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        // Changed child references an uncached parent whose fetch blows up.
        ConfigResource childChanged = makeResourceWithParent("child", "Child",
                Map.of("own", itemDef("b", ConfigItemDefinition.TypeEnum.STRING)), Map.of(), "boom-parent");
        when(mockApi.getConfig("child")).thenReturn(singleResponse(childChanged));
        when(mockApi.getConfig("boom-parent")).thenThrow(new ApiException(500, "boom"));

        configClient.simulateConfigChanged(Map.of("id", "child"));

        // The ancestor-fetch failure aborts before rebuild, so no listener fires.
        assertEquals(0, count.get());
        verify(mockApi, times(1)).getConfig("boom-parent");
    }

    @Test
    void configChanged_apiFetchThrows_isNoOp() throws ApiException {
        configClient.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        when(mockApi.getConfig("some-config")).thenThrow(new ApiException("API failure"));
        configClient.simulateConfigChanged(Map.of("id", "some-config"));

        assertEquals(0, count.get(), "Listener should not fire when API fetch throws");
        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
        verify(mockApi, times(1)).getConfig("some-config");
    }

    // -----------------------------------------------------------------------
    // config_deleted — remove from cache, re-resolve, no scoped fetch
    // -----------------------------------------------------------------------

    @Test
    void configDeleted_removesFromCache_firesValueToNull() throws ApiException {
        ConfigResource cfg = makeResource("del-config", "Del Config",
                Map.of("k", itemDef("v", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(cfg));
        configClient.ensureConnected();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("del-config", received::set);

        configClient.simulateConfigDeleted(Map.of("id", "del-config"));

        assertNotNull(received.get(), "Listener should fire on delete (value -> null)");
        assertEquals("del-config", received.get().configId());
        assertEquals("k", received.get().itemKey());
        assertEquals("v", received.get().oldValue());
        assertNull(received.get().newValue());
        assertEquals("websocket", received.get().source());
        verify(mockApi, never()).getConfig(any());
        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void configDeleted_missingId_fallsBackToFullRefresh() throws ApiException {
        configClient.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        configClient.simulateConfigDeleted(Map.of());

        assertEquals(0, count.get());
        verify(mockApi, times(2)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void configDeleted_unknownKey_isNoOp() throws ApiException {
        ConfigResource cfg = makeResource("config-a", "A",
                Map.of("k", itemDef("v", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(cfg));
        configClient.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        // Deleting a key not in the raw cache returns early — no rebuild, no fire.
        configClient.simulateConfigDeleted(Map.of("id", "not-present"));

        assertEquals(0, count.get());
    }

    @Test
    void configDeleted_otherConfigListener_doesNotFire() throws ApiException {
        ConfigResource cfg = makeResource("config-a", "A",
                Map.of("k", itemDef("v", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(cfg));
        configClient.ensureConnected();

        AtomicInteger bCount = new AtomicInteger();
        configClient.onChange("config-b", e -> bCount.incrementAndGet());

        configClient.simulateConfigDeleted(Map.of("id", "config-a"));

        assertEquals(0, bCount.get(), "config-b listener should not fire when config-a is deleted");
    }

    @Test
    void configDeleted_listenerThrows_doesNotPropagate() throws ApiException {
        ConfigResource cfg = makeResource("del-cfg", "Del",
                Map.of("k", itemDef("v", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(cfg));
        configClient.ensureConnected();

        configClient.onChange("del-cfg", e -> { throw new RuntimeException("listener crash"); });

        assertDoesNotThrow(() -> configClient.simulateConfigDeleted(Map.of("id", "del-cfg")),
                "Exception in delete listener should not propagate");
    }

    @Test
    void configDeleted_beforeConnect_isNoOp() throws ApiException {
        configClient.simulateConfigDeleted(Map.of("id", "anything"));
        verify(mockApi, never()).listConfigs(any(), any(), any(), any(), any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // configs_changed — full list fetch, diff-based firing
    // -----------------------------------------------------------------------

    @Test
    void configsChanged_fullFetch_diffBasedFiring() throws ApiException {
        ConfigResource initial = makeResource("cfg-1", "Cfg1",
                Map.of("val", itemDef(10, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(initial));
        configClient.ensureConnected();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("cfg-1", received::set);

        ConfigResource updated = makeResource("cfg-1", "Cfg1",
                Map.of("val", itemDef(20, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(updated));

        configClient.simulateConfigsChanged(Map.of());

        assertNotNull(received.get(), "Listener should fire when content changes");
        verify(mockApi, times(2)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void configsChanged_beforeConnect_isNoOp() throws ApiException {
        configClient.simulateConfigsChanged(Map.of());
        verify(mockApi, never()).listConfigs(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void configsChanged_refreshThrows_doesNotPropagate() throws ApiException {
        configClient.ensureConnected();

        // First listConfigs (connect) succeeded; make the next throw.
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenThrow(new ApiException(500, "boom"));

        assertDoesNotThrow(() -> configClient.simulateConfigsChanged(Map.of()),
                "configs_changed refresh failure must not propagate");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ConfigResource makeResourceWithParent(String id, String name,
                                                   Map<String, ConfigItemDefinition> items,
                                                   Map<String, Map<String, Object>> envs,
                                                   String parent) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(null, null);
        attrs.setName(name);
        attrs.setParent(parent);
        if (!items.isEmpty()) attrs.setItems(items);
        if (!envs.isEmpty()) attrs.setEnvironments(envs);

        ConfigResource resource = new ConfigResource();
        resource.setId(id);
        resource.setType(ConfigResource.TypeEnum.CONFIG);
        resource.setAttributes(attrs);
        return resource;
    }

    private ConfigResource makeResource(String id, String name,
                                        Map<String, ConfigItemDefinition> items,
                                        Map<String, Map<String, Object>> envs) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(null, null);
        attrs.setName(name);
        if (!items.isEmpty()) attrs.setItems(items);
        if (!envs.isEmpty()) attrs.setEnvironments(envs);

        ConfigResource resource = new ConfigResource();
        resource.setId(id);
        resource.setType(ConfigResource.TypeEnum.CONFIG);
        resource.setAttributes(attrs);
        return resource;
    }

    private ConfigListResponse listResponse(ConfigResource... resources) {
        ConfigListResponse resp = new ConfigListResponse();
        resp.setData(List.of(resources));
        return resp;
    }

    private ConfigResponse singleResponse(ConfigResource resource) {
        ConfigResponse resp = new ConfigResponse();
        resp.setData(resource);
        return resp;
    }

    private static ConfigItemDefinition itemDef(Object value, ConfigItemDefinition.TypeEnum type) {
        ConfigItemDefinition def = new ConfigItemDefinition();
        def.setValue(value);
        def.setType(type);
        return def;
    }
}
