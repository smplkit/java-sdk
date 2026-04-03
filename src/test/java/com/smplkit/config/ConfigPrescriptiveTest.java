package com.smplkit.config;

import com.smplkit.errors.SmplNotConnectedException;
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
import static org.mockito.Mockito.when;

/**
 * Tests for the prescriptive config access pattern: typed accessors,
 * refresh(), onChange(), and diffAndFire().
 */
class ConfigPrescriptiveTest {

    private ConfigsApi mockApi;
    private ConfigClient configClient;

    private static final String CONFIG_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PARENT_ID = "660e8400-e29b-41d4-a716-446655440001";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(ConfigsApi.class);
        configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
    }

    private ConfigResource makeResource(String id, String key, String name,
                                        String parent,
                                        Map<String, ConfigItemDefinition> items,
                                        Map<String, EnvironmentOverride> environments) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(null, null);
        attrs.setName(name != null ? name : "");
        if (key != null) attrs.setKey(key);
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

    private void connectWithConfigs(ConfigResource... resources) throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(resources)));
        configClient.connectInternal("production");
    }

    // -----------------------------------------------------------------------
    // getString
    // -----------------------------------------------------------------------

    @Test
    void getString_returnsStringValue() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("name", itemDef("Acme", ConfigItemDefinition.TypeEnum.STRING)),
                Map.of()));

        assertEquals("Acme", configClient.getString("app", "name", "default"));
    }

    @Test
    void getString_wrongType_returnsDefault() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("count", itemDef(42, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        assertEquals("fallback", configClient.getString("app", "count", "fallback"));
    }

    @Test
    void getString_missingKey_returnsDefault() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null, Map.of(), Map.of()));

        assertEquals("default", configClient.getString("app", "missing", "default"));
    }

    // -----------------------------------------------------------------------
    // getInt
    // -----------------------------------------------------------------------

    @Test
    void getInt_returnsIntValue() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("count", itemDef(42, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        assertEquals(42, configClient.getInt("app", "count", 0));
    }

    @Test
    void getInt_wrongType_returnsDefault() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("name", itemDef("text", ConfigItemDefinition.TypeEnum.STRING)),
                Map.of()));

        assertEquals(99, configClient.getInt("app", "name", 99));
    }

    // -----------------------------------------------------------------------
    // getBool
    // -----------------------------------------------------------------------

    @Test
    void getBool_returnsBoolValue() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("enabled", itemDef(true, ConfigItemDefinition.TypeEnum.BOOLEAN)),
                Map.of()));

        assertTrue(configClient.getBool("app", "enabled", false));
    }

    @Test
    void getBool_wrongType_returnsDefault() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("name", itemDef("text", ConfigItemDefinition.TypeEnum.STRING)),
                Map.of()));

        assertFalse(configClient.getBool("app", "name", false));
    }

    // -----------------------------------------------------------------------
    // NotConnected
    // -----------------------------------------------------------------------

    @Test
    void getString_notConnected_throws() {
        assertThrows(SmplNotConnectedException.class,
                () -> configClient.getString("app", "name", "x"));
    }

    @Test
    void getInt_notConnected_throws() {
        assertThrows(SmplNotConnectedException.class,
                () -> configClient.getInt("app", "count", 0));
    }

    @Test
    void getBool_notConnected_throws() {
        assertThrows(SmplNotConnectedException.class,
                () -> configClient.getBool("app", "flag", false));
    }

    @Test
    void refresh_notConnected_throws() {
        assertThrows(SmplNotConnectedException.class,
                () -> configClient.refresh());
    }

    // -----------------------------------------------------------------------
    // connectInternal — parent chain walking
    // -----------------------------------------------------------------------

    @Test
    void connectInternal_parentChain_resolvesInheritance() throws ApiException {
        ConfigResource parent = makeResource(PARENT_ID, "common", "Common", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER),
                        "timeout", itemDef(1000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        ConfigResource child = makeResource(CONFIG_ID, "service", "Service", PARENT_ID,
                Map.of("retries", itemDef(5, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());

        connectWithConfigs(parent, child);

        // Child overrides parent retries, inherits timeout
        assertEquals(5, configClient.getInt("service", "retries", 0));
        assertEquals(1000, configClient.getInt("service", "timeout", 0));
    }

    // -----------------------------------------------------------------------
    // refresh
    // -----------------------------------------------------------------------

    @Test
    void refresh_updatesCache() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        assertEquals(3, configClient.getInt("app", "retries", 0));

        // Update mock to return new value on next list call
        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(CONFIG_ID, "app", "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(7, configClient.getInt("app", "retries", 0));
    }

    @Test
    void refresh_parentChain_resolvesInheritance() throws ApiException {
        ConfigResource parent = makeResource(PARENT_ID, "common", "Common", null,
                Map.of("timeout", itemDef(1000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        ConfigResource child = makeResource(CONFIG_ID, "service", "Service", PARENT_ID,
                Map.of("retries", itemDef(5, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());

        connectWithConfigs(parent, child);
        assertEquals(1000, configClient.getInt("service", "timeout", 0));

        // After refresh, parent timeout changes
        ConfigResource parentUpdated = makeResource(PARENT_ID, "common", "Common", null,
                Map.of("timeout", itemDef(2000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(parentUpdated, child)));

        configClient.refresh();

        assertEquals(2000, configClient.getInt("service", "timeout", 0));
    }

    // -----------------------------------------------------------------------
    // onChange
    // -----------------------------------------------------------------------

    @Test
    void onChange_firesOnRefresh() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        List<ChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(CONFIG_ID, "app", "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, events.size());
        assertEquals("app", events.get(0).configKey());
        assertEquals("retries", events.get(0).itemKey());
        assertEquals("manual", events.get(0).source());
    }

    @Test
    void onChange_filteredByConfigAndItem() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER),
                        "timeout", itemDef(1000, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        List<ChangeEvent> retriesEvents = new ArrayList<>();
        configClient.onChange(retriesEvents::add, "app", "retries");

        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(CONFIG_ID, "app", "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER),
                                        "timeout", itemDef(2000, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        // Should only get the retries change, not timeout
        assertEquals(1, retriesEvents.size());
        assertEquals("retries", retriesEvents.get(0).itemKey());
    }

    @Test
    void onChange_filteredByConfigKeyOnly() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        List<ChangeEvent> appEvents = new ArrayList<>();
        configClient.onChange(appEvents::add, "app", null);

        List<ChangeEvent> otherEvents = new ArrayList<>();
        configClient.onChange(otherEvents::add, "other_config", null);

        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(CONFIG_ID, "app", "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, appEvents.size());
        assertTrue(otherEvents.isEmpty());
    }

    @Test
    void onChange_listenerExceptionDoesNotPropagate() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        List<ChangeEvent> events = new ArrayList<>();
        // First listener throws
        configClient.onChange(e -> { throw new RuntimeException("bad listener"); });
        // Second listener should still fire
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(CONFIG_ID, "app", "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, events.size());
    }

    // -----------------------------------------------------------------------
    // DiffAndFire edge cases
    // -----------------------------------------------------------------------

    @Test
    void diffAndFire_noListeners_doesNotThrow() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("retries", itemDef(3, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(CONFIG_ID, "app", "App", null,
                                Map.of("retries", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        // No listeners registered — should not throw
        assertDoesNotThrow(() -> configClient.refresh());
    }

    @Test
    void diffAndFire_newConfig() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        List<ChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        // After refresh, add a second config
        String newConfigId = "770e8400-e29b-41d4-a716-446655440002";
        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(CONFIG_ID, "app", "App", null,
                                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()),
                        makeResource(newConfigId, "new_config", "New Config", null,
                                Map.of("b", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)),
                                Map.of()))));

        configClient.refresh();

        assertEquals(1, events.size());
        assertEquals("new_config", events.get(0).configKey());
        assertEquals("b", events.get(0).itemKey());
        assertNull(events.get(0).oldValue());
    }

    @Test
    void diffAndFire_removedKey() throws ApiException {
        connectWithConfigs(makeResource(CONFIG_ID, "app", "App", null,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER),
                        "b", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of()));

        List<ChangeEvent> events = new ArrayList<>();
        configClient.onChange(events::add);

        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(listResponse(List.of(
                        makeResource(CONFIG_ID, "app", "App", null,
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
}
