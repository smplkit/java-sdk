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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ConfigClient WS event behaviors:
 * - config_changed: scoped fetch, diff-based listener firing
 * - config_deleted: remove from cache, fire listener with deleted=true, no fetch
 * - configs_changed: full list fetch, diff-based firing
 */
class ConfigClientWsEventsTest {

    private ConfigsApi mockApi;
    private ConfigClient configClient;

    @BeforeEach
    void setUp() throws ApiException {
        mockApi = Mockito.mock(ConfigsApi.class);
        configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        configClient.setEnvironment("production");

        // Default: empty list for init
        ConfigListResponse emptyList = new ConfigListResponse();
        emptyList.setData(List.of());
        when(mockApi.listConfigs(null)).thenReturn(emptyList);
    }

    // -----------------------------------------------------------------------
    // D. config_changed — scoped fetch, diff-based
    // -----------------------------------------------------------------------

    @Test
    void configChanged_contentChanged_scopedFetch_listenerFires() throws ApiException {
        // Seed initial state with one config
        ConfigResource initial = makeResource("my-config", "My Config",
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(initial));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("my-config", received::set);

        // getConfig returns updated content (timeout changed to 60)
        ConfigResource updated = makeResource("my-config", "My Config",
                Map.of("timeout", itemDef(60, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        when(mockApi.getConfig("my-config")).thenReturn(singleResponse(updated));

        configClient.simulateConfigChanged(Map.of("id", "my-config"));

        assertNotNull(received.get(), "Listener should fire when content changes");
        assertEquals("my-config", received.get().configId());
        assertEquals("websocket", received.get().source());
        assertFalse(received.get().isDeleted());
        // Scoped fetch: getConfig called, not listConfigs again
        verify(mockApi, times(1)).getConfig("my-config");
        verify(mockApi, times(1)).listConfigs(null); // only initial
    }

    @Test
    void configChanged_contentUnchanged_listenerDoesNotFire() throws ApiException {
        ConfigResource initial = makeResource("my-config", "My Config",
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(initial));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange("my-config", e -> count.incrementAndGet());

        // Same data
        when(mockApi.getConfig("my-config")).thenReturn(singleResponse(initial));
        configClient.simulateConfigChanged(Map.of("id", "my-config"));

        assertEquals(0, count.get(), "Listener should not fire when content is unchanged");
    }

    @Test
    void configChanged_missingId_isNoOp() throws ApiException {
        configClient._connectInternal();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        configClient.simulateConfigChanged(Map.of()); // no "id"

        assertEquals(0, count.get());
        verify(mockApi, never()).getConfig(any());
    }

    // -----------------------------------------------------------------------
    // D. config_deleted — remove from cache, fire listener, no fetch
    // -----------------------------------------------------------------------

    @Test
    void configDeleted_removesFromCache_firesListenerWithDeletedTrue() throws ApiException {
        ConfigResource cfg = makeResource("del-config", "Del Config",
                Map.of("k", itemDef("v", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(cfg));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("del-config", received::set);

        configClient.simulateConfigDeleted(Map.of("id", "del-config"));

        assertNotNull(received.get(), "Listener should fire on delete");
        assertEquals("del-config", received.get().configId());
        assertTrue(received.get().isDeleted());
        assertEquals("websocket", received.get().source());
        // No HTTP fetch
        verify(mockApi, never()).getConfig(any());
        verify(mockApi, times(1)).listConfigs(null); // only initial
    }

    @Test
    void configDeleted_missingId_isNoOp() throws ApiException {
        configClient._connectInternal();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        configClient.simulateConfigDeleted(Map.of()); // no "id"

        assertEquals(0, count.get());
    }

    @Test
    void configDeleted_otherConfigListener_doesNotFire() throws ApiException {
        ConfigResource cfg = makeResource("config-a", "A",
                Map.of("k", itemDef("v", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(cfg));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        AtomicInteger bCount = new AtomicInteger();
        configClient.onChange("config-b", e -> bCount.incrementAndGet());

        configClient.simulateConfigDeleted(Map.of("id", "config-a"));

        assertEquals(0, bCount.get(), "config-b listener should not fire when config-a is deleted");
    }

    // -----------------------------------------------------------------------
    // D. configs_changed — full list fetch, diff-based firing
    // -----------------------------------------------------------------------

    @Test
    void configsChanged_fullFetch_diffBasedFiring() throws ApiException {
        ConfigResource initial = makeResource("cfg-1", "Cfg1",
                Map.of("val", itemDef(10, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(initial));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("cfg-1", received::set);

        // configs_changed → full refresh with changed data
        ConfigResource updated = makeResource("cfg-1", "Cfg1",
                Map.of("val", itemDef(20, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        ConfigListResponse updatedList = new ConfigListResponse();
        updatedList.setData(List.of(updated));
        when(mockApi.listConfigs(null)).thenReturn(updatedList);

        configClient.simulateConfigsChanged(Map.of());

        assertNotNull(received.get(), "Listener should fire when content changes");
        // listConfigs called twice: once for init, once for configs_changed
        verify(mockApi, times(2)).listConfigs(null);
    }

    @Test
    void configsChanged_beforeConnect_isNoOp() throws ApiException {
        configClient.simulateConfigsChanged(Map.of());
        verify(mockApi, never()).listConfigs(any());
    }

    // -----------------------------------------------------------------------
    // Exception paths and edge cases
    // -----------------------------------------------------------------------

    @Test
    void configChanged_apiFetchThrows_isNoOp() throws ApiException {
        configClient._connectInternal();

        AtomicInteger count = new AtomicInteger();
        configClient.onChange(e -> count.incrementAndGet());

        when(mockApi.getConfig("some-config")).thenThrow(new ApiException("API failure"));
        configClient.simulateConfigChanged(Map.of("id", "some-config"));

        assertEquals(0, count.get(), "Listener should not fire when API fetch throws");
        verify(mockApi, times(1)).listConfigs(null); // only initial call, not triggered by failed event
        verify(mockApi, times(1)).getConfig("some-config");
    }

    @Test
    void configChanged_withParentChain_parentFound_resolvesChain() throws ApiException {
        // Seed child config initially
        ConfigResource childCfg = makeResource("child-cfg", "Child",
                Map.of("child-val", itemDef("initial", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(childCfg));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("child-cfg", received::set);

        // getConfig returns child with parent set
        ConfigResource childWithParent = makeResourceWithParent("child-cfg", "Child",
                Map.of("child-val", itemDef("updated", ConfigItemDefinition.TypeEnum.STRING)),
                Map.of(), "parent-cfg");
        when(mockApi.getConfig("child-cfg")).thenReturn(singleResponse(childWithParent));

        // listConfigs for management.list() returns parent + child (parent found in loop)
        ConfigResource parentCfg = makeResource("parent-cfg", "Parent",
                Map.of("base", itemDef(100, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        ConfigListResponse bothConfigs = new ConfigListResponse();
        bothConfigs.setData(List.of(parentCfg, childWithParent));
        when(mockApi.listConfigs(null)).thenReturn(bothConfigs);

        configClient.simulateConfigChanged(Map.of("id", "child-cfg"));

        assertNotNull(received.get(), "Listener should fire when content changes with parent chain");
        verify(mockApi, times(1)).getConfig("child-cfg");
    }

    @Test
    void configChanged_withParentChain_parentNotFound_breaksLoop() throws ApiException {
        // Seed child config
        ConfigResource childCfg = makeResource("child-cfg", "Child",
                Map.of("child-val", itemDef("initial", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(childCfg));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("child-cfg", received::set);

        // getConfig returns child with parent that doesn't exist
        ConfigResource childWithParent = makeResourceWithParent("child-cfg", "Child",
                Map.of("child-val", itemDef("updated2", ConfigItemDefinition.TypeEnum.STRING)),
                Map.of(), "nonexistent-parent");
        when(mockApi.getConfig("child-cfg")).thenReturn(singleResponse(childWithParent));

        // listConfigs returns only other configs — parent not found (loop exhausts, line 346)
        ConfigResource otherCfg = makeResource("other-cfg", "Other",
                Map.of("x", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of());
        ConfigListResponse noParent = new ConfigListResponse();
        noParent.setData(List.of(otherCfg));
        when(mockApi.listConfigs(null)).thenReturn(noParent);

        configClient.simulateConfigChanged(Map.of("id", "child-cfg"));

        assertNotNull(received.get(), "Listener should still fire even when parent not found");
    }

    @Test
    void configDeleted_emptyConfig_firesNullItemListener() throws ApiException {
        // Config with no items — exercises removed.isEmpty() branch
        ConfigResource cfg = makeResource("empty-cfg", "Empty", Map.of(), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(cfg));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        configClient.onChange("empty-cfg", received::set);

        configClient.simulateConfigDeleted(Map.of("id", "empty-cfg"));

        assertNotNull(received.get(), "Listener should fire for empty config delete");
        assertEquals("empty-cfg", received.get().configId());
        assertTrue(received.get().isDeleted());
        assertNull(received.get().itemKey(), "Item key should be null for empty config");
    }

    @Test
    void configDeleted_emptyConfig_listenerThrows_doesNotPropagate() throws ApiException {
        // Empty config (no items) + listener that throws → exercises exception catch at line 398-399
        ConfigResource cfg = makeResource("empty-throw-cfg", "EmptyThrow", Map.of(), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(cfg));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        configClient.onChange("empty-throw-cfg", e -> { throw new RuntimeException("empty delete crash"); });

        assertDoesNotThrow(() -> configClient.simulateConfigDeleted(Map.of("id", "empty-throw-cfg")),
                "Exception in empty-config delete listener should not propagate");
    }

    @Test
    void configDeleted_listenerThrows_doesNotPropagate() throws ApiException {
        ConfigResource cfg = makeResource("del-cfg", "Del",
                Map.of("k", itemDef("v", ConfigItemDefinition.TypeEnum.STRING)), Map.of());
        ConfigListResponse initList = new ConfigListResponse();
        initList.setData(List.of(cfg));
        when(mockApi.listConfigs(null)).thenReturn(initList);
        configClient._connectInternal();

        configClient.onChange("del-cfg", e -> { throw new RuntimeException("listener crash"); });

        assertDoesNotThrow(() -> configClient.simulateConfigDeleted(Map.of("id", "del-cfg")),
                "Exception in delete listener should not propagate");
    }

    // -----------------------------------------------------------------------
    // ConfigChangeEvent.isDeleted()
    // -----------------------------------------------------------------------

    @Test
    void configChangeEvent_isDeletedFalseByDefault() {
        ConfigChangeEvent event = new ConfigChangeEvent("cfg", "key", "old", "new", "websocket");
        assertFalse(event.isDeleted());
    }

    @Test
    void configChangeEvent_isDeletedTrueWhenSet() {
        ConfigChangeEvent event = new ConfigChangeEvent("cfg", "key", "v", null, "websocket", true);
        assertTrue(event.isDeleted());
    }

    @Test
    void configChangeEvent_toString_includesDeletedField() {
        ConfigChangeEvent event = new ConfigChangeEvent("cfg", "key", null, null, "websocket", true);
        assertTrue(event.toString().contains("deleted=true"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ConfigResource makeResourceWithParent(String id, String name,
                                                   Map<String, ConfigItemDefinition> items,
                                                   Map<String, com.smplkit.internal.generated.config.model.EnvironmentOverride> envs,
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
                                         Map<String, com.smplkit.internal.generated.config.model.EnvironmentOverride> envs) {
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
