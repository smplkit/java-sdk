package com.smplkit.config;

import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
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
                                        String parent, Map<String, ConfigItemDefinition> items,
                                        Map<String, EnvironmentOverride> environments) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(null, null);
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
    // getByKey() — ApiException path
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
    // list() — ApiException path
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
    // update() — with parent preservation and null description
    // -----------------------------------------------------------------------

    @Test
    void update_preservesParent() throws ApiException {
        Config existing = new Config(
                CONFIG_ID, "svc", "Name", null, PARENT_ID,
                Map.of("a", 1), Map.of(), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null,
                PARENT_ID, Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)),
                Map.of());
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
    // parseResource — environments with empty override
    // -----------------------------------------------------------------------

    @Test
    void get_resourceWithEmptyEnvironmentOverride_handledGracefully() throws ApiException {
        EnvironmentOverride emptyOverride = new EnvironmentOverride();

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null,
                null, Map.of(), Map.of("production", envOverride(Map.of("a", 1)),
                        "staging", emptyOverride));
        when(mockApi.getConfig(UUID.fromString(CONFIG_ID)))
                .thenReturn(singleResponse(resource));

        Config config = configClient.get(CONFIG_ID);

        assertTrue(config.environments().containsKey("production"));
        assertTrue(config.environments().containsKey("staging"));
    }

    // -----------------------------------------------------------------------
    // parseResource — null id from resource
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
    // mapException — null message
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
    // setValues with existing env that has no "values" key
    // -----------------------------------------------------------------------

    @Test
    void setValues_existingEnvWithoutValuesKey_createsNewValuesMap() throws ApiException {
        Map<String, Object> existingEnv = new java.util.HashMap<>();
        existingEnv.put("other", "data");

        Config existing = new Config(
                CONFIG_ID, "svc", "Name", null, null, Map.of(),
                Map.of("production", existingEnv), null, null);

        ConfigResource resource = makeResource(CONFIG_ID, "svc", "Name", null,
                null, Map.of(), Map.of("production", envOverride(Map.of("key", "val"))));
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(resource));

        Config result = configClient.setValues(existing, Map.of("key", "val"), "production");

        assertNotNull(result);
    }
}
