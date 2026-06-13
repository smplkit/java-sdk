package com.smplkit.config;

import com.smplkit.SharedWebSocket;
import com.smplkit.errors.ConflictError;
import com.smplkit.errors.NotFoundError;
import com.smplkit.errors.ValidationError;
import com.smplkit.errors.SmplError;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigCreateRequest;
import com.smplkit.internal.generated.config.model.ConfigCreateResource;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the fused {@link ConfigClient} CRUD surface:
 * new_() + save(), get(id), list(), delete(id), plus error mapping,
 * {@link ConfigChangeEvent}, and the WebSocket-handler registration / simulate
 * seams.
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
                                        Map<String, Map<String, Object>> environments,
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

    /** Per ADR-024 §2.4 the wire-shape per-env override IS the flat {@code {key: rawValue}} map. */
    private static Map<String, Object> envOverride(Map<String, Object> rawValues) {
        return rawValues != null ? new HashMap<>(rawValues) : new HashMap<>();
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

    private ConfigListResponse emptyListResponse() {
        ConfigListResponse resp = new ConfigListResponse();
        resp.setData(List.of());
        return resp;
    }

    // -----------------------------------------------------------------------
    // new_()
    // -----------------------------------------------------------------------

    @Test
    void new_createsUnsavedConfig() {
        Config config = configClient.new_("user_service");

        assertEquals("user_service", config.getId());
        assertEquals("User Service", config.getName()); // auto-generated from id
        assertNull(config.getDescription());
        assertNull(config.getParent());
        assertTrue(config.items().isEmpty());
    }

    @Test
    void new_withAllParams() {
        Config config = configClient.new_("user_service", "My Service", "A description", "parent-uuid");

        assertEquals("user_service", config.getId());
        assertEquals("My Service", config.getName());
        assertEquals("A description", config.getDescription());
        assertEquals("parent-uuid", config.getParent());
    }

    @Test
    void new_nullName_autoGeneratesFromId() {
        Config config = configClient.new_("checkout-v2", null, null, (String) null);
        assertEquals("Checkout V2", config.getName());
    }

    @Test
    void new_withParentConfigInstance_usesItsId() throws ApiException {
        ConfigResource parentRes = makeResource(CONFIG_ID, "Parent", null, null,
                Map.of(), Map.of(), OffsetDateTime.now(), OffsetDateTime.now());
        when(mockApi.createConfig(any())).thenReturn(singleResponse(parentRes));
        Config parent = configClient.new_("parent");
        parent.save(); // gives it an id

        Config child = configClient.new_("child", null, null, parent);
        assertEquals(CONFIG_ID, child.getParent());
    }

    @Test
    void new_withNullParentConfigInstance_keepsNullParent() {
        Config child = configClient.new_("child", null, null, (Config) null);
        assertNull(child.getParent());
    }

    @Test
    void new_withParentConfigMissingId_throws() {
        // A parent Config that was never saved (id == null) cannot be used as a
        // parent reference.
        Config noIdParent = new Config(configClient, null, "P", null, null,
                new HashMap<>(), new HashMap<>(), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> configClient.new_("child", null, null, noIdParent));
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
        ArgumentCaptor<ConfigCreateRequest> captor = ArgumentCaptor.forClass(ConfigCreateRequest.class);
        when(mockApi.createConfig(captor.capture())).thenReturn(singleResponse(resource));

        Config config = configClient.new_("user_service");
        config.setDescription("Main user service config");
        config.setNumber("timeout", 30);
        config.save();

        assertEquals(CONFIG_ID, config.getId());
        assertEquals("User Service", config.getName());
        verify(mockApi).createConfig(any(ConfigCreateRequest.class));
        ConfigCreateResource data = captor.getValue().getData();
        assertEquals("user_service", data.getId());
        assertEquals(ConfigCreateResource.TypeEnum.CONFIG, data.getType());
        // The typed item carried its NUMBER type to the wire.
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER,
                data.getAttributes().getItems().get("timeout").getType());
    }

    @Test
    void save_create_withBooleanValue() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "Flags", null, null,
                Map.of("enabled", itemDef(true, ConfigItemDefinition.TypeEnum.BOOLEAN)),
                Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.new_("flags");
        config.setBoolean("enabled", true);
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

        Config config = configClient.new_("complex");
        config.setJson("nested", Map.of("k", "v"));
        config.save();

        assertNotNull(config.getId());
        verify(mockApi).createConfig(any());
    }

    @Test
    void save_create_withParent() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID_2, "Child", null, CONFIG_ID,
                Map.of(), Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.new_("child", null, null, CONFIG_ID);
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

        Config config = configClient.new_("svc");
        config.setNumber("timeout", 60, "production");
        config.save();

        assertNotNull(config.getId());
    }

    @Test
    void save_create_422_throwsValidationException() throws ApiException {
        when(mockApi.createConfig(any()))
                .thenThrow(new ApiException(422, "Validation error"));

        Config config = configClient.new_("bad");
        assertThrows(ValidationError.class, config::save);
    }

    @Test
    void save_create_nullResponse_throwsValidationError() throws ApiException {
        when(mockApi.createConfig(any())).thenReturn(null);
        Config config = configClient.new_("svc");
        assertThrows(ValidationError.class, config::save);
    }

    // -----------------------------------------------------------------------
    // save() — update (PUT)
    // -----------------------------------------------------------------------

    @Test
    void save_update_putsAndAppliesResponse() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource resource = makeResource(CONFIG_ID, "Old Name", null, null,
                Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.new_("svc");
        config.setNumber("a", 1);
        config.save(); // create

        ConfigResource updated = makeResource(CONFIG_ID, "New Name", "Updated desc",
                null, Map.of("a", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), now, now);
        when(mockApi.updateConfig(eq(CONFIG_ID), any()))
                .thenReturn(singleResponse(updated));

        config.setName("New Name");
        config.setDescription("Updated desc");
        config.setNumber("a", 2);
        config.save(); // update

        assertEquals("New Name", config.getName());
        assertEquals("Updated desc", config.getDescription());
        verify(mockApi).updateConfig(eq(CONFIG_ID), any());
    }

    @Test
    void save_update_nullResponse_throwsValidationError() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource created = makeResource(CONFIG_ID, "Svc", null, null,
                Map.of(), Map.of(), now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(created));
        Config config = configClient.new_("svc");
        config.save();

        when(mockApi.updateConfig(any(), any())).thenReturn(null);
        config.setName("New");
        assertThrows(ValidationError.class, config::save);
    }

    @Test
    void save_withoutClient_throwsIllegalState() {
        Config config = new Config(null, "test", "Test", null, null,
                new HashMap<>(), new HashMap<>(), null, null);
        assertThrows(IllegalStateException.class, config::save);
    }

    // -----------------------------------------------------------------------
    // get(id)
    // -----------------------------------------------------------------------

    @Test
    void get_byId_returnsConfig() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource resource = makeResource(CONFIG_ID, "User Service",
                "Main user service config", null,
                Map.of("timeout", itemDefWithDesc(30, ConfigItemDefinition.TypeEnum.NUMBER, "Timeout in seconds")),
                Map.of(), now, now);
        when(mockApi.getConfig("user_service")).thenReturn(singleResponse(resource));

        Config config = configClient.get("user_service");

        assertEquals(CONFIG_ID, config.getId());
        assertEquals("User Service", config.getName());
        assertEquals("Main user service config", config.getDescription());
        assertNull(config.getParent());
        assertNotNull(config.getCreatedAt());
        assertNotNull(config.getUpdatedAt());

        // Items expose the full typed shape via itemsRaw().
        @SuppressWarnings("unchecked")
        Map<String, Object> timeoutItem = (Map<String, Object>) config.itemsRaw().get("timeout");
        assertEquals(30, timeoutItem.get("value"));
        assertEquals("NUMBER", timeoutItem.get("type"));
        assertEquals("Timeout in seconds", timeoutItem.get("description"));

        // items() returns just the values.
        assertEquals(30, config.items().get("timeout"));
    }

    @Test
    void get_throwsNotFoundOn404() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException(404, "Not Found"));

        assertThrows(NotFoundError.class, () -> configClient.get("nonexistent"));
    }

    @Test
    void get_nullResponse_throwsNotFound() throws ApiException {
        when(mockApi.getConfig(any())).thenReturn(null);
        assertThrows(NotFoundError.class, () -> configClient.get("nope"));
    }

    @Test
    void get_nullData_throwsNotFound() throws ApiException {
        ConfigResponse resp = new ConfigResponse();
        resp.setData(null);
        when(mockApi.getConfig(any())).thenReturn(resp);
        assertThrows(NotFoundError.class, () -> configClient.get("nope"));
    }

    @Test
    void get_apiException_throwsSmplError() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException(500, "Internal Server Error"));

        SmplError ex = assertThrows(SmplError.class, () -> configClient.get("some-id"));
        assertEquals(500, ex.statusCode());
    }

    @Test
    void get_nullIdAndName_usesEmptyStrings() throws ApiException {
        ConfigResource resource = makeResource(null, null, null, null, null, null, null, null);
        when(mockApi.getConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.get("anything");

        assertEquals("", config.getId());
        assertEquals("", config.getName());
        assertNull(config.getDescription());
        assertTrue(config.items().isEmpty());
        assertTrue(config.environments().isEmpty());
    }

    @Test
    void get_withParent() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID_2, "Child", null,
                CONFIG_ID, Map.of(), Map.of(), null, null);
        when(mockApi.getConfig("child")).thenReturn(singleResponse(resource));

        Config config = configClient.get("child");
        assertEquals(CONFIG_ID, config.getParent());
    }

    @Test
    void get_resourceWithEmptyEnvironmentOverride_handledGracefully() throws ApiException {
        Map<String, Object> emptyOverride = new HashMap<>();

        ConfigResource resource = makeResource(CONFIG_ID, "Name", null,
                null, Map.of(), Map.of("production", envOverride(Map.of("a", 1)),
                        "staging", emptyOverride), null, null);
        when(mockApi.getConfig("svc")).thenReturn(singleResponse(resource));

        Config config = configClient.get("svc");

        assertTrue(config.environments().containsKey("production"));
        assertTrue(config.environments().containsKey("staging"));
    }

    // -----------------------------------------------------------------------
    // list()
    // -----------------------------------------------------------------------

    @Test
    void list_returnsAllConfigs() throws ApiException {
        ConfigResource r1 = makeResource(CONFIG_ID, "Svc A", null, null, Map.of(), Map.of(), null, null);
        ConfigResource r2 = makeResource(CONFIG_ID_2, "Svc B", null, CONFIG_ID, Map.of(), Map.of(), null, null);
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(listResponse(List.of(r1, r2)));

        List<Config> configs = configClient.list();

        assertEquals(2, configs.size());
        assertEquals(CONFIG_ID, configs.get(0).getId());
        assertEquals(CONFIG_ID_2, configs.get(1).getId());
        assertEquals(CONFIG_ID, configs.get(1).getParent());
    }

    @Test
    void list_returnsUnmodifiableList() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(listResponse(List.of()));

        List<Config> configs = configClient.list();

        assertThrows(UnsupportedOperationException.class, () ->
                configs.add(configClient.new_("id")));
    }

    @Test
    void list_nullData_returnsEmpty() throws ApiException {
        ConfigListResponse response = new ConfigListResponse();
        response.setData(null);
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(response);

        List<Config> configs = configClient.list();
        assertTrue(configs.isEmpty());
    }

    @Test
    void list_apiException_throwsSmplError() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new ApiException(503, "Service Unavailable"));

        SmplError ex = assertThrows(SmplError.class, () -> configClient.list());
        assertEquals(503, ex.statusCode());
    }

    // -----------------------------------------------------------------------
    // delete(id)
    // -----------------------------------------------------------------------

    @Test
    void delete_byId_deletesDirectly() throws ApiException {
        configClient.delete("my_config");
        verify(mockApi).deleteConfig("my_config");
    }

    @Test
    void delete_409_throwsConflictException() throws ApiException {
        Mockito.doThrow(new ApiException(409, "Has children"))
                .when(mockApi).deleteConfig("parent_config");

        ConflictError ex = assertThrows(ConflictError.class, () ->
                configClient.delete("parent_config"));
        assertEquals(409, ex.statusCode());
    }

    @Test
    void delete_notFound_throwsNotFoundException() throws ApiException {
        Mockito.doThrow(new ApiException(404, "Not Found"))
                .when(mockApi).deleteConfig("nonexistent");

        assertThrows(NotFoundError.class, () -> configClient.delete("nonexistent"));
    }

    // -----------------------------------------------------------------------
    // Config.delete() — active-record delete
    // -----------------------------------------------------------------------

    @Test
    void activeRecord_delete_callsClientDelete() throws ApiException {
        Config config = configClient.new_("doomed");
        config.delete();
        verify(mockApi).deleteConfig("doomed");
    }

    @Test
    void activeRecord_delete_bubblesNotFound() throws ApiException {
        Mockito.doThrow(new ApiException(404, "Not Found"))
                .when(mockApi).deleteConfig("ghost");

        Config config = configClient.new_("ghost");
        assertThrows(NotFoundError.class, config::delete);
    }

    @Test
    void activeRecord_delete_unboundClient_throwsIllegalState() {
        Config config = new Config(null, "orphan", "Orphan", null, null,
                new HashMap<>(), new HashMap<>(), null, null);
        assertThrows(IllegalStateException.class, config::delete);
    }

    @Test
    void activeRecord_delete_nullId_throwsIllegalState() {
        Config config = new Config(configClient, null, "NoId", null, null,
                new HashMap<>(), new HashMap<>(), null, null);
        assertThrows(IllegalStateException.class, config::delete);
    }

    // -----------------------------------------------------------------------
    // Error mapping
    // -----------------------------------------------------------------------

    @Test
    void apiException_500_mapsToSmplError() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException(500, "Internal Server Error"));

        SmplError ex = assertThrows(SmplError.class, () -> configClient.get(CONFIG_ID));
        assertEquals(500, ex.statusCode());
    }

    @Test
    void apiException_0_mapsToSmplError() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException("network failure"));

        assertThrows(SmplError.class, () -> configClient.get(CONFIG_ID));
    }

    @Test
    void apiException_nullMessage_usesHttpCodeInMessage() throws ApiException {
        when(mockApi.getConfig(any()))
                .thenThrow(new ApiException(503, (String) null));

        SmplError ex = assertThrows(SmplError.class, () -> configClient.get("some-id"));
        assertTrue(ex.getMessage().contains("503"));
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
    // setEnvironment / isConnected
    // -----------------------------------------------------------------------

    @Test
    void setEnvironment_doesNotConnect() {
        configClient.setEnvironment("staging");
        assertFalse(configClient.isConnected());
    }

    @Test
    void isConnected_falseByDefault() {
        assertFalse(configClient.isConnected());
    }

    @Test
    void ensureConnected_runsDeferredStartHook() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());
        java.util.concurrent.atomic.AtomicInteger hookRuns = new java.util.concurrent.atomic.AtomicInteger();
        configClient.setEnsureStarted(hookRuns::incrementAndGet);
        configClient.setService("svc");
        configClient.setEnvironment("production");

        configClient.ensureConnected();
        // Idempotent connect, but the hook runs on every ensureConnected call.
        configClient.ensureConnected();

        assertTrue(hookRuns.get() >= 1, "deferred-start hook should run on connect");
        assertTrue(configClient.isConnected());
    }

    // -----------------------------------------------------------------------
    // WebSocket handler registration + simulate seams
    // -----------------------------------------------------------------------

    @Test
    void ensureConnected_registersWsHandlersWhenManagerSet() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());
        SharedWebSocket mockWs = mock(SharedWebSocket.class);
        configClient.setSharedWs(mockWs);
        configClient.setEnvironment("production");

        configClient.ensureConnected();

        verify(mockWs).on(eq("config_changed"), any());
        verify(mockWs).on(eq("config_deleted"), any());
        verify(mockWs).on(eq("configs_changed"), any());
        verify(mockWs).ensureConnected(any());
    }

    @Test
    void simulateConfigChanged_triggersScopedFetch() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());
        ConfigResource resource = makeResource(CONFIG_ID, "Some Config", null, null,
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), null, null);
        when(mockApi.getConfig("some-config-id")).thenReturn(singleResponse(resource));
        configClient.setEnvironment("production");
        configClient.ensureConnected();

        configClient.simulateConfigChanged(Map.of("id", "some-config-id"));

        // Scoped fetch: getConfig called once for the changed key.
        verify(mockApi, times(1)).getConfig("some-config-id");
        // listConfigs called only once for init, NOT for the scoped change.
        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void simulateConfigDeleted_removesFromCacheNoFetch() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "Some Config", null, null,
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), null, null);
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse(List.of(resource)));
        configClient.setEnvironment("production");
        configClient.ensureConnected();

        configClient.simulateConfigDeleted(Map.of("id", CONFIG_ID));

        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
        verify(mockApi, never()).getConfig(any());
    }

    @Test
    void simulateConfigChanged_beforeConnect_isNoOp() throws ApiException {
        configClient.simulateConfigChanged(Map.of("id", "some-config-id"));
        verify(mockApi, never()).listConfigs(any(), any(), any(), any(), any(), any(), any());
        verify(mockApi, never()).getConfig(any());
    }

    @Test
    void simulateConfigDeleted_beforeConnect_isNoOp() throws ApiException {
        configClient.simulateConfigDeleted(Map.of("id", "some-config-id"));
        verify(mockApi, never()).listConfigs(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void simulateConfigsChanged_triggersFullRefresh() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyListResponse());
        configClient.setEnvironment("production");
        configClient.ensureConnected();

        configClient.simulateConfigsChanged(Map.of());

        // configs_changed triggers full refresh: listConfigs called twice.
        verify(mockApi, times(2)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }
}
