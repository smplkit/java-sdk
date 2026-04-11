package com.smplkit.config;

import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigItemOverride;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.EnvironmentOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the prescriptive config access pattern: resolve(), subscribe(),
 * onChange(), refresh(), lazy init, and LiveConfig.
 */
class ConfigPrescriptiveTest {

    private ConfigsApi mockApi;
    private ConfigClient configClient;

    private static final String APP_ID = "app";
    private static final String SERVICE_ID = "service";
    private static final String PARENT_ID = "common";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(ConfigsApi.class);
        configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        configClient.setEnvironment("production");
    }

    private ConfigResource makeResource(String id, String name,
                                        String parent,
                                        Map<String, ConfigItemDefinition> items,
                                        Map<String, EnvironmentOverride> environments) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(null, null);
        attrs.setName(name != null ? name : "");
        if (parent != null) attrs.setParent(parent);
        if (items != null) attrs.setItems(items);
        if (environments != null) attrs.setEnvironments(environments);

        ConfigResource resource = new ConfigResource();
        resource.setId(id);
        resource.setType(ConfigResource.TypeEnum.CONFIG);
        resource.setAttributes(attrs);
        return resource;
    }

    private static ConfigItemDefinition itemDef(Object value, ConfigItemDefinition.TypeEnum type) {
        ConfigItemDefinition def = new ConfigItemDefinition();
        def.setValue(value);
        def.setType(type);
        return def;
    }

    private static EnvironmentOverride envOverride(Map<String, Object> rawValues) {
        EnvironmentOverride override = new EnvironmentOverride();
        if (rawValues != null) {
            Map<String, ConfigItemOverride> wrapped = new HashMap<>();
            for (Map.Entry<String, Object> entry : rawValues.entrySet()) {
                ConfigItemOverride item = new ConfigItemOverride();
                item.setValue(entry.getValue());
                wrapped.put(entry.getKey(), item);
            }
            override.setValues(wrapped);
        }
        return override;
    }

    private ConfigListResponse listResponse(List<ConfigResource> resources) {
        ConfigListResponse response = new ConfigListResponse();
        response.setData(resources);
        return response;
    }

    private void setupListResponse(ConfigResource... resources) throws ApiException {
        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(resources)));
    }

    // -----------------------------------------------------------------------
    // resolve()
    // -----------------------------------------------------------------------

    @Test
    void resolve_returnsResolvedValues() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("name", itemDef("Acme", ConfigItemDefinition.TypeEnum.STRING),
                        "retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        Map<String, Object> values = configClient.resolve("app");

        assertEquals("Acme", values.get("name"));
        assertEquals(3, values.get("retries"));
    }

    @Test
    void resolve_withEnvironmentOverrides() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of("production", envOverride(Map.of("retries", 5)))));

        Map<String, Object> values = configClient.resolve("app");

        assertEquals(5, values.get("retries"));
    }

    @Test
    void resolve_unknownKey_returnsEmptyMap() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));

        Map<String, Object> values = configClient.resolve("nonexistent");

        assertTrue(values.isEmpty());
    }

    @Test
    void resolve_withModelType() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("host", itemDef("localhost", ConfigItemDefinition.TypeEnum.STRING),
                        "port", itemDef(5432, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        SimpleConfig model = configClient.resolve("app", SimpleConfig.class);

        assertEquals("localhost", model.host);
        assertEquals(5432, model.port);
    }

    @Test
    void resolve_withModelType_unflattensDotNotation() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("database.host", itemDef("localhost", ConfigItemDefinition.TypeEnum.STRING),
                        "database.port", itemDef(5432, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        NestedConfig model = configClient.resolve("app", NestedConfig.class);

        assertEquals("localhost", model.database.get("host"));
        assertEquals(5432, model.database.get("port"));
    }

    // -----------------------------------------------------------------------
    // Lazy init
    // -----------------------------------------------------------------------

    @Test
    void resolve_triggersLazyInit() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("x", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        assertFalse(configClient.isConnected());
        configClient.resolve("app");
        assertTrue(configClient.isConnected());
    }

    @Test
    void resolve_lazyInitIdempotent() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));

        configClient.resolve("app");
        configClient.resolve("app");

        // Only one list call (lazy init is idempotent)
        verify(mockApi, times(1)).listConfigs(isNull());
    }

    @Test
    void resolve_noEnvironment_doesNotConnect() {
        ConfigClient noEnvClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        // No setEnvironment()

        Map<String, Object> values = noEnvClient.resolve("app");

        assertFalse(noEnvClient.isConnected());
        assertTrue(values.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Parent chain resolution
    // -----------------------------------------------------------------------

    @Test
    void resolve_parentChain_resolvesInheritance() throws ApiException {
        ConfigResource parent = makeResource(PARENT_ID, "Common", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER),
                        "timeout", itemDef(1000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        ConfigResource child = makeResource(SERVICE_ID, "Service", PARENT_ID,
                Map.of("retries", itemDef(5, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());

        setupListResponse(parent, child);

        Map<String, Object> values = configClient.resolve("service");

        // Child overrides parent retries, inherits timeout
        assertEquals(5, values.get("retries"));
        assertEquals(1000, values.get("timeout"));
    }

    // -----------------------------------------------------------------------
    // subscribe()
    // -----------------------------------------------------------------------

    @Test
    void subscribe_returnsLiveConfigMap() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        LiveConfig<Map<String, Object>> live = configClient.subscribe("app");

        assertNotNull(live);
        assertEquals("app", live.getId());
        assertNull(live.getModelType());
        assertEquals(3, live.getAsMap().get("retries"));
    }

    @Test
    void subscribe_withModelType() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("host", itemDef("localhost", ConfigItemDefinition.TypeEnum.STRING),
                        "port", itemDef(5432, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        LiveConfig<SimpleConfig> live = configClient.subscribe("app", SimpleConfig.class);

        assertNotNull(live);
        assertEquals("app", live.getId());
        assertEquals(SimpleConfig.class, live.getModelType());

        SimpleConfig model = live.get();
        assertEquals("localhost", model.host);
        assertEquals(5432, model.port);
    }

    @Test
    void subscribe_triggersLazyInit() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));

        assertFalse(configClient.isConnected());
        configClient.subscribe("app");
        assertTrue(configClient.isConnected());
    }

    @Test
    void subscribe_liveConfigUpdatesOnRefresh() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        LiveConfig<Map<String, Object>> live = configClient.subscribe("app");
        assertEquals(3, live.getAsMap().get("retries"));

        // Update mock for refresh
        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(7, live.getAsMap().get("retries"));
    }

    @Test
    void liveConfig_getWithoutModelType_throws() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));

        LiveConfig<Map<String, Object>> live = configClient.subscribe("app");

        assertThrows(IllegalStateException.class, live::get);
    }

    // -----------------------------------------------------------------------
    // refresh()
    // -----------------------------------------------------------------------

    @Test
    void refresh_updatesCache() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        Map<String, Object> initial = configClient.resolve("app");
        assertEquals(3, initial.get("retries"));

        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        Map<String, Object> refreshed = configClient.resolve("app");
        assertEquals(7, refreshed.get("retries"));
    }

    @Test
    void refresh_parentChain_resolvesInheritance() throws ApiException {
        ConfigResource parent = makeResource(PARENT_ID, "Common", null,
                Map.of("timeout", itemDef(1000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        ConfigResource child = makeResource(SERVICE_ID, "Service", PARENT_ID,
                Map.of("retries", itemDef(5, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());

        setupListResponse(parent, child);
        assertEquals(1000, configClient.resolve("service").get("timeout"));

        // After refresh, parent timeout changes
        ConfigResource parentUpdated = makeResource(PARENT_ID, "Common", null,
                Map.of("timeout", itemDef(2000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(parentUpdated, child)));

        configClient.refresh();

        assertEquals(2000, configClient.resolve("service").get("timeout"));
    }

    @Test
    void refresh_noEnvironment_noOp() {
        ConfigClient noEnvClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        assertDoesNotThrow(noEnvClient::refresh);
    }

    // -----------------------------------------------------------------------
    // onChange
    // -----------------------------------------------------------------------

    @Test
    void onChange_global_firesOnRefresh() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.resolve("app"); // trigger lazy init

        List<ConfigChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, events.size());
        assertEquals("app", events.get(0).configId());
        assertEquals("retries", events.get(0).itemKey());
        assertEquals(3, events.get(0).oldValue());
        assertEquals(7, events.get(0).newValue());
        assertEquals("manual", events.get(0).source());
    }

    @Test
    void onChange_configScoped() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.resolve("app");

        List<ConfigChangeEvent> appEvents = new ArrayList<>();
        configClient.onChange("app", appEvents::add);

        List<ConfigChangeEvent> otherEvents = new ArrayList<>();
        configClient.onChange("other_config", otherEvents::add);

        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, appEvents.size());
        assertTrue(otherEvents.isEmpty());
    }

    @Test
    void onChange_itemScoped() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER),
                        "timeout", itemDef(1000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.resolve("app");

        List<ConfigChangeEvent> retriesEvents = new ArrayList<>();
        configClient.onChange("app", "retries", retriesEvents::add);

        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER),
                                        "timeout", itemDef(2000, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        // Should only get the retries change, not timeout
        assertEquals(1, retriesEvents.size());
        assertEquals("retries", retriesEvents.get(0).itemKey());
    }

    @Test
    void onChange_listenerExceptionDoesNotPropagate() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.resolve("app");

        List<ConfigChangeEvent> events = new ArrayList<>();
        configClient.onChange(e -> { throw new RuntimeException("bad listener"); });
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, events.size());
    }

    // -----------------------------------------------------------------------
    // diffAndFire edge cases
    // -----------------------------------------------------------------------

    @Test
    void diffAndFire_noListeners_doesNotThrow() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.resolve("app");

        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        assertDoesNotThrow(() -> configClient.refresh());
    }

    @Test
    void diffAndFire_newConfig() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.resolve("app");

        List<ConfigChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        String newConfigId = "new_config";
        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()),
                        makeResource(newConfigId, "New Config", null,
                                Map.of("b", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, events.size());
        assertEquals("new_config", events.get(0).configId());
        assertEquals("b", events.get(0).itemKey());
        assertNull(events.get(0).oldValue());
    }

    @Test
    void diffAndFire_removedKey() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER),
                        "b", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.resolve("app");

        List<ConfigChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, events.size());
        assertEquals("b", events.get(0).itemKey());
        assertNull(events.get(0).newValue());
    }

    // -----------------------------------------------------------------------
    // Resolver
    // -----------------------------------------------------------------------

    @Test
    void resolver_deepMerge_nestedMaps() {
        Map<String, Object> base = new HashMap<>();
        base.put("db", new HashMap<>(Map.of("host", "localhost", "port", 5432)));

        Map<String, Object> override = new HashMap<>();
        override.put("db", new HashMap<>(Map.of("host", "prod", "ssl", true)));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = Resolver.deepMerge(base, override);
        @SuppressWarnings("unchecked")
        Map<String, Object> db = (Map<String, Object>) result.get("db");
        assertEquals("prod", db.get("host"));
        assertEquals(5432, db.get("port"));
        assertEquals(true, db.get("ssl"));
    }

    @Test
    void resolver_resolve_emptyChain() {
        Map<String, Object> result = Resolver.resolve(List.of(), "production");
        assertTrue(result.isEmpty());
    }

    @Test
    void resolver_resolve_envOverridesBase() {
        Map<String, Object> envValues = Map.of("a", 99);
        Map<String, Object> envData = new HashMap<>();
        envData.put("values", envValues);

        Resolver.ChainEntry entry = new Resolver.ChainEntry(
                "id1",
                Map.of("a", 1, "b", 2),
                Map.of("production", envData));
        Map<String, Object> result = Resolver.resolve(List.of(entry), "production");
        assertEquals(99, result.get("a"));
        assertEquals(2, result.get("b"));
    }

    @Test
    void resolver_chainEntry_nullsDefaultToEmpty() {
        Resolver.ChainEntry entry = new Resolver.ChainEntry("id", null, null);
        assertNotNull(entry.values);
        assertNotNull(entry.environments);
        assertTrue(entry.values.isEmpty());
        assertTrue(entry.environments.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Test model classes for resolve with model type
    // -----------------------------------------------------------------------

    public static class SimpleConfig {
        public String host;
        public int port;
    }

    public static class NestedConfig {
        public Map<String, Object> database;
    }
}
