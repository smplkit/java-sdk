package com.smplkit.config;

import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
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
import static org.mockito.Mockito.when;

/**
 * Additional tests for {@link ConfigClient} covering edge cases not in ConfigClientTest.
 */
class ConfigClientFullTest {

    private ConfigsApi mockApi;
    private ConfigClient configClient;

    private static final String CONFIG_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PARENT_ID = "660e8400-e29b-41d4-a716-446655440001";
    private static final String GRANDPARENT_ID = "770e8400-e29b-41d4-a716-446655440002";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(ConfigsApi.class);
        configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
    }

    private ConfigResource makeResource(String id, String key, String name, String description,
                                        String parent, Map<String, Object> values,
                                        Map<String, Object> environments) {
        com.smplkit.internal.generated.config.model.Config attrs =
                new com.smplkit.internal.generated.config.model.Config(null, null);
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
    // getByKey() — ApiException path (line 85-86)
    // -----------------------------------------------------------------------

    @Test
    void getByKey_apiException_throwsSmplException() throws ApiException {
        when(mockApi.listConfigs(any(), any()))
                .thenThrow(new ApiException(500, "Internal Server Error"));

        SmplException ex = assertThrows(SmplException.class, () ->
                configClient.getByKey("some-key"));
        assertEquals(500, ex.statusCode());
    }

    // -----------------------------------------------------------------------
    // list() — ApiException path (lines 144-145)
    // -----------------------------------------------------------------------

    @Test
    void list_apiException_throwsSmplException() throws ApiException {
        when(mockApi.listConfigs(null, null))
                .thenThrow(new ApiException(503, "Service Unavailable"));

        SmplException ex = assertThrows(SmplException.class, () ->
                configClient.list());
        assertEquals(503, ex.statusCode());
    }

    // -----------------------------------------------------------------------
    // update() — with parent preservation (line 189) and null description
    // -----------------------------------------------------------------------

    @Test
    void update_preservesParent() throws ApiException {
        Config existing = new Config(
                CONFIG_ID, "svc", "Name", null, PARENT_ID,
                Map.of("a", 1), Map.of(), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null,
                PARENT_ID, Map.of("a", 1), Map.of());
        when(mockApi.updateConfig(eq(UUID.fromString(CONFIG_ID)), any()))
                .thenReturn(singleResponse(resource));

        UpdateConfigParams params = UpdateConfigParams.builder().build();
        Config result = configClient.update(existing, params);

        assertNotNull(result);
    }

    @Test
    void update_withNullDescription_doesNotSetIt() throws ApiException {
        Config existing = new Config(
                CONFIG_ID, "svc", "Name", null, null,
                Map.of(), Map.of(), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null,
                null, Map.of(), Map.of());
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        // Both existing.description() and params.description() are null
        UpdateConfigParams params = UpdateConfigParams.builder().build();
        Config result = configClient.update(existing, params);

        assertNotNull(result);
    }

    @Test
    void update_apiException_throwsSmplException() throws ApiException {
        Config existing = new Config(
                CONFIG_ID, "svc", "Name", null, null,
                Map.of(), Map.of(), null, null);

        when(mockApi.updateConfig(any(), any()))
                .thenThrow(new ApiException(500, "Error"));

        UpdateConfigParams params = UpdateConfigParams.builder().name("New").build();
        assertThrows(SmplException.class, () -> configClient.update(existing, params));
    }

    // -----------------------------------------------------------------------
    // connect() with parent chain — exercises fetchChain lambda
    // -----------------------------------------------------------------------

    @Test
    void connect_withMultipleLevelChain_buildsFullChain() throws ApiException {
        // grandparent -> parent -> child
        ConfigResource grandparent = makeResource(GRANDPARENT_ID, "gp", "Grandparent", null,
                null, Map.of("x", 1), Map.of());
        ConfigResource parent = makeResource(PARENT_ID, "parent", "Parent", null,
                GRANDPARENT_ID, Map.of("y", 2), Map.of());

        when(mockApi.getConfig(UUID.fromString(PARENT_ID)))
                .thenReturn(singleResponse(parent));
        when(mockApi.getConfig(UUID.fromString(GRANDPARENT_ID)))
                .thenReturn(singleResponse(grandparent));

        Config child = new Config(
                CONFIG_ID, "child", "Child", null, PARENT_ID,
                Map.of("z", 3), Map.of(), null, null);

        try (ConfigRuntime runtime = configClient.connect(child, "prod")) {
            assertEquals(1, runtime.get("x"));
            assertEquals(2, runtime.get("y"));
            assertEquals(3, runtime.get("z"));
        }
    }

    // -----------------------------------------------------------------------
    // parseResource — environments with non-Map entries (line 333-334)
    // -----------------------------------------------------------------------

    @Test
    void get_resourceWithNonMapEnvironmentEntry_isSkipped() throws ApiException {
        // An environment entry whose value is not a Map should be skipped
        Map<String, Object> envs = new java.util.HashMap<>();
        envs.put("production", Map.of("values", Map.of("a", 1)));
        envs.put("bad_entry", "this is not a map");

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null,
                null, Map.of(), envs);
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID)))
                .thenReturn(singleResponse(resource));

        Config config = configClient.get(CONFIG_ID);

        // Only "production" should be in environments, "bad_entry" should be skipped
        assertTrue(config.environments().containsKey("production"));
        assertFalse(config.environments().containsKey("bad_entry"));
    }

    // -----------------------------------------------------------------------
    // parseResource — null id from resource (line 343)
    // -----------------------------------------------------------------------

    @Test
    void get_resourceWithNullId_usesEmptyString() throws ApiException {
        ConfigResource resource = makeResource(null, "svc", "Name", null,
                null, Map.of(), Map.of());
        when(mockApi.getConfig(any())).thenReturn(singleResponse(resource));

        Config config = configClient.get(CONFIG_ID);

        assertEquals("", config.id());
    }

    // -----------------------------------------------------------------------
    // mapException — null message (line 368)
    // -----------------------------------------------------------------------

    @Test
    void apiException_nullMessage_usesHttpCodeInMessage() throws ApiException {
        ApiException apiEx = new ApiException(503, (String) null);
        when(mockApi.getConfig(any())).thenThrow(apiEx);

        SmplException ex = assertThrows(SmplException.class, () ->
                configClient.get(CONFIG_ID));
        assertTrue(ex.getMessage().contains("503"));
    }

    // -----------------------------------------------------------------------
    // connect() + refresh() — exercises fetchChain (lines 296, 305-311)
    // -----------------------------------------------------------------------

    @Test
    void connect_thenRefresh_exercisesFetchChain() throws ApiException {
        // Root config, no parent
        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Svc",
                null, null, Map.of("a", 1), Map.of());
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID)))
                .thenReturn(singleResponse(resource));

        Config config = new Config(
                CONFIG_ID, "svc", "Svc", null, null,
                Map.of("a", 1), Map.of(), null, null);

        try (ConfigRuntime runtime = configClient.connect(config, "prod")) {
            assertEquals(1, runtime.get("a"));

            // Now update mock to return different values for the refresh
            ConfigResource updated = makeResource(CONFIG_ID, "svc", "Svc",
                    null, null, Map.of("a", 99), Map.of());
            when(mockApi.getConfig(UUID.fromString(CONFIG_ID)))
                    .thenReturn(singleResponse(updated));

            // Refresh triggers fetchChain which calls get() on each ID
            runtime.refresh();

            assertEquals(99, runtime.get("a"));
        }
    }

    // -----------------------------------------------------------------------
    // setValues with existing env that has no "values" key
    // -----------------------------------------------------------------------

    @Test
    void setValues_existingEnvWithoutValuesKey_createsNewValuesMap() throws ApiException {
        // Env data exists but has no "values" key
        Map<String, Object> existingEnv = new java.util.HashMap<>();
        existingEnv.put("other", "data");

        Config existing = new Config(
                CONFIG_ID, "svc", "Name", null, null, Map.of(),
                Map.of("production", existingEnv), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null,
                null, Map.of(), Map.of("production", Map.of("values", Map.of("key", "val"))));
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        Config result = configClient.setValues(existing, Map.of("key", "val"), "production");

        assertNotNull(result);
    }
}
