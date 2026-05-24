package com.smplkit.config;

import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the prescriptive config access pattern: get(), get(id, key),
 * refresh(), onChange, lazy init, and the {@link Resolver}.
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
                                        Map<String, Map<String, Object>> environments) {
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

    /**
     * Per ADR-024 §2.4 the wire-shape per-env override IS the flat
     * {@code {key: rawValue}} map.
     */
    private static Map<String, Object> envOverride(Map<String, Object> rawValues) {
        return rawValues != null ? new HashMap<>(rawValues) : new HashMap<>();
    }

    private ConfigListResponse listResponse(List<ConfigResource> resources) {
        ConfigListResponse response = new ConfigListResponse();
        response.setData(resources);
        return response;
    }

    private void setupListResponse(ConfigResource... resources) throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(resources)));
    }

    // -----------------------------------------------------------------------
    // get(id)
    // -----------------------------------------------------------------------

    @Test
    void get_returnsResolvedValues() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("name", itemDef("Acme", ConfigItemDefinition.TypeEnum.STRING),
                        "retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        Map<String, Object> values = configClient.get("app");

        assertEquals("Acme", values.get("name"));
        assertEquals(3, values.get("retries"));
    }

    @Test
    void get_withEnvironmentOverrides() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of("production", envOverride(Map.of("retries", 5)))));

        Map<String, Object> values = configClient.get("app");

        assertEquals(5, values.get("retries"));
    }

    @Test
    void get_unknownKey_throws() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));

        // The id is not in the cache → NotFoundError. Declarative discovery
        // via bind() / get(id, key, default) handles the unknown case.
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> configClient.get("nonexistent"));
    }

    // -----------------------------------------------------------------------
    // get(id, key) — two-argument form, throws on miss
    // -----------------------------------------------------------------------

    @Test
    void get_byKey_returnsValue() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        assertEquals(3, configClient.get("app", "retries"));
    }

    @Test
    void get_byKey_missingConfig_throws() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> configClient.get("missing", "key"));
    }

    @Test
    void get_byKey_missingKey_throws() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> configClient.get("app", "missing"));
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
        configClient.get("app");
        assertTrue(configClient.isConnected());
    }

    @Test
    void resolve_lazyInitIdempotent() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));

        configClient.get("app");
        configClient.get("app");

        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void resolve_noEnvironment_doesNotConnect() {
        ConfigClient noEnvClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        assertThrows(com.smplkit.errors.NotFoundError.class, () -> noEnvClient.get("app"));
        assertFalse(noEnvClient.isConnected());
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

        Map<String, Object> values = configClient.get("service");

        assertEquals(5, values.get("retries"));
        assertEquals(1000, values.get("timeout"));
    }

    // -----------------------------------------------------------------------
    // refresh()
    // -----------------------------------------------------------------------

    @Test
    void refresh_updatesCache() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        Map<String, Object> initial = configClient.get("app");
        assertEquals(3, initial.get("retries"));

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        Map<String, Object> refreshed = configClient.get("app");
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
        assertEquals(1000, configClient.get("service").get("timeout"));

        ConfigResource parentUpdated = makeResource(PARENT_ID, "Common", null,
                Map.of("timeout", itemDef(2000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(parentUpdated, child)));

        configClient.refresh();

        assertEquals(2000, configClient.get("service").get("timeout"));
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

        configClient.get("app");

        List<ConfigChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
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

        configClient.get("app");

        List<ConfigChangeEvent> appEvents = new ArrayList<>();
        configClient.onChange("app", appEvents::add);

        List<ConfigChangeEvent> otherEvents = new ArrayList<>();
        configClient.onChange("other_config", otherEvents::add);

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
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

        configClient.get("app");

        List<ConfigChangeEvent> retriesEvents = new ArrayList<>();
        configClient.onChange("app", "retries", retriesEvents::add);

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER),
                                        "timeout", itemDef(2000, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, retriesEvents.size());
        assertEquals("retries", retriesEvents.get(0).itemKey());
    }

    @Test
    void onChange_listenerExceptionDoesNotPropagate() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.get("app");

        List<ConfigChangeEvent> events = new ArrayList<>();
        configClient.onChange(e -> { throw new RuntimeException("bad listener"); });
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
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

        configClient.get("app");

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
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

        configClient.get("app");

        List<ConfigChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        String newConfigId = "new_config";
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
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

        configClient.get("app");

        List<ConfigChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
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
        // Per ADR-024 §2.4 the env override IS the flat {key: rawValue} map.
        Map<String, Object> envValues = new HashMap<>();
        envValues.put("a", 99);

        Resolver.ChainEntry entry = new Resolver.ChainEntry(
                "id1",
                Map.of("a", 1, "b", 2),
                Map.of("production", envValues));
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
}
