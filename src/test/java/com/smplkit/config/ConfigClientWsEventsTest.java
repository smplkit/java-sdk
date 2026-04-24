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
