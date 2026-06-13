package com.smplkit.config;

import com.smplkit.MetricsReporter;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the prescriptive config access pattern: subscribe(), getValue(),
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
    // subscribe(id) — live resolved view
    // -----------------------------------------------------------------------

    @Test
    void subscribe_returnsResolvedValues() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("name", itemDef("Acme", ConfigItemDefinition.TypeEnum.STRING),
                        "retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        LiveConfigProxy values = configClient.subscribe("app");

        assertEquals("Acme", values.get("name"));
        assertEquals(3, values.get("retries"));
    }

    @Test
    void subscribe_withEnvironmentOverrides() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of("production", envOverride(Map.of("retries", 5)))));

        LiveConfigProxy values = configClient.subscribe("app");
        assertEquals(5, values.get("retries"));
    }

    @Test
    void subscribe_unknownConfig_throws() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> configClient.subscribe("nonexistent"));
    }

    // -----------------------------------------------------------------------
    // getValue(id, key) — throws on miss
    // -----------------------------------------------------------------------

    @Test
    void getValue_returnsValue() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        assertEquals(3, configClient.getValue("app", "retries"));
    }

    @Test
    void getValue_missingConfig_throws() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> configClient.getValue("missing", "key"));
    }

    @Test
    void getValue_missingKey_throws() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> configClient.getValue("app", "missing"));
    }

    // -----------------------------------------------------------------------
    // getValue(id, key, default) — registers + falls back
    // -----------------------------------------------------------------------

    @Test
    void getValueOrDefault_existingValue_overDefault() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));
        assertEquals(3, configClient.getValue("app", "retries", 999));
    }

    @Test
    void getValueOrDefault_missingConfig_returnsDefault() throws ApiException {
        setupListResponse();
        assertEquals(42, configClient.getValue("brand-new", "k", 42));
    }

    @Test
    void getValueOrDefault_missingKey_returnsDefault() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));
        assertEquals("fallback", configClient.getValue("app", "missing", "fallback"));
    }

    // -----------------------------------------------------------------------
    // Lazy init
    // -----------------------------------------------------------------------

    @Test
    void subscribe_triggersLazyInit() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("x", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        assertFalse(configClient.isConnected());
        configClient.subscribe("app");
        assertTrue(configClient.isConnected());
    }

    @Test
    void subscribe_lazyInitIdempotent() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null, Map.of(), Map.of()));

        configClient.subscribe("app");
        configClient.subscribe("app");

        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    // -----------------------------------------------------------------------
    // Parent chain resolution
    // -----------------------------------------------------------------------

    @Test
    void subscribe_parentChain_resolvesInheritance() throws ApiException {
        ConfigResource parent = makeResource(PARENT_ID, "Common", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER),
                        "timeout", itemDef(1000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        ConfigResource child = makeResource(SERVICE_ID, "Service", PARENT_ID,
                Map.of("retries", itemDef(5, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());

        setupListResponse(parent, child);

        LiveConfigProxy values = configClient.subscribe("service");

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

        LiveConfigProxy initial = configClient.subscribe("app");
        assertEquals(3, initial.get("retries"));

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        // The same live proxy reflects the refreshed value.
        assertEquals(7, initial.get("retries"));
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
        assertEquals(1000, configClient.subscribe("service").get("timeout"));

        ConfigResource parentUpdated = makeResource(PARENT_ID, "Common", null,
                Map.of("timeout", itemDef(2000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(parentUpdated, child)));

        configClient.refresh();

        assertEquals(2000, configClient.subscribe("service").get("timeout"));
    }

    // -----------------------------------------------------------------------
    // onChange — fires on refresh diffs
    // -----------------------------------------------------------------------

    @Test
    void onChange_global_firesOnRefresh() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.subscribe("app");

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

        configClient.subscribe("app");

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

        configClient.subscribe("app");

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

        configClient.subscribe("app");

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
    // refresh diff edge cases (new config, removed key, no listeners)
    // -----------------------------------------------------------------------

    @Test
    void refresh_noListeners_doesNotThrow() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.subscribe("app");

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        assertDoesNotThrow(() -> configClient.refresh());
    }

    @Test
    void refresh_newConfig_firesWithNullOld() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.subscribe("app");

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
    void refresh_removedKey_firesWithNullNew() throws ApiException {
        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER),
                        "b", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        configClient.subscribe("app");

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
    // Metrics — recorded on subscribe (resolutions) and on change (changes)
    // -----------------------------------------------------------------------

    @Test
    void metrics_recordedOnSubscribeAndOnChange() throws ApiException {
        MetricsReporter metrics = mock(MetricsReporter.class);
        configClient.setMetrics(metrics);

        setupListResponse(makeResource(APP_ID, "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        // subscribe records a resolution metric.
        configClient.subscribe("app");
        verify(metrics, atLeastOnce())
                .record(eq("config.resolutions"), eq("resolutions"), any());

        // a refresh that changes a value records a change metric.
        configClient.onChange(e -> {});
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(APP_ID, "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));
        configClient.refresh();
        verify(metrics, atLeastOnce())
                .record(eq("config.changes"), eq("changes"), any());
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
    void resolver_resolve_unwrapsTypedItems() {
        // Items in the typed {key: {value, type}} shape are unwrapped to raw.
        Resolver.ChainEntry entry = new Resolver.ChainEntry(
                "id1",
                Map.of("a", Map.of("value", 7, "type", "NUMBER")),
                Map.of());
        Map<String, Object> result = Resolver.resolve(List.of(entry), "production");
        assertEquals(7, result.get("a"));
    }

    @Test
    void resolver_chainEntry_nullsDefaultToEmpty() {
        Resolver.ChainEntry entry = new Resolver.ChainEntry("id", null, null);
        assertNotNull(entry.items);
        assertNotNull(entry.environments);
        assertTrue(entry.items.isEmpty());
        assertTrue(entry.environments.isEmpty());
    }
}
