package com.smplkit.config;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ConfigClient} using a mocked {@link ConfigsApi}.
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

    private ConfigResource makeResource(String id, String key, String name, String description,
                                        String parent, Map<String, ConfigItemDefinition> items,
                                        Map<String, EnvironmentOverride> environments,
                                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(createdAt, updatedAt);
        if (name != null) attrs.setName(name); else attrs.setName("");
        if (key != null) attrs.setKey(key);
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

    /** Helper to create a typed item definition with a raw value. */
    private static ConfigItemDefinition itemDef(Object value, ConfigItemDefinition.TypeEnum type) {
        ConfigItemDefinition def = new ConfigItemDefinition();
        def.setValue(value);
        def.setType(type);
        return def;
    }

    /** Helper to create an environment override with wrapped values. */
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
    // get()
    // -----------------------------------------------------------------------

    @Test
    void getById_returnsConfig() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource resource = makeResource(CONFIG_ID, "user_service", "User Service",
                "Main user service config", null,
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), now, now);
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID))).thenReturn(singleResponse(resource));

        com.smplkit.config.Config config = configClient.get(CONFIG_ID);

        assertEquals(CONFIG_ID, config.id());
        assertEquals("user_service", config.key());
        assertEquals("User Service", config.name());
        assertEquals("Main user service config", config.description());
        assertNull(config.parent());
        assertEquals(30, config.items().get("timeout"));
        assertNotNull(config.createdAt());
        assertNotNull(config.updatedAt());
    }

    @Test
    void getById_nullKeyAndName_usesEmptyStrings() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, null, null, null, null, null, null, null, null);
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID))).thenReturn(singleResponse(resource));

        com.smplkit.config.Config config = configClient.get(CONFIG_ID);

        assertEquals("", config.key());
        assertEquals("", config.name());
        assertNull(config.description());
        assertTrue(config.items().isEmpty());
        assertTrue(config.environments().isEmpty());
        assertNull(config.createdAt());
        assertNull(config.updatedAt());
    }

    @Test
    void getById_withParent() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID_2, "child", "Child", null,
                CONFIG_ID, Map.of(), Map.of(), null, null);
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID_2))).thenReturn(singleResponse(resource));

        com.smplkit.config.Config config = configClient.get(CONFIG_ID_2);

        assertEquals(CONFIG_ID, config.parent());
    }

    @Test
    void get_404_throwsNotFoundException() throws ApiException {
        when(mockApi.getConfig(any(UUID.class)))
                .thenThrow(new ApiException(404, "Not Found"));

        SmplNotFoundException ex = assertThrows(SmplNotFoundException.class, () ->
                configClient.get(CONFIG_ID));
        assertEquals(404, ex.statusCode());
    }

    // -----------------------------------------------------------------------
    // getByKey()
    // -----------------------------------------------------------------------

    @Test
    void getByKey_returnsConfig() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "user_service", "User Service",
                null, null, Map.of(), Map.of(), null, null);
        when(mockApi.listConfigs("user_service", null)).thenReturn(listResponse(List.of(resource)));

        com.smplkit.config.Config config = configClient.getByKey("user_service");

        assertEquals(CONFIG_ID, config.id());
        assertEquals("user_service", config.key());
    }

    @Test
    void getByKey_throwsNotFoundWhenEmpty() throws ApiException {
        when(mockApi.listConfigs(any(), any())).thenReturn(listResponse(List.of()));

        assertThrows(SmplNotFoundException.class, () ->
                configClient.getByKey("nonexistent"));
    }

    @Test
    void getByKey_throwsNotFoundWhenNullData() throws ApiException {
        ConfigListResponse response = new ConfigListResponse();
        response.setData(null);
        when(mockApi.listConfigs(any(), any())).thenReturn(response);

        assertThrows(SmplNotFoundException.class, () ->
                configClient.getByKey("nonexistent"));
    }

    // -----------------------------------------------------------------------
    // list()
    // -----------------------------------------------------------------------

    @Test
    void list_returnsAllConfigs() throws ApiException {
        ConfigResource r1 = makeResource(CONFIG_ID, "svc_a", "Svc A", null, null, Map.of(), Map.of(), null, null);
        ConfigResource r2 = makeResource(CONFIG_ID_2, "svc_b", "Svc B", null, CONFIG_ID, Map.of(), Map.of(), null, null);
        when(mockApi.listConfigs(null, null)).thenReturn(listResponse(List.of(r1, r2)));

        List<com.smplkit.config.Config> configs = configClient.list();

        assertEquals(2, configs.size());
        assertEquals("svc_a", configs.get(0).key());
        assertEquals("svc_b", configs.get(1).key());
        assertEquals(CONFIG_ID, configs.get(1).parent());
    }

    @Test
    void list_returnsUnmodifiableList() throws ApiException {
        when(mockApi.listConfigs(null, null)).thenReturn(listResponse(List.of()));

        List<com.smplkit.config.Config> configs = configClient.list();

        assertThrows(UnsupportedOperationException.class, () ->
                configs.add(new com.smplkit.config.Config("id", "key", "name", null, null, Map.of(), Map.of(), null, null)));
    }

    @Test
    void list_nullData_returnsEmpty() throws ApiException {
        ConfigListResponse response = new ConfigListResponse();
        response.setData(null);
        when(mockApi.listConfigs(null, null)).thenReturn(response);

        List<com.smplkit.config.Config> configs = configClient.list();

        assertTrue(configs.isEmpty());
    }

    // -----------------------------------------------------------------------
    // create()
    // -----------------------------------------------------------------------

    @Test
    void create_sendsBodyAndReturnsConfig() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "user_service", "User Service",
                "Main user service config", null,
                Map.of("timeout", itemDef(30, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        CreateConfigParams params = CreateConfigParams.builder("User Service")
                .key("user_service")
                .description("Main user service config")
                .values(Map.of("timeout", 30))
                .build();

        com.smplkit.config.Config config = configClient.create(params);

        assertEquals(CONFIG_ID, config.id());
        assertEquals("User Service", config.name());
        verify(mockApi).createConfig(any());
    }

    @Test
    void create_withMinimalParams() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID, "my_config", "My Config", null, null, Map.of(), Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        CreateConfigParams params = CreateConfigParams.builder("My Config").build();
        com.smplkit.config.Config config = configClient.create(params);

        assertNotNull(config);
    }

    @Test
    void create_withParent() throws ApiException {
        ConfigResource resource = makeResource(CONFIG_ID_2, "child", "Child", null, CONFIG_ID, Map.of(), Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        CreateConfigParams params = CreateConfigParams.builder("Child")
                .parent(CONFIG_ID)
                .build();
        com.smplkit.config.Config config = configClient.create(params);

        assertNotNull(config);
    }

    @Test
    void create_withBooleanValue_infersBooleanType() throws ApiException {
        // Exercises inferType with a Boolean value → ConfigItemDefinition.TypeEnum.BOOLEAN
        ConfigResource resource = makeResource(CONFIG_ID, "flags", "Flags", null, null,
                Map.of("enabled", itemDef(true, ConfigItemDefinition.TypeEnum.BOOLEAN)),
                Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        CreateConfigParams params = CreateConfigParams.builder("Flags")
                .values(Map.of("enabled", Boolean.TRUE))
                .build();
        com.smplkit.config.Config config = configClient.create(params);

        assertNotNull(config);
        verify(mockApi).createConfig(any());
    }

    @Test
    void create_withJsonValue_infersJsonType() throws ApiException {
        // Exercises inferType with a non-primitive value → ConfigItemDefinition.TypeEnum.JSON
        ConfigResource resource = makeResource(CONFIG_ID, "complex", "Complex", null, null,
                Map.of("nested", itemDef(Map.of("k", "v"), ConfigItemDefinition.TypeEnum.JSON)),
                Map.of(), null, null);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(resource));

        CreateConfigParams params = CreateConfigParams.builder("Complex")
                .values(Map.of("nested", Map.of("k", "v")))
                .build();
        com.smplkit.config.Config config = configClient.create(params);

        assertNotNull(config);
        verify(mockApi).createConfig(any());
    }

    @Test
    void create_422_throwsValidationException() throws ApiException {
        when(mockApi.createConfig(any()))
                .thenThrow(new ApiException(422, "Validation error"));

        CreateConfigParams params = CreateConfigParams.builder("Bad").build();
        SmplValidationException ex = assertThrows(SmplValidationException.class, () ->
                configClient.create(params));
        assertEquals(422, ex.statusCode());
    }

    // -----------------------------------------------------------------------
    // delete()
    // -----------------------------------------------------------------------

    @Test
    void delete_callsApi() throws ApiException {
        assertDoesNotThrow(() -> configClient.delete(CONFIG_ID));
        verify(mockApi).deleteConfig(UUID.fromString(CONFIG_ID));
    }

    @Test
    void delete_409_throwsConflictException() throws ApiException {
        Mockito.doThrow(new ApiException(409, "Has children"))
                .when(mockApi).deleteConfig(any(UUID.class));

        SmplConflictException ex = assertThrows(SmplConflictException.class, () ->
                configClient.delete(CONFIG_ID));
        assertEquals(409, ex.statusCode());
    }

    @Test
    void delete_404_throwsNotFoundException() throws ApiException {
        Mockito.doThrow(new ApiException(404, "Not Found"))
                .when(mockApi).deleteConfig(any(UUID.class));

        assertThrows(SmplNotFoundException.class, () -> configClient.delete(CONFIG_ID));
    }

    // -----------------------------------------------------------------------
    // update()
    // -----------------------------------------------------------------------

    @Test
    void update_sendsUpdatedFields() throws ApiException {
        com.smplkit.config.Config existing = new com.smplkit.config.Config(
                CONFIG_ID, "svc", "Old Name", "Old desc", null,
                Map.of("a", 1), Map.of(), null, null);

        ConfigResource updated = makeResource(CONFIG_ID, "svc", "New Name", "New desc",
                null, Map.of("a", itemDef(2, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), null, null);
        when(mockApi.updateConfig(eq(UUID.fromString(CONFIG_ID)), any()))
                .thenReturn(singleResponse(updated));

        UpdateConfigParams params = UpdateConfigParams.builder()
                .name("New Name")
                .description("New desc")
                .values(Map.of("a", 2))
                .build();
        com.smplkit.config.Config result = configClient.update(existing, params);

        assertEquals("New Name", result.name());
        verify(mockApi).updateConfig(eq(UUID.fromString(CONFIG_ID)), any());
    }

    @Test
    void update_preservesUnsetFields() throws ApiException {
        com.smplkit.config.Config existing = new com.smplkit.config.Config(
                CONFIG_ID, "svc", "My Name", "My Desc", null,
                Map.of("x", 10), Map.of(), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "My Name", "My Desc",
                null, Map.of("x", itemDef(10, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of(), null, null);
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        // No fields set on params — everything should be preserved
        UpdateConfigParams params = UpdateConfigParams.builder().build();
        com.smplkit.config.Config result = configClient.update(existing, params);

        assertEquals("My Name", result.name());
    }

    @Test
    void update_withEnvironments() throws ApiException {
        com.smplkit.config.Config existing = new com.smplkit.config.Config(
                CONFIG_ID, "svc", "Name", null, null, Map.of(), Map.of(), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null, null,
                Map.of(), Map.of("production", envOverride(Map.of("k", "v"))),
                null, null);
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        UpdateConfigParams params = UpdateConfigParams.builder()
                .environments(Map.of("production", Map.of("values", Map.of("k", "v"))))
                .build();
        com.smplkit.config.Config result = configClient.update(existing, params);

        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // setValues()
    // -----------------------------------------------------------------------

    @Test
    void setValues_mergesWithExistingEnvValues() throws ApiException {
        Map<String, Object> existingProdValues = Map.of("a", 1);
        Map<String, Object> existingEnv = new java.util.HashMap<>();
        existingEnv.put("values", existingProdValues);

        com.smplkit.config.Config existing = new com.smplkit.config.Config(
                CONFIG_ID, "svc", "Name", null, null, Map.of(),
                Map.of("production", existingEnv), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null, null,
                Map.of(), Map.of("production", envOverride(Map.of("a", 1, "b", 2))),
                null, null);
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        com.smplkit.config.Config result = configClient.setValues(existing, Map.of("b", 2), "production");

        assertNotNull(result);
        verify(mockApi).updateConfig(eq(UUID.fromString(CONFIG_ID)), any());
    }

    @Test
    void setValues_createsNewEnvIfAbsent() throws ApiException {
        com.smplkit.config.Config existing = new com.smplkit.config.Config(
                CONFIG_ID, "svc", "Name", null, null, Map.of(), Map.of(), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null, null,
                Map.of(), Map.of("staging", envOverride(Map.of("key", "val"))),
                null, null);
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        com.smplkit.config.Config result = configClient.setValues(existing, Map.of("key", "val"), "staging");

        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // setValue()
    // -----------------------------------------------------------------------

    @Test
    void setValue_setsSingleKey() throws ApiException {
        com.smplkit.config.Config existing = new com.smplkit.config.Config(
                CONFIG_ID, "svc", "Name", null, null, Map.of(), Map.of(), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null, null,
                Map.of(), Map.of("production", envOverride(Map.of("timeout", 60))),
                null, null);
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        com.smplkit.config.Config result = configClient.setValue(existing, "timeout", 60, "production");

        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // Error mapping
    // -----------------------------------------------------------------------

    @Test
    void apiException_500_mapsToSmplException() throws ApiException {
        when(mockApi.getConfig(any(UUID.class)))
                .thenThrow(new ApiException(500, "Internal Server Error"));

        SmplException ex = assertThrows(SmplException.class, () ->
                configClient.get(CONFIG_ID));
        assertEquals(500, ex.statusCode());
    }

    @Test
    void apiException_0_mapsToSmplException() throws ApiException {
        when(mockApi.getConfig(any(UUID.class)))
                .thenThrow(new ApiException("network failure"));

        // code 0 should still map to a SmplException
        assertThrows(SmplException.class, () -> configClient.get(CONFIG_ID));
    }

    // -----------------------------------------------------------------------
    // Config record
    // -----------------------------------------------------------------------

    @Test
    void configRecord_equality() {
        com.smplkit.config.Config c1 = new com.smplkit.config.Config("id1", "key", "name", null, null, Map.of(), Map.of(), null, null);
        com.smplkit.config.Config c2 = new com.smplkit.config.Config("id1", "key", "name", null, null, Map.of(), Map.of(), null, null);
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void configRecord_toString() {
        com.smplkit.config.Config config = new com.smplkit.config.Config("id1", "key1", "My Config", null, null, Map.of(), Map.of(), null, null);
        assertEquals("Config[id=id1, key=key1, name=My Config]", config.toString());
    }

    @Test
    void configRecord_nullItems_defaultToEmptyMap() {
        com.smplkit.config.Config config = new com.smplkit.config.Config("id1", "key1", "name", null, null, null, null, null, null);
        assertNotNull(config.items());
        assertTrue(config.items().isEmpty());
        assertNotNull(config.environments());
        assertTrue(config.environments().isEmpty());
    }

    @Test
    void configRecord_requiresNonNullIdKeyName() {
        assertThrows(NullPointerException.class, () ->
                new com.smplkit.config.Config(null, "key", "name", null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () ->
                new com.smplkit.config.Config("id", null, "name", null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () ->
                new com.smplkit.config.Config("id", "key", null, null, null, null, null, null, null));
    }

    // -----------------------------------------------------------------------
    // CreateConfigParams
    // -----------------------------------------------------------------------

    @Test
    void createConfigParams_builder() {
        CreateConfigParams params = CreateConfigParams.builder("Test Config")
                .key("test_config")
                .description("A test config")
                .parent("parent-id")
                .values(Map.of("key", "value"))
                .build();

        assertEquals("Test Config", params.name());
        assertEquals("test_config", params.key());
        assertEquals("A test config", params.description());
        assertEquals("parent-id", params.parent());
        assertEquals(Map.of("key", "value"), params.values());
    }

    @Test
    void createConfigParams_minimalBuilder() {
        CreateConfigParams params = CreateConfigParams.builder("Minimal").build();

        assertEquals("Minimal", params.name());
        assertNull(params.key());
        assertNull(params.description());
        assertNull(params.parent());
        assertNull(params.values());
    }

    @Test
    void createConfigParams_requiresName() {
        assertThrows(NullPointerException.class, () ->
                CreateConfigParams.builder(null));
    }

    // --- Prescriptive access (Phase 2) ---

    @Test
    void isConnected_falseByDefault() {
        assertFalse(configClient.isConnected());
    }

    @Test
    void connectInternal_setsConnectedState() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull()))
                .thenReturn(new ConfigListResponse().data(java.util.List.of()));
        configClient.connectInternal("production");
        assertTrue(configClient.isConnected());
    }

    @Test
    void getValue_returnsNullWhenNotConnected() {
        assertNull(configClient.getValue("cfg", "key"));
    }

    @Test
    void getValues_returnsNullWhenNotConnected() {
        assertNull(configClient.getValues("cfg"));
    }
}
