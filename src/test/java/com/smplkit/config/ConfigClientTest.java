package com.smplkit.config;

import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.errors.SmplException;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.Config;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                                        String parent, Map<String, Object> values,
                                        Map<String, Object> environments,
                                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        // Use @JsonCreator constructor for timestamps; setters for other fields
        Config attrs = new Config(createdAt, updatedAt);
        if (name != null) attrs.setName(name); else attrs.setName("");
        if (key != null) attrs.setKey(key);
        if (description != null) attrs.setDescription(description);
        if (parent != null) attrs.setParent(parent);
        if (values != null) attrs.setValues(values);
        if (environments != null) attrs.setEnvironments(environments);

        ConfigResource resource = new ConfigResource();
        resource.setId(id);
        resource.setType(ConfigResource.TypeEnum.CONFIG);
        resource.setAttributes(attrs);
        return resource;
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
                "Main user service config", null, Map.of("timeout", 30), Map.of(), now, now);
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID))).thenReturn(singleResponse(resource));

        com.smplkit.config.Config config = configClient.get(CONFIG_ID);

        assertEquals(CONFIG_ID, config.id());
        assertEquals("user_service", config.key());
        assertEquals("User Service", config.name());
        assertEquals("Main user service config", config.description());
        assertNull(config.parent());
        assertEquals(30, config.values().get("timeout"));
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
        assertTrue(config.values().isEmpty());
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
                "Main user service config", null, Map.of("timeout", 30), Map.of(), null, null);
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
                null, Map.of("a", 2), Map.of(), null, null);
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
                null, Map.of("x", 10), Map.of(), null, null);
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
                Map.of(), Map.of("production", Map.of("values", Map.of("k", "v"))), null, null);
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
                Map.of(), Map.of("production", Map.of("values", Map.of("a", 1, "b", 2))), null, null);
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
                Map.of(), Map.of("staging", Map.of("values", Map.of("key", "val"))), null, null);
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
                Map.of(), Map.of("production", Map.of("values", Map.of("timeout", 60))), null, null);
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        com.smplkit.config.Config result = configClient.setValue(existing, "timeout", 60, "production");

        assertNotNull(result);
    }

    // -----------------------------------------------------------------------
    // connect()
    // -----------------------------------------------------------------------

    @Test
    void connect_rootConfig_returnsRuntimeWithValues() throws ApiException {
        // Config with no parent — chain is just [config]
        ConfigResource resource = makeResource(CONFIG_ID, "user_service", "User Service",
                null, null, Map.of("timeout", 30), Map.of(), null, null);
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID))).thenReturn(singleResponse(resource));

        com.smplkit.config.Config config = new com.smplkit.config.Config(
                CONFIG_ID, "user_service", "User Service", null, null,
                Map.of("timeout", 30), Map.of(), null, null);

        try (ConfigRuntime runtime = configClient.connect(config, "production")) {
            assertEquals(30, runtime.get("timeout"));
        }
    }

    @Test
    void connect_withParent_buildsChain() throws ApiException {
        // Child config whose parent is another config
        ConfigResource parent = makeResource(CONFIG_ID, "common", "Common", null, null,
                Map.of("shared", "yes"), Map.of(), null, null);
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID))).thenReturn(singleResponse(parent));

        com.smplkit.config.Config childConfig = new com.smplkit.config.Config(
                CONFIG_ID_2, "user_service", "User Service", null, CONFIG_ID,
                Map.of("timeout", 30), Map.of(), null, null);

        try (ConfigRuntime runtime = configClient.connect(childConfig, "production")) {
            assertEquals(30, runtime.get("timeout"));
            assertEquals("yes", runtime.get("shared")); // inherited from parent
        }
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
    void configRecord_nullValues_defaultToEmptyMap() {
        com.smplkit.config.Config config = new com.smplkit.config.Config("id1", "key1", "name", null, null, null, null, null, null);
        assertNotNull(config.values());
        assertTrue(config.values().isEmpty());
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
}
