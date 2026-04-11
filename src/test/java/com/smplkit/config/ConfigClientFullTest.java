package com.smplkit.config;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Additional integration-style tests for {@link ConfigClient} covering
 * save() round-trips, update edge cases, and parseResource corner cases.
 */
class ConfigClientFullTest {

    private ConfigsApi mockApi;
    private ConfigClient configClient;

    private static final String CONFIG_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PARENT_ID = "660e8400-e29b-41d4-a716-446655440001";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(ConfigsApi.class);
        configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
    }

    private ConfigResource makeResource(String id, String name, String description,
                                        String parent, Map<String, ConfigItemDefinition> items,
                                        Map<String, EnvironmentOverride> environments) {
        return makeResource(id, name, description, parent, items, environments, null, null);
    }

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
    // save() create + update round-trip
    // -----------------------------------------------------------------------

    @Test
    void save_fullRoundTrip_createThenUpdate() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        // 1. Create
        ConfigResource created = makeResource(CONFIG_ID, "Svc", "initial desc",
                null, Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of(),
                now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(created));

        Config config = configClient.new_("svc", "Svc", "initial desc", null);
        config.setItems(Map.of("a", Map.of("value", 1)));
        config.save();

        assertEquals(CONFIG_ID, config.getId());
        assertEquals("initial desc", config.getDescription());

        // 2. Mutate and update
        ConfigResource updated = makeResource(CONFIG_ID, "Svc Updated", "new desc",
                null, Map.of("a", itemDef(99, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of(),
                now, now);
        when(mockApi.updateConfig(eq(CONFIG_ID), any()))
                .thenReturn(singleResponse(updated));

        config.setName("Svc Updated");
        config.setDescription("new desc");
        config.setItems(Map.of("a", Map.of("value", 99)));
        config.save();

        assertEquals("Svc Updated", config.getName());
        assertEquals("new desc", config.getDescription());
        verify(mockApi).updateConfig(eq(CONFIG_ID), any());
    }

    // -----------------------------------------------------------------------
    // save() update with parent preservation
    // -----------------------------------------------------------------------

    @Test
    void save_update_preservesParent() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource created = makeResource(CONFIG_ID, "Child", null,
                PARENT_ID, Map.of(), Map.of(), now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(created));

        Config config = configClient.new_("child", null, null, PARENT_ID);
        config.save();

        assertEquals(PARENT_ID, config.getParent());

        // Update should preserve parent
        ConfigResource updated = makeResource(CONFIG_ID, "Child Updated", null,
                PARENT_ID, Map.of(), Map.of(), now, now);
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(updated));

        config.setName("Child Updated");
        config.save();

        assertEquals(PARENT_ID, config.getParent());
    }

    // -----------------------------------------------------------------------
    // save() update API error
    // -----------------------------------------------------------------------

    @Test
    void save_update_apiException_throwsSmplException() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        // Setup: create a config first to give it createdAt
        ConfigResource created = makeResource(CONFIG_ID, "Svc", null,
                null, Map.of(), Map.of(), now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(created));

        Config config = configClient.new_("svc");
        config.save();

        // Now update fails
        when(mockApi.updateConfig(any(), any()))
                .thenThrow(new ApiException(500, "Error"));

        config.setName("New Name");
        assertThrows(SmplException.class, config::save);
    }

    // -----------------------------------------------------------------------
    // save() with environments
    // -----------------------------------------------------------------------

    @Test
    void save_update_withEnvironments() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource created = makeResource(CONFIG_ID, "Name", null, null,
                Map.of(), Map.of(), now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(created));

        Config config = configClient.new_("svc");
        config.save();

        ConfigResource updated = makeResource(CONFIG_ID, "Name", null, null,
                Map.of(), Map.of("production", envOverride(Map.of("k", "v"))), now, now);
        when(mockApi.updateConfig(any(), any())).thenReturn(singleResponse(updated));

        Map<String, Object> envData = new HashMap<>();
        envData.put("values", Map.of("k", "v"));
        config.setEnvironments(Map.of("production", envData));
        config.save();

        assertNotNull(config);
    }

    // -----------------------------------------------------------------------
    // wrapValuesAsItems
    // -----------------------------------------------------------------------

    @Test
    void wrapValuesAsItems_wrapsAllTypes() {
        Map<String, Object> values = Map.of(
                "str", "hello",
                "num", 42,
                "bool", true,
                "json", Map.of("k", "v")
        );
        Map<String, ConfigItemDefinition> wrapped = ConfigClient.wrapValuesAsItems(values);

        assertEquals(4, wrapped.size());
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, wrapped.get("str").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER, wrapped.get("num").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN, wrapped.get("bool").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.JSON, wrapped.get("json").getType());
    }

    // -----------------------------------------------------------------------
    // wrapEnvironments edge cases
    // -----------------------------------------------------------------------

    @Test
    void wrapEnvironments_handlesNonMapValue() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("staging", "not-a-map");

        Map<String, EnvironmentOverride> wrapped = ConfigClient.wrapEnvironments(envs);

        assertNotNull(wrapped.get("staging"));
        // Should not throw, just produce an empty override
    }

    @Test
    void wrapEnvironments_handlesMapWithoutValuesKey() {
        Map<String, Object> envData = new HashMap<>();
        envData.put("other", "data");

        Map<String, Object> envs = Map.of("staging", envData);
        Map<String, EnvironmentOverride> wrapped = ConfigClient.wrapEnvironments(envs);

        assertNotNull(wrapped.get("staging"));
    }

    // -----------------------------------------------------------------------
    // Config.getClient() package-private getter
    // -----------------------------------------------------------------------

    @Test
    void config_getClient_returnsClient() {
        Config config = configClient.new_("test");
        assertSame(configClient, config.getClient());
    }

    @Test
    void config_setClient_changesClient() {
        Config config = configClient.new_("test");
        ConfigsApi otherApi = Mockito.mock(ConfigsApi.class);
        ConfigClient otherClient = new ConfigClient(otherApi, HttpClient.newHttpClient(), "other");
        config.setClient(otherClient);
        assertSame(otherClient, config.getClient());
    }
}
