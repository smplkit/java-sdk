package com.smplkit.config;

import com.smplkit.errors.SmplError;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigRequest;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Integration-style save() round-trips, update edge cases, and parseResource
 * corner cases for the fused {@link ConfigClient}.
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

    private static Map<String, Object> envOverride(Map<String, Object> rawValues) {
        return rawValues != null ? new HashMap<>(rawValues) : new HashMap<>();
    }

    private ConfigResponse singleResponse(ConfigResource resource) {
        ConfigResponse response = new ConfigResponse();
        response.setData(resource);
        return response;
    }

    // -----------------------------------------------------------------------
    // save() create + update round-trip
    // -----------------------------------------------------------------------

    @Test
    void save_fullRoundTrip_createThenUpdate() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource created = makeResource(CONFIG_ID, "Svc", "initial desc",
                null, Map.of("a", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of(),
                now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(created));

        Config config = configClient.new_("svc", "Svc", "initial desc", (String) null);
        config.setNumber("a", 1);
        config.save();

        assertEquals(CONFIG_ID, config.getId());
        assertEquals("initial desc", config.getDescription());

        ConfigResource updated = makeResource(CONFIG_ID, "Svc Updated", "new desc",
                null, Map.of("a", itemDef(99, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of(),
                now, now);
        when(mockApi.updateConfig(eq(CONFIG_ID), any()))
                .thenReturn(singleResponse(updated));

        config.setName("Svc Updated");
        config.setDescription("new desc");
        config.setNumber("a", 99);
        config.save();

        assertEquals("Svc Updated", config.getName());
        assertEquals("new desc", config.getDescription());
        verify(mockApi).updateConfig(eq(CONFIG_ID), any());
    }

    @Test
    void save_update_preservesParent() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource created = makeResource(CONFIG_ID, "Child", null,
                PARENT_ID, Map.of(), Map.of(), now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(created));

        Config config = configClient.new_("child", null, null, PARENT_ID);
        config.save();
        assertEquals(PARENT_ID, config.getParent());

        ConfigResource updated = makeResource(CONFIG_ID, "Child Updated", null,
                PARENT_ID, Map.of(), Map.of(), now, now);
        ArgumentCaptor<ConfigRequest> captor = ArgumentCaptor.forClass(ConfigRequest.class);
        when(mockApi.updateConfig(any(), captor.capture())).thenReturn(singleResponse(updated));

        config.setName("Child Updated");
        config.save();

        assertEquals(PARENT_ID, config.getParent());
        // The update body carried the parent through.
        assertEquals(PARENT_ID, captor.getValue().getData().getAttributes().getParent());
    }

    @Test
    void save_update_apiException_throwsSmplError() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        ConfigResource created = makeResource(CONFIG_ID, "Svc", null,
                null, Map.of(), Map.of(), now, now);
        when(mockApi.createConfig(any())).thenReturn(singleResponse(created));

        Config config = configClient.new_("svc");
        config.save();

        when(mockApi.updateConfig(any(), any()))
                .thenThrow(new ApiException(500, "Error"));

        config.setName("New Name");
        assertThrows(SmplError.class, config::save);
    }

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
        ArgumentCaptor<ConfigRequest> captor = ArgumentCaptor.forClass(ConfigRequest.class);
        when(mockApi.updateConfig(any(), captor.capture())).thenReturn(singleResponse(updated));

        config.setString("k", "v", "production");
        config.save();

        // The update body carried the flat per-env override.
        Map<String, Map<String, Object>> envs = captor.getValue().getData().getAttributes().getEnvironments();
        assertEquals("v", envs.get("production").get("k"));
    }

    // -----------------------------------------------------------------------
    // _createConfig with plain (untyped) items — inferType path
    // -----------------------------------------------------------------------

    @Test
    void save_create_inferTypeFromPlainItemValues() throws ApiException {
        // Construct a config carrying plain (non-typed-map) item values so the
        // wire conversion has to infer each item's type.
        Map<String, Object> plainItems = new HashMap<>();
        plainItems.put("str", "hello");
        plainItems.put("num", 42);
        plainItems.put("bool", true);
        plainItems.put("json", Map.of("k", "v"));
        Config config = new Config(configClient, "svc", "Svc", null, null,
                plainItems, new HashMap<>(), null, null);

        ConfigResource created = makeResource(CONFIG_ID, "Svc", null, null,
                Map.of(), Map.of(), OffsetDateTime.now(), OffsetDateTime.now());
        ArgumentCaptor<com.smplkit.internal.generated.config.model.ConfigCreateRequest> captor =
                ArgumentCaptor.forClass(com.smplkit.internal.generated.config.model.ConfigCreateRequest.class);
        when(mockApi.createConfig(captor.capture())).thenReturn(singleResponse(created));

        config.save();

        Map<String, ConfigItemDefinition> sent =
                captor.getValue().getData().getAttributes().getItems();
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, sent.get("str").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER, sent.get("num").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN, sent.get("bool").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.JSON, sent.get("json").getType());
    }

    @Test
    void save_create_typedItem_withDescriptionAndMissingType() throws ApiException {
        // A typed item map carrying a description but NO "type" key exercises
        // makeItems' description-present + type-absent branches.
        Map<String, Object> typed = new HashMap<>();
        Map<String, Object> withDesc = new HashMap<>();
        withDesc.put("value", "v");
        withDesc.put("type", "STRING");
        withDesc.put("description", "a described item");
        Map<String, Object> noType = new HashMap<>();
        noType.put("value", 9); // no "type" key
        typed.put("described", withDesc);
        typed.put("untyped", noType);

        Config config = new Config(configClient, "svc", "Svc", null, null,
                typed, new HashMap<>(), null, null);

        ConfigResource created = makeResource(CONFIG_ID, "Svc", null, null,
                Map.of(), Map.of(), OffsetDateTime.now(), OffsetDateTime.now());
        ArgumentCaptor<com.smplkit.internal.generated.config.model.ConfigCreateRequest> captor =
                ArgumentCaptor.forClass(com.smplkit.internal.generated.config.model.ConfigCreateRequest.class);
        when(mockApi.createConfig(captor.capture())).thenReturn(singleResponse(created));

        config.save();

        Map<String, ConfigItemDefinition> sent =
                captor.getValue().getData().getAttributes().getItems();
        assertEquals("a described item", sent.get("described").getDescription());
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, sent.get("described").getType());
        // The untyped item kept its value but carries no declared type.
        assertEquals(9, sent.get("untyped").getValue());
        assertNull(sent.get("untyped").getType());
    }

    @Test
    void registerConfigItem_nullType_mapsToNullTypeEnum() throws Exception {
        // A null item type label drives toTypeEnum(null) -> null on the wire.
        com.smplkit.internal.generated.config.api.ConfigsApi api = mockApi;
        when(api.bulkRegisterConfigs(any()))
                .thenReturn(new com.smplkit.internal.generated.config.model.ConfigBulkResponse());
        configClient.registerConfig("billing", "svc", "prod", null, null, null);
        configClient.registerConfigItem("billing", "x", null, 5, null);

        ArgumentCaptor<com.smplkit.internal.generated.config.model.ConfigBulkRequest> captor =
                ArgumentCaptor.forClass(com.smplkit.internal.generated.config.model.ConfigBulkRequest.class);
        configClient.flush();
        verify(api).bulkRegisterConfigs(captor.capture());
        assertNull(captor.getValue().getConfigs().get(0).getItems().get("x").getType());
    }

    // -----------------------------------------------------------------------
    // parseResource — items carrying only a value (no type/description)
    // -----------------------------------------------------------------------

    @Test
    void get_itemWithoutTypeOrDescription_parsesValueOnly() throws ApiException {
        ConfigItemDefinition bare = new ConfigItemDefinition();
        bare.setValue("only-value");
        ConfigResource resource = makeResource(CONFIG_ID, "Svc", null, null,
                Map.of("k", bare), Map.of(), OffsetDateTime.now(), OffsetDateTime.now());
        when(mockApi.getConfig("svc")).thenReturn(singleResponse(resource));

        Config config = configClient.get("svc");

        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) config.itemsRaw().get("k");
        assertEquals("only-value", raw.get("value"));
        assertFalse(raw.containsKey("type"));
        assertFalse(raw.containsKey("description"));
    }

    // -----------------------------------------------------------------------
    // buildChain — parent fetched on demand when not in the supplied list
    // -----------------------------------------------------------------------

    @Test
    void resolveParentChain_fetchesUnlistedParent_viaClientGet() throws ApiException {
        // A standalone config whose parent isn't in the resolution list forces
        // buildChain to fetch the parent through the client.
        ConfigResource parentRes = makeResource(PARENT_ID, "Parent", null, null,
                Map.of("base", itemDef(7, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of(),
                OffsetDateTime.now(), OffsetDateTime.now());
        when(mockApi.getConfig(PARENT_ID)).thenReturn(singleResponse(parentRes));

        ConfigResource child = makeResource(CONFIG_ID, "Child", null, PARENT_ID,
                Map.of("own", itemDef(1, ConfigItemDefinition.TypeEnum.NUMBER)), Map.of(),
                OffsetDateTime.now(), OffsetDateTime.now());
        when(mockApi.listConfigs(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ConfigListResponse().data(List.of(child)));
        configClient.setEnvironment("production");

        configClient.ensureConnected();

        // The unlisted parent was fetched and folded into the child's resolved view.
        assertEquals(7, configClient.getValue(CONFIG_ID, "base"));
        assertEquals(1, configClient.getValue(CONFIG_ID, "own"));
        verify(mockApi).getConfig(PARENT_ID);
    }
}
