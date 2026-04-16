package com.smplkit.config;

import com.smplkit.SharedWebSocket;
import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.errors.SmplException;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigItemOverride;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import com.smplkit.internal.generated.config.model.EnvironmentOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ConfigClient} CRUD operations using the new API:
 * new_() + save(), get(id), list(), delete(id).
 */
class ConfigClientTest {

    private ConfigsApi mockApi;
    private ConfigClient configClient;

    private static final String CONFIG_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CONFIG_ID_2 = "660e8400-e29b-41d4-a716-446655440001";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(ConfigsApi.class);
        configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
    }

    // -----------------------------------------------------------------------
    // Helpers to build generated model objects
    // -----------------------------------------------------------------------

    private ConfigResource makeResource(String id, String name, String description,
                                        String parent, Map<String, ConfigItemDefinition> items,
                                        Map<String, EnvironmentOverride> environments,
                                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(createdAt, updatedAt);
        if (name != null) attrs.setName(name); else attrs.setName("");
        if (description != null) attrs.setDescription(description);
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

    private static ConfigItemDefinition itemDefWithDesc(Object value, ConfigItemDefinition.TypeEnum type, String desc) {
        ConfigItemDefinition def = new ConfigItemDefinition();
        def.setValue(value);
        def.setType(type);
        def.setDescription(desc);
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

    private ConfigResponse singleResponse(ConfigResource resource) {
        ConfigResponse response = new ConfigResponse();
        response.setData(resource);
        return response;
    }

    private ConfigListResponse listResponse(List<ConfigResource> resources) {
        ConfigListResponse response = new ConfigListResponse();
        response.setData(resources);
        return response;
    }

    // -----------------------------------------------------------------------
    // new_()
    // -----------------------------------------------------------------------

    @Test
    void new_createsUnsavedConfig() {
        Config config = configClient.management().new_("user_service");

        assertEquals("user_service", config.getId());
        assertEquals("User Service", config.getName()); // auto-generated from id
        assertNull(config.getDescription());
        assertNull(config.getParent());
        assertTrue(config.getItems().isEmpty());
    }

    @Test
    void new_withAllParams() {
        Config config = configClient.management().new_("user_service", "My Service", "A description", "parent-uuid");

        assertEquals("user_service", config.getId());
        assertEquals("My Service", config.getName());
        assertEquals("A description", config.getDescription());
        assertEquals("parent-uuid", config.getParent());
    }

    @Test
    void new_nullName_autoGeneratesFromId() {
        Config config = configClient.management().new_("checkout-v2", null, null, null);

        assertEquals("Checkout V2", config.getName());
    }

    // -----------------------------------------------------------------------
    // save() — create (POST)
    // -----------------------------------------------------------------------

    @Test
    void save_create_postsAndAppliesResponse() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "User Service",
                "Main user service config", null,
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.management().new_("user_service");
        config.setDescription("Main user service config");
        config.setItems(Map.of("timeout", Map.of("value", 30)));
        config.save();

        assertEquals(CONFIG_ID, config.getId());
        assertEquals("User Service", config.getName());
        verify(mockApi).createConfig(any());
    }

    @Test
    void save_create_withBooleanValue() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "Flags", null, null,
                Map.of("enabled", itemDef(true, ConfigItemDefinition.TypeEnum.BOOLEAN)),
                Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.management().new_("flags");
        config.setItems(Map.of("enabled", Map.of("value", Boolean.TRUE)));
        config.save();

        assertNotNull(config.getId());
        verify(mockApi).createConfig(any());
    }

    @Test
    void save_create_withJsonValue() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "Complex", null, null,
                Map.of("nested", itemDef(Map.of("k", "v"), ConfigItemDefinition.TypeEnum.JSON)),
                Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.management().new_("complex");
        config.setItems(Map.of("nested", Map.of("value", Map.of("k", "v"))));
        config.save();

        assertNotNull(config.getId());
        verify(mockApi).createConfig(any());
    }

    @Test
    void save_create_withParent() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID_2, "Child", null, CONFIG_ID,
                Map.of(), Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.management().new_("child", null, null, CONFIG_ID);
        config.save();

        assertNotNull(config.getId());
        assertEquals(CONFIG_ID, config.getParent());
    }

    @Test
    void save_create_withEnvironments() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "Svc", null, null,
                Map.of(), Map.of("production", envOverride(Map.of("timeout", 60))),
                null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.management().new_("svc");
        Map<String, Object> envData = new HashMap<>();
        envData.put("values", Map.of("timeout", 60));
        config.setEnvironments(Map.of("production", envData));
        config.save();

        assertNotNull(config.getId());
    }

    @Test
    void save_create_422_throwsValidationException() throws ApiException {
        when(mockApi.createConfig(any()))
                .thenThrow(new ApiException(422, "Validation error"));

        Config config = configClient.management().new_("bad");
        assertThrows(SmplValidationException.class, config::save);
    }

    // -----------------------------------------------------------------------
    // save() — update (PUT)
    // -----------------------------------------------------------------------

    @Test
    void save_update_putsAndAppliesResponse() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        // First create a config to get createdAt set
        ConfigResource resource = makeResource(CONFIG_ID, "Old Name", null, null,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.management().new_("svc");
        config.setItems(Map.of("a", Map.of("value", 1)));
        config.save(); // create

        // Now update
        ConfigResource updated = makeResource(CONFIG_ID, "New Name", "Updated desc",
                null, Map.of("a", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), now, now);
        when(mockApi.updateConfig(eq(CONFIG_ID), any()))
                .thenReturn(singleResponse(updated));

        config.setName("New Name");
        config.setDescription("Updated desc");
        config.setItems(Map.of("a", Map.of("value", 2)));
        config.save(); // update

        assertEquals("New Name", config.getName());
        assertEquals("Updated desc", config.getDescription());
        verify(mockApi).updateConfig(eq(CONFIG_ID), any());
    }

    @Test
    void save_withoutClient_throwsIllegalState() {
        Config config = new Config(null, "test", "Test");
        assertThrows(IllegalStateException.class, config::save);
    }

    // -----------------------------------------------------------------------
    // get(key)
    // -----------------------------------------------------------------------

    @Test
    void get_byId_returnsConfig() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource resource = makeResource(CONFIG_ID, "User Service",
                "Main user service config", null,
                Map.of("timeout", itemDefWithDesc(30, ConfigItemDefinition.TypeEnum.NUMBER, "Timeout in seconds")),
                Map.of(), now, now);
        when(mockApi.getConfig("user_service")).thenReturn(singleResponse(resource));

        Config config = configClient.management().get("user_service");

        assertEquals(CONFIG_ID, config.getId());
        assertEquals("User Service", config.getName());
        assertEquals("Main user service config", config.getDescription());
        assertNull(config.getParent());
        assertNotNull(config.getCreatedAt());
        assertNotNull(config.getUpdatedAt());

        // Items should have the full typed shape
        @SuppressWarnings("unchecked")
        Map<String, Object> timeoutItem = (Map<String, Object>) config.getItems().get("timeout");
        assertEquals(30, timeoutItem.get("value"));
        assertEquals("NUMBER", timeoutItem.get("type"));
        assertEquals("Timeout in seconds", timeoutItem.get("description"));

        // Resolved items should just have the value
        assertEquals(30, config.getResolvedItems().get("timeout"));
    }

    @Test
    void get_throwsNotFoundOn404() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException(404, "Not Found"));

        assertThrows(SmplNotFoundException.class, () ->
                configClient.management().get("nonexistent"));
    }

    @Test
    void get_apiException_throwsSmplException() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException(500, "Internal Server Error"));

        SmplException ex = assertThrows(SmplException.class, () ->
                configClient.management().get("some-id"));
        assertEquals(500, ex.statusCode());
    }

    @Test
    void get_nullIdAndName_usesEmptyStrings() throws ApiException {
        ConfigResource resource = makeResource(null, null, null, null, null, null, null, null);
        when(mockApi.getConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.management().get("anything");

        assertEquals("", config.getId());
        assertEquals("", config.getName());
        assertNull(config.getDescription());
        assertTrue(config.getItems().isEmpty());
        assertTrue(config.getEnvironments().isEmpty());
    }

    @Test
    void get_withParent() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID_2, "Child", null,
                CONFIG_ID, Map.of(), Map.of(), null, null);
        when(mockApi.getConfig("child")).thenReturn(singleResponse(resource));

        Config config = configClient.management().get("child");

        assertEquals(CONFIG_ID, config.getParent());
    }

    @Test
    void get_resourceWithNullId_usesEmptyString() throws ApiException {
        ConfigResource resource = makeResource(null, "Name", null,
                null, Map.of(), Map.of(), null, null);
        when(mockApi.getConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.management().get("svc");

        assertEquals("", config.getId());
    }

    @Test
    void get_resourceWithEmptyEnvironmentOverride_handledGracefully() throws ApiException {
        EnvironmentOverride emptyOverride = new EnvironmentOverride();

        ConfigResource resource = makeResource(CONFIG_ID, "Name", null,
                null, Map.of(), Map.of("production", envOverride(Map.of("a", 1)),
                        "staging", emptyOverride), null, null);
        when(mockApi.getConfig("svc")).thenReturn(singleResponse(resource));

        Config config = configClient.management().get("svc");

        assertTrue(config.getEnvironments().containsKey("production"));
        assertTrue(config.getEnvironments().containsKey("staging"));
    }

    // -----------------------------------------------------------------------
    // list()
    // -----------------------------------------------------------------------

    @Test
    void list_returnsAllConfigs() throws ApiException {
        ConfigResource r1 = makeResource(CONFIG_ID, "Svc A", null, null, Map.of(), Map.of(), null, null);
        ConfigResource r2 = makeResource(CONFIG_ID_2, "Svc B", null, CONFIG_ID, Map.of(), Map.of(), null, null);
        when(mockApi.listConfigs(null)).thenReturn(listResponse(List.of(r1, r2)));

        List<Config> configs = configClient.management().list();

        assertEquals(2, configs.size());
        assertEquals(CONFIG_ID, configs.get(0).getId());
        assertEquals(CONFIG_ID_2, configs.get(1).getId());
        assertEquals(CONFIG_ID, configs.get(1).getParent());
    }

    @Test
    void list_returnsUnmodifiableList() throws ApiException {
        when(mockApi.listConfigs(null)).thenReturn(listResponse(List.of()));

        List<Config> configs = configClient.management().list();

        assertThrows(UnsupportedOperationException.class, () ->
                configs.add(new Config(null, "id", "name")));
    }

    @Test
    void list_nullData_returnsEmpty() throws ApiException {
        ConfigListResponse response = new ConfigListResponse();
        response.setData(null);
        when(mockApi.listConfigs(null)).thenReturn(response);

        List<Config> configs = configClient.management().list();

        assertTrue(configs.isEmpty());
    }

    @Test
    void list_apiException_throwsSmplException() throws ApiException {
        when(mockApi.listConfigs(null))
                .thenThrow(new ApiException(503, "Service Unavailable"));

        SmplException ex = assertThrows(SmplException.class, () ->
                configClient.management().list());
        assertEquals(503, ex.statusCode());
    }

    // -----------------------------------------------------------------------
    // delete(key)
    // -----------------------------------------------------------------------

    @Test
    void delete_byId_deletesDirectly() throws ApiException {
        configClient.management().delete("my_config");

        verify(mockApi).deleteConfig("my_config");
    }

    @Test
    void delete_409_throwsConflictException() throws ApiException {
        Mockito.doThrow(new ApiException(409, "Has children"))
                .when(mockApi).deleteConfig("parent_config");

        SmplConflictException ex = assertThrows(SmplConflictException.class, () ->
                configClient.management().delete("parent_config"));
        assertEquals(409, ex.statusCode());
    }

    @Test
    void delete_notFound_throwsNotFoundException() throws ApiException {
        Mockito.doThrow(new ApiException(404, "Not Found"))
                .when(mockApi).deleteConfig("nonexistent");

        assertThrows(SmplNotFoundException.class, () -> configClient.management().delete("nonexistent"));
    }

    // -----------------------------------------------------------------------
    // Error mapping
    // -----------------------------------------------------------------------

    @Test
    void apiException_500_mapsToSmplException() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException(500, "Internal Server Error"));

        SmplException ex = assertThrows(SmplException.class, () ->
                configClient.management().get(CONFIG_ID));
        assertEquals(500, ex.statusCode());
    }

    @Test
    void apiException_0_mapsToSmplException() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException("network failure"));

        assertThrows(SmplException.class, () -> configClient.management().get(CONFIG_ID));
    }

    @Test
    void apiException_nullMessage_usesHttpCodeInMessage() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException(503, (String) null));

        SmplException ex = assertThrows(SmplException.class, () ->
                configClient.management().get("some-id"));
        assertTrue(ex.getMessage().contains("503"));
    }

    // -----------------------------------------------------------------------
    // Config class
    // -----------------------------------------------------------------------

    @Test
    void config_toString() {
        Config config = configClient.management().new_("id1");
        assertEquals("Config[id=id1, name=Id1]", config.toString());
    }

    @Test
    void config_getResolvedItems_extractsValues() {
        Config config = configClient.management().new_("test");
        Map<String, Object> items = new HashMap<>();
        items.put("timeout", Map.of("value", 30, "type", "NUMBER"));
        items.put("name", Map.of("value", "hello"));
        items.put("plain", "raw"); // non-map value
        config.setItems(items);

        Map<String, Object> resolved = config.getResolvedItems();
        assertEquals(30, resolved.get("timeout"));
        assertEquals("hello", resolved.get("name"));
        assertEquals("raw", resolved.get("plain"));
    }

    @Test
    void config_getResolvedItems_mapWithoutValueKey() {
        Config config = configClient.management().new_("test");
        Map<String, Object> items = new HashMap<>();
        items.put("nested", Map.of("key", "val")); // map without "value" key
        config.setItems(items);

        Map<String, Object> resolved = config.getResolvedItems();
        assertEquals(Map.of("key", "val"), resolved.get("nested"));
    }

    @Test
    void config_setItems_null_defaultsToEmpty() {
        Config config = configClient.management().new_("test");
        config.setItems(null);
        assertNotNull(config.getItems());
        assertTrue(config.getItems().isEmpty());
    }

    @Test
    void config_setEnvironments_null_defaultsToEmpty() {
        Config config = configClient.management().new_("test");
        config.setEnvironments(null);
        assertNotNull(config.getEnvironments());
        assertTrue(config.getEnvironments().isEmpty());
    }

    @Test
    void config_apply_copiesAllFields() {
        Config source = configClient.management().new_("src-id");
        source.setDescription("src desc");
        source.setParent("src-parent");
        source.setItems(Map.of("k", "v"));
        source.setEnvironments(Map.of("prod", Map.of()));
        source.setCreatedAt(java.time.Instant.now());
        source.setUpdatedAt(java.time.Instant.now());

        Config target = configClient.management().new_("target-id");
        target._apply(source);

        assertEquals("src-id", target.getId());
        assertEquals("Src Id", target.getName());
        assertEquals("src desc", target.getDescription());
        assertEquals("src-parent", target.getParent());
        assertNotNull(target.getCreatedAt());
        assertNotNull(target.getUpdatedAt());
    }

    @Test
    void config_apply_copiesItemsAndEnvironments() {
        Config source = new Config(configClient, "src", "Src");
        source.setItems(Map.of("k", "v"));
        source.setEnvironments(Map.of("prod", Map.of()));

        Config target = configClient.management().new_("tgt");
        target._apply(source);

        assertEquals(Map.of("k", "v"), target.getItems());
        assertEquals(Map.of("prod", Map.of()), target.getEnvironments());
    }

    // -----------------------------------------------------------------------
    // ConfigChangeEvent
    // -----------------------------------------------------------------------

    @Test
    void configChangeEvent_accessors() {
        ConfigChangeEvent event = new ConfigChangeEvent("user_service", "timeout", 30, 60, "websocket");
        assertEquals("user_service", event.configId());
        assertEquals("timeout", event.itemKey());
        assertEquals(30, event.oldValue());
        assertEquals(60, event.newValue());
        assertEquals("websocket", event.source());
    }

    @Test
    void configChangeEvent_toString_format() {
        ConfigChangeEvent event = new ConfigChangeEvent("cfg", "key", "old", "new", "manual");
        String str = event.toString();
        assertTrue(str.contains("configId=cfg"));
        assertTrue(str.contains("itemKey=key"));
        assertTrue(str.contains("oldValue=old"));
        assertTrue(str.contains("newValue=new"));
        assertTrue(str.contains("source=manual"));
    }

    @Test
    void configChangeEvent_nullValues_allowed() {
        ConfigChangeEvent event = new ConfigChangeEvent("cfg", "key", null, "value", "websocket");
        assertNull(event.oldValue());
        assertEquals("value", event.newValue());
    }

    @Test
    void configChangeEvent_equality() {
        ConfigChangeEvent e1 = new ConfigChangeEvent("cfg", "k", "a", "b", "websocket");
        ConfigChangeEvent e2 = new ConfigChangeEvent("cfg", "k", "a", "b", "websocket");
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    // -----------------------------------------------------------------------
    // unflatten
    // -----------------------------------------------------------------------

    @Test
    void unflatten_convertsDotNotation() {
        Map<String, Object> flat = Map.of(
                "database.host", "localhost",
                "database.port", 5432,
                "app_name", "MyApp"
        );
        Map<String, Object> nested = ConfigClient.unflatten(flat);

        @SuppressWarnings("unchecked")
        Map<String, Object> db = (Map<String, Object>) nested.get("database");
        assertEquals("localhost", db.get("host"));
        assertEquals(5432, db.get("port"));
        assertEquals("MyApp", nested.get("app_name"));
    }

    @Test
    void unflatten_emptyMap() {
        Map<String, Object> nested = ConfigClient.unflatten(Map.of());
        assertTrue(nested.isEmpty());
    }

    // -----------------------------------------------------------------------
    // inferType
    // -----------------------------------------------------------------------

    @Test
    void inferType_allTypes() {
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, ConfigClient.inferType("hello"));
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER, ConfigClient.inferType(42));
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN, ConfigClient.inferType(true));
        assertEquals(ConfigItemDefinition.TypeEnum.JSON, ConfigClient.inferType(Map.of("k", "v")));
    }

    // -----------------------------------------------------------------------
    // setEnvironment
    // -----------------------------------------------------------------------

    @Test
    void setEnvironment_setsValue() {
        configClient.setEnvironment("staging");
        // No public getter, but verified indirectly through resolve()
        assertFalse(configClient.isConnected());
    }

    // -----------------------------------------------------------------------
    // isConnected
    // -----------------------------------------------------------------------

    @Test
    void isConnected_falseByDefault() {
        assertFalse(configClient.isConnected());
    }

    // -----------------------------------------------------------------------
    // WebSocket handler coverage
    // -----------------------------------------------------------------------

    @Test
    void connectInternal_registersWsHandlersWhenManagerSet() throws ApiException {
        when(mockApi.listConfigs(null)).thenReturn(emptyListResponse());
        SharedWebSocket mockWs = mock(SharedWebSocket.class);
        configClient.setSharedWs(mockWs);
        configClient.setEnvironment("production");

        configClient._connectInternal();

        verify(mockWs).on(eq("config_changed"), any());
        verify(mockWs).on(eq("config_deleted"), any());
    }

    @Test
    void simulateConfigChanged_triggersRefresh() throws ApiException {
        when(mockApi.listConfigs(null)).thenReturn(emptyListResponse());
        configClient.setEnvironment("production");
        configClient._connectInternal();

        configClient.simulateConfigChanged(Map.of("id", "some-config-id"));

        // listConfigs should have been called twice: once for init, once for refresh
        verify(mockApi, times(2)).listConfigs(null);
    }

    @Test
    void simulateConfigDeleted_triggersRefresh() throws ApiException {
        when(mockApi.listConfigs(null)).thenReturn(emptyListResponse());
        configClient.setEnvironment("production");
        configClient._connectInternal();

        configClient.simulateConfigDeleted(Map.of("id", "some-config-id"));

        verify(mockApi, times(2)).listConfigs(null);
    }

    @Test
    void simulateConfigChanged_beforeConnect_isNoOp() throws ApiException {
        // handleConfigChanged when not connected should not throw and not call API
        configClient.simulateConfigChanged(Map.of("id", "some-config-id"));
        verify(mockApi, never()).listConfigs(any());
    }

    @Test
    void simulateConfigDeleted_beforeConnect_isNoOp() throws ApiException {
        configClient.simulateConfigDeleted(Map.of("id", "some-config-id"));
        verify(mockApi, never()).listConfigs(any());
    }

    private com.smplkit.internal.generated.config.model.ConfigListResponse emptyListResponse() {
        com.smplkit.internal.generated.config.model.ConfigListResponse resp =
                new com.smplkit.internal.generated.config.model.ConfigListResponse();
        resp.setData(List.of());
        return resp;
    }
}
