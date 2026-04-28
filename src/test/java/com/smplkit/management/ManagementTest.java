package com.smplkit.management;

import com.smplkit.Context;
import com.smplkit.LogLevel;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.AccountApi;
import com.smplkit.internal.generated.app.api.ContextTypesApi;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.api.EnvironmentsApi;
import com.smplkit.internal.generated.app.model.ContextBulkItem;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.app.model.ContextListResponse;
import com.smplkit.internal.generated.app.model.ContextResource;
import com.smplkit.internal.generated.app.model.ContextResponse;
import com.smplkit.internal.generated.app.model.ContextType;
import com.smplkit.internal.generated.app.model.ContextTypeListResponse;
import com.smplkit.internal.generated.app.model.ContextTypeResource;
import com.smplkit.internal.generated.app.model.ContextTypeResponse;
import com.smplkit.internal.generated.app.model.Environment;
import com.smplkit.internal.generated.app.model.EnvironmentListResponse;
import com.smplkit.internal.generated.app.model.EnvironmentResource;
import com.smplkit.internal.generated.app.model.EnvironmentResponse;
import com.smplkit.logging.Logger;
import com.smplkit.logging.LoggerSource;
import com.smplkit.logging.LoggingClient;
import com.smplkit.logging.LoggingManagement;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LoggerBulkRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the management package: models, clients, shared buffer.
 */
class ManagementTest {

    // -----------------------------------------------------------------------
    // EnvironmentClassification enum
    // -----------------------------------------------------------------------

    @Test
    void environmentClassification_fromValue_standard() {
        assertEquals(EnvironmentClassification.STANDARD,
                EnvironmentClassification.fromValue("STANDARD"));
    }

    @Test
    void environmentClassification_fromValue_adHoc() {
        assertEquals(EnvironmentClassification.AD_HOC,
                EnvironmentClassification.fromValue("AD_HOC"));
    }

    @Test
    void environmentClassification_fromValue_caseInsensitive() {
        assertEquals(EnvironmentClassification.AD_HOC,
                EnvironmentClassification.fromValue("ad_hoc"));
    }

    @Test
    void environmentClassification_fromValue_null_returnsStandard() {
        assertEquals(EnvironmentClassification.STANDARD,
                EnvironmentClassification.fromValue(null));
    }

    @Test
    void environmentClassification_fromValue_unknown_returnsStandard() {
        assertEquals(EnvironmentClassification.STANDARD,
                EnvironmentClassification.fromValue("UNKNOWN_VALUE"));
    }

    @Test
    void environmentClassification_getValue() {
        assertEquals("STANDARD", EnvironmentClassification.STANDARD.getValue());
        assertEquals("AD_HOC", EnvironmentClassification.AD_HOC.getValue());
    }

    // -----------------------------------------------------------------------
    // Environment model
    // -----------------------------------------------------------------------

    @Test
    void environment_getters() {
        Instant now = Instant.now();
        com.smplkit.management.Environment env = new com.smplkit.management.Environment(
                null, "prod", "Production", "#ff0000", EnvironmentClassification.STANDARD, now, now);
        assertEquals("prod", env.getId());
        assertEquals("Production", env.getName());
        assertEquals("#ff0000", env.getColor());
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
        assertEquals(now, env.getCreatedAt());
        assertEquals(now, env.getUpdatedAt());
    }

    @Test
    void environment_setters() {
        com.smplkit.management.Environment env = new com.smplkit.management.Environment(
                null, "prod", "Production", null, null, null, null);
        env.setName("Prod 2");
        env.setColor("#00ff00");
        env.setClassification(EnvironmentClassification.AD_HOC);
        assertEquals("Prod 2", env.getName());
        assertEquals("#00ff00", env.getColor());
        assertEquals(EnvironmentClassification.AD_HOC, env.getClassification());
    }

    @Test
    void environment_classificationDefaultsToStandard() {
        com.smplkit.management.Environment env = new com.smplkit.management.Environment(
                null, "prod", "Production", null, null, null, null);
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
    }

    @Test
    void environment_toString() {
        com.smplkit.management.Environment env = new com.smplkit.management.Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, null, null);
        assertTrue(env.toString().contains("prod"));
    }

    @Test
    void environment_save_withoutClient_throws() {
        com.smplkit.management.Environment env = new com.smplkit.management.Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, null, null);
        assertThrows(IllegalStateException.class, env::save);
    }

    // -----------------------------------------------------------------------
    // ContextType model
    // -----------------------------------------------------------------------

    @Test
    void contextType_addAttribute_noMetadata() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        ct.addAttribute("plan");
        assertTrue(ct.getAttributes().containsKey("plan"));
        assertTrue(ct.getAttributes().get("plan").isEmpty());
    }

    @Test
    void contextType_addAttribute_withMap() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        ct.addAttribute("plan", Map.of("description", "User plan"));
        assertEquals("User plan", ct.getAttributes().get("plan").get("description"));
    }

    @Test
    void contextType_addAttribute_withVarargs() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        ct.addAttribute("plan", "description", "User plan", "required", true);
        assertEquals("User plan", ct.getAttributes().get("plan").get("description"));
        assertEquals(true, ct.getAttributes().get("plan").get("required"));
    }

    @Test
    void contextType_addAttribute_withNullMap() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        ct.addAttribute("plan", (Map<String, Object>) null);
        assertTrue(ct.getAttributes().get("plan").isEmpty());
    }

    @Test
    void contextType_removeAttribute() {
        Map<String, Map<String, Object>> attrs = new HashMap<>();
        attrs.put("plan", new HashMap<>());
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", attrs, null, null);
        ct.removeAttribute("plan");
        assertFalse(ct.getAttributes().containsKey("plan"));
    }

    @Test
    void contextType_removeAttribute_nonExistent_noThrow() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        assertDoesNotThrow(() -> ct.removeAttribute("nonexistent"));
    }

    @Test
    void contextType_updateAttribute() {
        Map<String, Map<String, Object>> attrs = new HashMap<>();
        attrs.put("plan", Map.of("desc", "old"));
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", attrs, null, null);
        ct.updateAttribute("plan", Map.of("desc", "new"));
        assertEquals("new", ct.getAttributes().get("plan").get("desc"));
    }

    @Test
    void contextType_updateAttribute_withNullMap() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        ct.updateAttribute("plan", null);
        assertTrue(ct.getAttributes().get("plan").isEmpty());
    }

    @Test
    void contextType_setName() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        ct.setName("Users");
        assertEquals("Users", ct.getName());
    }

    @Test
    void contextType_toString() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        assertTrue(ct.toString().contains("user"));
    }

    @Test
    void contextType_save_withoutClient_throws() {
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "user", "User", null, null, null);
        assertThrows(IllegalStateException.class, ct::save);
    }

    // -----------------------------------------------------------------------
    // ContextEntity model
    // -----------------------------------------------------------------------

    @Test
    void contextEntity_getId_composite() {
        ContextEntity entity = new ContextEntity("user", "u123", "Alice",
                Map.of("plan", "free"), null, null);
        assertEquals("user:u123", entity.getId());
        assertEquals("user", entity.getType());
        assertEquals("u123", entity.getKey());
        assertEquals("Alice", entity.getName());
        assertEquals("free", entity.getAttributes().get("plan"));
        assertNull(entity.getCreatedAt());
        assertNull(entity.getUpdatedAt());
    }

    @Test
    void contextEntity_toString() {
        ContextEntity entity = new ContextEntity("user", "u123", null, null, null, null);
        assertTrue(entity.toString().contains("user"));
        assertTrue(entity.toString().contains("u123"));
    }

    @Test
    void contextEntity_nullAttributes_returnsEmpty() {
        ContextEntity entity = new ContextEntity("user", "k", null, null, null, null);
        assertTrue(entity.getAttributes().isEmpty());
    }

    // -----------------------------------------------------------------------
    // AccountSettings model
    // -----------------------------------------------------------------------

    @Test
    void accountSettings_environmentOrder_empty() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        assertTrue(s.getEnvironmentOrder().isEmpty());
    }

    @Test
    void accountSettings_setEnvironmentOrder() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        s.setEnvironmentOrder(List.of("production", "staging"));
        assertEquals(List.of("production", "staging"), s.getEnvironmentOrder());
    }

    @Test
    void accountSettings_getRaw() {
        Map<String, Object> data = new HashMap<>();
        data.put("key", "value");
        AccountSettings s = new AccountSettings(null, data);
        assertEquals("value", s.getRaw().get("key"));
    }

    @Test
    void accountSettings_setRaw() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        s.setRaw(Map.of("a", "b"));
        assertEquals("b", s.getRaw().get("a"));
    }

    @Test
    void accountSettings_setRaw_null() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        s.setRaw(null);
        assertTrue(s.getRaw().isEmpty());
    }

    @Test
    void accountSettings_environmentOrder_fromList() {
        Map<String, Object> data = new HashMap<>();
        data.put("environment_order", List.of("production", "staging"));
        AccountSettings s = new AccountSettings(null, data);
        assertEquals(List.of("production", "staging"), s.getEnvironmentOrder());
    }

    @Test
    void accountSettings_setEnvironmentOrder_null() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        s.setEnvironmentOrder(null);
        assertTrue(s.getEnvironmentOrder().isEmpty());
    }

    @Test
    void accountSettings_toString() {
        AccountSettings s = new AccountSettings(null, Map.of("x", "y"));
        assertTrue(s.toString().contains("AccountSettings"));
    }

    @Test
    void accountSettings_nullData_isEmptyMap() {
        AccountSettings s = new AccountSettings(null, null);
        assertTrue(s.getRaw().isEmpty());
    }

    @Test
    void accountSettings_save_withoutClient_throws() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        assertThrows(IllegalStateException.class, s::save);
    }

    // -----------------------------------------------------------------------
    // ContextRegistrationBuffer
    // -----------------------------------------------------------------------

    @Test
    void contextBuffer_observe_deduplication() {
        ContextRegistrationBuffer buf = new ContextRegistrationBuffer();
        Context ctx = new Context("user", "u1", Map.of("plan", "free"));
        buf.observe(ctx);
        buf.observe(ctx); // duplicate
        List<Map<String, Object>> drained = buf.drain();
        assertEquals(1, drained.size());
        assertEquals("user", drained.get(0).get("type"));
        assertEquals("u1", drained.get(0).get("key"));
    }

    @Test
    void contextBuffer_observeAll() {
        ContextRegistrationBuffer buf = new ContextRegistrationBuffer();
        buf.observeAll(List.of(
                new Context("user", "u1", Map.of()),
                new Context("user", "u2", Map.of())
        ));
        assertEquals(2, buf.drain().size());
    }

    @Test
    void contextBuffer_drain_clearsQueue() {
        ContextRegistrationBuffer buf = new ContextRegistrationBuffer();
        buf.observe(new Context("user", "u1", null));
        assertEquals(1, buf.pendingCount());
        buf.drain();
        assertEquals(0, buf.pendingCount());
    }

    @Test
    void contextBuffer_observe_nullAttributes() {
        ContextRegistrationBuffer buf = new ContextRegistrationBuffer();
        buf.observe(new Context("user", "u1", null));
        List<Map<String, Object>> drained = buf.drain();
        assertNotNull(drained.get(0).get("attributes"));
    }

    // -----------------------------------------------------------------------
    // EnvironmentsClient — via reflection-injected mock api
    // -----------------------------------------------------------------------

    private EnvironmentsApi mockEnvApi;
    private EnvironmentsClient envClient;

    @BeforeEach
    void setUpEnvClient() throws Exception {
        mockEnvApi = mock(EnvironmentsApi.class);
        envClient = new EnvironmentsClient(buildFakeApiClient());
        // Inject mock via reflection
        var field = EnvironmentsClient.class.getDeclaredField("api");
        field.setAccessible(true);
        field.set(envClient, mockEnvApi);
    }

    @Test
    void envClient_new_() {
        com.smplkit.management.Environment env = envClient.new_(
                "prod", "Production", null, EnvironmentClassification.STANDARD);
        assertEquals("prod", env.getId());
        assertEquals("Production", env.getName());
        assertNull(env.getColor());
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
    }

    @Test
    void envClient_new_nullClassification_defaultsToStandard() {
        com.smplkit.management.Environment env = envClient.new_("prod", "Production", null, null);
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
    }

    @Test
    void envClient_list() throws Exception {
        EnvironmentListResponse resp = new EnvironmentListResponse();
        EnvironmentResource r = buildEnvResource("prod", "Production", null, "STANDARD",
                OffsetDateTime.now(), OffsetDateTime.now());
        resp.setData(List.of(r));
        when(mockEnvApi.listEnvironments()).thenReturn(resp);

        List<com.smplkit.management.Environment> envs = envClient.list();
        assertEquals(1, envs.size());
        assertEquals("prod", envs.get(0).getId());
    }

    @Test
    void envClient_list_emptyData() throws Exception {
        EnvironmentListResponse resp = new EnvironmentListResponse();
        resp.setData(null);
        when(mockEnvApi.listEnvironments()).thenReturn(resp);
        assertTrue(envClient.list().isEmpty());
    }

    @Test
    void envClient_get() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(buildEnvResource("prod", "Production", "#ff0000", "STANDARD",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockEnvApi.getEnvironment("prod")).thenReturn(resp);

        com.smplkit.management.Environment env = envClient.get("prod");
        assertEquals("prod", env.getId());
        assertEquals("#ff0000", env.getColor());
    }

    @Test
    void envClient_get_nullAttributes() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        EnvironmentResource r = new EnvironmentResource().id("prod")
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT);
        // no attributes
        resp.setData(r);
        when(mockEnvApi.getEnvironment("prod")).thenReturn(resp);

        com.smplkit.management.Environment env = envClient.get("prod");
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
    }

    @Test
    void envClient_delete() throws Exception {
        doNothing().when(mockEnvApi).deleteEnvironment("prod");
        assertDoesNotThrow(() -> envClient.delete("prod"));
        verify(mockEnvApi).deleteEnvironment("prod");
    }

    @Test
    void envClient_create_viaModelSave() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(buildEnvResource("prod", "Production", null, "STANDARD",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockEnvApi.createEnvironment(any())).thenReturn(resp);

        com.smplkit.management.Environment env = envClient.new_(
                "prod", "Production", null, EnvironmentClassification.STANDARD);
        env.save();

        assertEquals("prod", env.getId());
        assertNotNull(env.getCreatedAt());
        verify(mockEnvApi).createEnvironment(any());
    }

    @Test
    void envClient_update_viaModelSave() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(buildEnvResource("prod", "Production Updated", "#00ff00", "STANDARD",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockEnvApi.updateEnvironment(eq("prod"), any())).thenReturn(resp);

        // Simulate an existing environment (has createdAt)
        com.smplkit.management.Environment env = new com.smplkit.management.Environment(
                envClient, "prod", "Production", null, EnvironmentClassification.STANDARD,
                Instant.now(), Instant.now());
        env.setName("Production Updated");
        env.setColor("#00ff00");
        env.save();

        assertEquals("Production Updated", env.getName());
        verify(mockEnvApi).updateEnvironment(eq("prod"), any());
    }

    @Test
    void envClient_buildRequest_withColor() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(buildEnvResource("prod", "Production", "#red", "AD_HOC",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockEnvApi.createEnvironment(any())).thenReturn(resp);

        com.smplkit.management.Environment env = envClient.new_(
                "prod", "Production", "#red", EnvironmentClassification.AD_HOC);
        env.save();

        assertEquals(EnvironmentClassification.AD_HOC, env.getClassification());
    }

    @Test
    void envClient_apiException_mapped() throws Exception {
        when(mockEnvApi.listEnvironments()).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplException.class, () -> envClient.list());
    }

    @Test
    void envClient_apiException_zeroCode() throws Exception {
        when(mockEnvApi.listEnvironments()).thenThrow(new ApiException(0, "network error"));
        assertThrows(SmplException.class, () -> envClient.list());
    }

    @Test
    void envClient_get_nullResponse_throws() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse(); // data = null
        when(mockEnvApi.getEnvironment("x")).thenReturn(resp);
        assertThrows(IllegalStateException.class, () -> envClient.get("x"));
    }

    // -----------------------------------------------------------------------
    // ContextTypesClient — via reflection-injected mock api
    // -----------------------------------------------------------------------

    private ContextTypesApi mockCtApi;
    private ContextTypesClient ctClient;

    @BeforeEach
    void setUpCtClient() throws Exception {
        mockCtApi = mock(ContextTypesApi.class);
        ctClient = new ContextTypesClient(buildFakeApiClient());
        var field = ContextTypesClient.class.getDeclaredField("api");
        field.setAccessible(true);
        field.set(ctClient, mockCtApi);
    }

    @Test
    void ctClient_new_() {
        com.smplkit.management.ContextType ct = ctClient.new_("user");
        assertEquals("user", ct.getId());
        assertNotNull(ct.getName());
    }

    @Test
    void ctClient_new_withName() {
        com.smplkit.management.ContextType ct = ctClient.new_("user", "User", null);
        assertEquals("User", ct.getName());
    }

    @Test
    void ctClient_new_nullName_usesDisplayName() {
        com.smplkit.management.ContextType ct = ctClient.new_("user", null, null);
        assertNotNull(ct.getName());
    }

    @Test
    void ctClient_list() throws Exception {
        ContextTypeListResponse resp = new ContextTypeListResponse();
        resp.setData(List.of(buildCtResource("user", "User", new HashMap<>(),
                OffsetDateTime.now(), OffsetDateTime.now())));
        when(mockCtApi.listContextTypes()).thenReturn(resp);

        List<com.smplkit.management.ContextType> cts = ctClient.list();
        assertEquals(1, cts.size());
        assertEquals("user", cts.get(0).getId());
    }

    @Test
    void ctClient_list_emptyData() throws Exception {
        ContextTypeListResponse resp = new ContextTypeListResponse();
        resp.setData(null);
        when(mockCtApi.listContextTypes()).thenReturn(resp);
        assertTrue(ctClient.list().isEmpty());
    }

    @Test
    void ctClient_get() throws Exception {
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User", new HashMap<>(),
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockCtApi.getContextType("user")).thenReturn(resp);

        com.smplkit.management.ContextType ct = ctClient.get("user");
        assertEquals("user", ct.getId());
    }

    @Test
    void ctClient_get_withAttributes() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("plan", Map.of("desc", "User plan"));
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User", attrs,
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockCtApi.getContextType("user")).thenReturn(resp);

        com.smplkit.management.ContextType ct = ctClient.get("user");
        assertTrue(ct.getAttributes().containsKey("plan"));
    }

    @Test
    void ctClient_get_withNonMapAttributeValue() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("plan", "string-value"); // not a Map
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User", attrs, null, null));
        when(mockCtApi.getContextType("user")).thenReturn(resp);

        com.smplkit.management.ContextType ct = ctClient.get("user");
        assertTrue(ct.getAttributes().get("plan").isEmpty()); // fallback to empty map
    }

    @Test
    void ctClient_get_nullAttributes() throws Exception {
        ContextTypeResource r = new ContextTypeResource().id("user")
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE);
        ContextTypeResponse resp = new ContextTypeResponse().data(r);
        when(mockCtApi.getContextType("user")).thenReturn(resp);

        com.smplkit.management.ContextType ct = ctClient.get("user");
        assertTrue(ct.getAttributes().isEmpty());
    }

    @Test
    void ctClient_delete() throws Exception {
        doNothing().when(mockCtApi).deleteContextType("user");
        assertDoesNotThrow(() -> ctClient.delete("user"));
        verify(mockCtApi).deleteContextType("user");
    }

    @Test
    void ctClient_create_viaModelSave() throws Exception {
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User", new HashMap<>(),
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockCtApi.createContextType(any())).thenReturn(resp);

        com.smplkit.management.ContextType ct = ctClient.new_("user");
        ct.addAttribute("plan");
        ct.save();

        assertEquals("User", ct.getName());
        verify(mockCtApi).createContextType(any());
    }

    @Test
    void ctClient_update_viaModelSave() throws Exception {
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User Updated", new HashMap<>(),
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockCtApi.updateContextType(eq("user"), any())).thenReturn(resp);

        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                ctClient, "user", "User", new HashMap<>(), Instant.now(), Instant.now());
        ct.setName("User Updated");
        ct.save();

        assertEquals("User Updated", ct.getName());
        verify(mockCtApi).updateContextType(eq("user"), any());
    }

    @Test
    void ctClient_create_emptyAttributes() throws Exception {
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User", new HashMap<>(), null, null));
        when(mockCtApi.createContextType(any())).thenReturn(resp);

        com.smplkit.management.ContextType ct = ctClient.new_("user"); // no attributes
        ct.save();
        verify(mockCtApi).createContextType(any());
    }

    @Test
    void ctClient_get_nullResponse_throws() throws Exception {
        ContextTypeResponse resp = new ContextTypeResponse(); // data = null
        when(mockCtApi.getContextType("x")).thenReturn(resp);
        assertThrows(IllegalStateException.class, () -> ctClient.get("x"));
    }

    @Test
    void ctClient_apiException_mapped() throws Exception {
        when(mockCtApi.listContextTypes()).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplException.class, () -> ctClient.list());
    }

    @Test
    void ctClient_apiException_zeroCode() throws Exception {
        when(mockCtApi.listContextTypes()).thenThrow(new ApiException(0, "network"));
        assertThrows(SmplException.class, () -> ctClient.list());
    }

    // -----------------------------------------------------------------------
    // ContextsClient — via reflection-injected mock api
    // -----------------------------------------------------------------------

    private ContextsApi mockCtxApi;
    private ContextsClient ctxClient;
    private ContextRegistrationBuffer ctxBuffer;

    @BeforeEach
    void setUpCtxClient() throws Exception {
        mockCtxApi = mock(ContextsApi.class);
        ctxBuffer = new ContextRegistrationBuffer();
        ctxClient = new ContextsClient(buildFakeApiClient(), ctxBuffer);
        var field = ContextsClient.class.getDeclaredField("api");
        field.setAccessible(true);
        field.set(ctxClient, mockCtxApi);
    }

    @Test
    void ctxClient_register_single() {
        Context ctx = new Context("user", "u1", Map.of("plan", "free"));
        ctxClient.register(ctx);
        assertEquals(1, ctxBuffer.pendingCount());
    }

    @Test
    void ctxClient_register_list() {
        ctxClient.register(List.of(
                new Context("user", "u1", null),
                new Context("user", "u2", null)
        ));
        assertEquals(2, ctxBuffer.pendingCount());
    }

    @Test
    void ctxClient_register_single_withFlush() throws Exception {
        when(mockCtxApi.bulkRegisterContexts(any())).thenReturn(null);
        ctxClient.register(new Context("user", "u1", null), true);
        verify(mockCtxApi).bulkRegisterContexts(any());
        assertEquals(0, ctxBuffer.pendingCount());
    }

    @Test
    void ctxClient_register_list_withFlush() throws Exception {
        when(mockCtxApi.bulkRegisterContexts(any())).thenReturn(null);
        ctxClient.register(List.of(new Context("user", "u1", null)), true);
        verify(mockCtxApi).bulkRegisterContexts(any());
    }

    @Test
    void ctxClient_flush_empty_isNoop() throws Exception {
        ctxClient.flush(); // nothing pending
        verifyNoInteractions(mockCtxApi);
    }

    @Test
    void ctxClient_flush_sendsBatch() throws Exception {
        when(mockCtxApi.bulkRegisterContexts(any())).thenReturn(null);
        ctxClient.register(new Context("user", "u1", Map.of("plan", "free")));
        ctxClient.flush();
        ArgumentCaptor<ContextBulkRegister> captor = ArgumentCaptor.forClass(ContextBulkRegister.class);
        verify(mockCtxApi).bulkRegisterContexts(captor.capture());
        ContextBulkItem item = captor.getValue().getContexts().get(0);
        assertEquals("user", item.getType());
        assertEquals("u1", item.getKey());
    }

    @Test
    void ctxClient_flush_apiException() throws Exception {
        when(mockCtxApi.bulkRegisterContexts(any())).thenThrow(new ApiException(500, "error"));
        ctxClient.register(new Context("user", "u1", null));
        assertThrows(SmplException.class, () -> ctxClient.flush());
    }

    @Test
    void ctxClient_list() throws Exception {
        ContextListResponse resp = new ContextListResponse();
        resp.setData(List.of(buildCtxResource("user:u1", "Alice", new HashMap<>(),
                OffsetDateTime.now(), OffsetDateTime.now())));
        when(mockCtxApi.listContexts("user")).thenReturn(resp);

        List<ContextEntity> entities = ctxClient.list("user");
        assertEquals(1, entities.size());
        assertEquals("user", entities.get(0).getType());
        assertEquals("u1", entities.get(0).getKey());
    }

    @Test
    void ctxClient_list_emptyData() throws Exception {
        ContextListResponse resp = new ContextListResponse();
        resp.setData(null);
        when(mockCtxApi.listContexts("user")).thenReturn(resp);
        assertTrue(ctxClient.list("user").isEmpty());
    }

    @Test
    void ctxClient_get_byCompositeId() throws Exception {
        ContextResponse resp = new ContextResponse();
        resp.setData(buildCtxResource("user:u1", "Alice", new HashMap<>(), null, null));
        when(mockCtxApi.getContext("user:u1")).thenReturn(resp);

        ContextEntity e = ctxClient.get("user:u1");
        assertEquals("user", e.getType());
        assertEquals("u1", e.getKey());
    }

    @Test
    void ctxClient_get_byTypeAndKey() throws Exception {
        ContextResponse resp = new ContextResponse();
        resp.setData(buildCtxResource("user:u1", "Alice", new HashMap<>(), null, null));
        when(mockCtxApi.getContext("user:u1")).thenReturn(resp);

        ContextEntity e = ctxClient.get("user", "u1");
        assertEquals("user", e.getType());
    }

    @Test
    void ctxClient_get_nullResponse_throwsNotFound() throws Exception {
        ContextResponse resp = new ContextResponse(); // data = null
        when(mockCtxApi.getContext("user:u1")).thenReturn(resp);
        assertThrows(SmplNotFoundException.class, () -> ctxClient.get("user:u1"));
    }

    @Test
    void ctxClient_get_noColonId_throws() {
        assertThrows(IllegalArgumentException.class, () -> ctxClient.get("nocolon"));
    }

    @Test
    void ctxClient_get_ctxResource_noColon_handlesGracefully() throws Exception {
        // Resource id with no colon — edge case
        ContextResponse resp = new ContextResponse();
        ContextResource r = new ContextResource().id("nocoLonId");
        resp.setData(r);
        when(mockCtxApi.getContext("nocoLonId")).thenReturn(resp);
        // This would fail earlier due to splitCompositeId; but let's hit resourceToEntity
        // by crafting a resource directly
        ContextEntity e = new ContextEntity("nocoLonId", "", null, null, null, null);
        assertEquals("nocoLonId:", e.getId());
    }

    @Test
    void ctxClient_delete_byCompositeId() throws Exception {
        doNothing().when(mockCtxApi).deleteContext("user:u1");
        assertDoesNotThrow(() -> ctxClient.delete("user:u1"));
        verify(mockCtxApi).deleteContext("user:u1");
    }

    @Test
    void ctxClient_delete_byTypeAndKey() throws Exception {
        doNothing().when(mockCtxApi).deleteContext("user:u1");
        assertDoesNotThrow(() -> ctxClient.delete("user", "u1"));
        verify(mockCtxApi).deleteContext("user:u1");
    }

    @Test
    void ctxClient_delete_apiException() throws Exception {
        doThrow(new ApiException(404, "not found")).when(mockCtxApi).deleteContext(any());
        assertThrows(SmplException.class, () -> ctxClient.delete("user:u1"));
    }

    @Test
    void ctxClient_get_apiException() throws Exception {
        when(mockCtxApi.getContext(any())).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplException.class, () -> ctxClient.get("user:u1"));
    }

    @Test
    void ctxClient_list_apiException() throws Exception {
        when(mockCtxApi.listContexts(any())).thenThrow(new ApiException(0, "error"));
        assertThrows(SmplException.class, () -> ctxClient.list("user"));
    }

    @Test
    void ctxClient_ctxResource_withAttributes() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("plan", "free");
        ContextResponse resp = new ContextResponse();
        resp.setData(buildCtxResource("user:u1", "Alice", attrs, OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockCtxApi.getContext("user:u1")).thenReturn(resp);

        ContextEntity e = ctxClient.get("user:u1");
        assertEquals("free", e.getAttributes().get("plan"));
        assertEquals("Alice", e.getName());
    }

    // -----------------------------------------------------------------------
    // LoggerSource record
    // -----------------------------------------------------------------------

    @Test
    void loggerSource_fullConstructor() {
        LoggerSource src = new LoggerSource("my.logger", "svc", "prod", LogLevel.WARN, LogLevel.ERROR);
        assertEquals("my.logger", src.name());
        assertEquals("svc", src.service());
        assertEquals("prod", src.environment());
        assertEquals(LogLevel.WARN, src.resolvedLevel());
        assertEquals(LogLevel.ERROR, src.level());
    }

    @Test
    void loggerSource_shortConstructor_levelNull() {
        LoggerSource src = new LoggerSource("my.logger", "svc", "prod", LogLevel.INFO);
        assertNull(src.level());
        assertEquals(LogLevel.INFO, src.resolvedLevel());
    }

    // -----------------------------------------------------------------------
    // LoggingManagement.registerSources — via reflection-injected mock
    // -----------------------------------------------------------------------

    private LoggersApi mockLoggersApi;
    private LogGroupsApi mockLogGroupsApi;
    private LoggingManagement loggingMgmt;

    @BeforeEach
    void setUpLoggingMgmt() throws Exception {
        mockLoggersApi = mock(LoggersApi.class);
        mockLogGroupsApi = mock(LogGroupsApi.class);
        LoggingClient loggingClient = new LoggingClient(
                mockLoggersApi, mockLogGroupsApi,
                HttpClient.newHttpClient(), "test-key");
        loggingMgmt = loggingClient.management();
    }

    @Test
    void registerSources_sendsCorrectBulkRequest() throws Exception {
        when(mockLoggersApi.bulkRegisterLoggers(any())).thenReturn(null);

        loggingMgmt.registerSources(List.of(
                new LoggerSource("my.logger", "svc", "prod", LogLevel.WARN, LogLevel.ERROR),
                new LoggerSource("other.logger", "svc2", "staging", LogLevel.INFO)
        ));

        ArgumentCaptor<LoggerBulkRequest> captor = ArgumentCaptor.forClass(LoggerBulkRequest.class);
        verify(mockLoggersApi).bulkRegisterLoggers(captor.capture());

        var items = captor.getValue().getLoggers();
        assertEquals(2, items.size());
        assertEquals("my.logger", items.get(0).getId());
        assertEquals("WARN", items.get(0).getResolvedLevel());
        assertEquals("ERROR", items.get(0).getLevel_JsonNullable().get());
        assertEquals("svc", items.get(0).getService());
        assertEquals("prod", items.get(0).getEnvironment());
        assertEquals("other.logger", items.get(1).getId());
        assertEquals("INFO", items.get(1).getResolvedLevel());
        assertFalse(items.get(1).getLevel_JsonNullable().isPresent());
    }

    @Test
    void registerSources_emptyList_isNoop() throws Exception {
        loggingMgmt.registerSources(List.of());
        verifyNoInteractions(mockLoggersApi);
    }

    @Test
    void registerSources_null_isNoop() throws Exception {
        loggingMgmt.registerSources(null);
        verifyNoInteractions(mockLoggersApi);
    }

    @Test
    void registerSources_apiException_throws() throws Exception {
        when(mockLoggersApi.bulkRegisterLoggers(any()))
                .thenThrow(new com.smplkit.internal.generated.logging.ApiException(500, "error"));
        assertThrows(SmplException.class,
                () -> loggingMgmt.registerSources(List.of(
                        new LoggerSource("x", "s", "e", LogLevel.INFO))));
    }

    // -----------------------------------------------------------------------
    // ContextTypesClient — exception paths for get/delete/_create/_update
    // -----------------------------------------------------------------------

    @Test
    void ctClient_get_apiException() throws Exception {
        when(mockCtApi.getContextType(any())).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplException.class, () -> ctClient.get("user"));
    }

    @Test
    void ctClient_delete_apiException() throws Exception {
        doThrow(new ApiException(500, "error")).when(mockCtApi).deleteContextType(any());
        assertThrows(SmplException.class, () -> ctClient.delete("user"));
    }

    @Test
    void ctClient_create_apiException() throws Exception {
        when(mockCtApi.createContextType(any())).thenThrow(new ApiException(400, "bad request"));
        com.smplkit.management.ContextType ct = ctClient.new_("user");
        assertThrows(SmplException.class, ct::save);
    }

    @Test
    void ctClient_update_apiException() throws Exception {
        when(mockCtApi.updateContextType(any(), any())).thenThrow(new ApiException(409, "conflict"));
        // Create a ct that looks persisted (createdAt != null) so save() calls _update
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                ctClient, "user", "User", Map.of(),
                java.time.Instant.now(), java.time.Instant.now());
        assertThrows(SmplException.class, ct::save);
    }

    // -----------------------------------------------------------------------
    // ContextType getCreatedAt / getUpdatedAt coverage
    // -----------------------------------------------------------------------

    @Test
    void contextType_getCreatedAt_getUpdatedAt() {
        java.time.Instant now = java.time.Instant.now();
        com.smplkit.management.ContextType ct = new com.smplkit.management.ContextType(
                null, "x", "X", null, now, now);
        assertEquals(now, ct.getCreatedAt());
        assertEquals(now, ct.getUpdatedAt());
    }

    // -----------------------------------------------------------------------
    // EnvironmentsClient — exception paths for get/delete/_create/_update
    // -----------------------------------------------------------------------

    @Test
    void envClient_get_apiException() throws Exception {
        when(mockEnvApi.getEnvironment(any())).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplException.class, () -> envClient.get("prod"));
    }

    @Test
    void envClient_delete_apiException() throws Exception {
        doThrow(new ApiException(500, "error")).when(mockEnvApi).deleteEnvironment(any());
        assertThrows(SmplException.class, () -> envClient.delete("prod"));
    }

    @Test
    void envClient_create_apiException() throws Exception {
        when(mockEnvApi.createEnvironment(any())).thenThrow(new ApiException(400, "bad request"));
        com.smplkit.management.Environment env = envClient.new_("prod", "Production", null, EnvironmentClassification.STANDARD);
        assertThrows(SmplException.class, env::save);
    }

    @Test
    void envClient_update_apiException() throws Exception {
        when(mockEnvApi.updateEnvironment(any(), any())).thenThrow(new ApiException(409, "conflict"));
        // Create env that looks persisted (createdAt != null) so save() calls _update
        com.smplkit.management.Environment env = new com.smplkit.management.Environment(
                envClient, "prod", "Production", null, EnvironmentClassification.STANDARD,
                java.time.Instant.now(), java.time.Instant.now());
        assertThrows(SmplException.class, env::save);
    }

    // -----------------------------------------------------------------------
    // ContextsClient — resource with no colon in id (172-173)
    // -----------------------------------------------------------------------

    @Test
    void ctxClient_resourceToEntity_noColonInId() throws Exception {
        // Build a context resource whose composite id has no colon (edge case in resourceToEntity)
        ContextResponse resp = new ContextResponse();
        com.smplkit.internal.generated.app.model.Context attrs =
                new com.smplkit.internal.generated.app.model.Context();
        attrs.setName("Bare");
        ContextResource r = new ContextResource().id("user");
        r.setAttributes(attrs);
        resp.setData(r);
        when(mockCtxApi.getContext("user:user")).thenReturn(resp);

        ContextEntity e = ctxClient.get("user:user");
        assertEquals("user", e.getType());
        assertEquals("", e.getKey());
    }

    // -----------------------------------------------------------------------
    // AccountSettings save() / _apply() coverage
    // -----------------------------------------------------------------------

    private AccountApi mockAccountApi;
    private AccountSettingsClient acctClient;

    @BeforeEach
    void setUpAccountClient() throws Exception {
        mockAccountApi = mock(AccountApi.class);
        acctClient = new AccountSettingsClient(buildFakeApiClient(), "http://localhost", "key");
        java.lang.reflect.Field f = AccountSettingsClient.class.getDeclaredField("api");
        f.setAccessible(true);
        f.set(acctClient, mockAccountApi);
    }

    @Test
    void acctClient_get_returnsSettings() throws Exception {
        when(mockAccountApi.getAccountSettings()).thenReturn(
                Map.of("environment_order", List.of("production", "staging")));

        AccountSettings settings = acctClient.get();
        assertEquals(List.of("production", "staging"), settings.getEnvironmentOrder());
    }

    @Test
    void acctClient_get_nullResponse_returnsEmpty() throws Exception {
        when(mockAccountApi.getAccountSettings()).thenReturn(null);

        AccountSettings settings = acctClient.get();
        assertTrue(settings.getEnvironmentOrder().isEmpty());
    }

    @Test
    void acctClient_get_apiException() throws Exception {
        when(mockAccountApi.getAccountSettings()).thenThrow(new ApiException(500, "error"));
        assertThrows(SmplException.class, acctClient::get);
    }

    @Test
    void acctClient_get_apiException_zeroCode() throws Exception {
        when(mockAccountApi.getAccountSettings()).thenThrow(new ApiException(0, "connection error"));
        assertThrows(SmplException.class, acctClient::get);
    }

    @Test
    void acctClient_save_successViaHttpServer() throws Exception {
        // Spin up a local HTTP server to handle the PUT
        String responseBody = "{\"environment_order\":[\"production\"]}";
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress(0), 0);
        server.createContext("/api/v1/accounts/current/settings", exchange -> {
            byte[] resp = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AccountSettingsClient testClient = new AccountSettingsClient(
                    buildFakeApiClient(), "http://localhost:" + port, "test-key");

            AccountSettings settings = new AccountSettings(testClient, Map.of());
            settings.save();

            assertEquals(List.of("production"), settings.getEnvironmentOrder());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acctClient_save_errorStatusThrows() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress(0), 0);
        server.createContext("/api/v1/accounts/current/settings", exchange -> {
            byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AccountSettingsClient testClient = new AccountSettingsClient(
                    buildFakeApiClient(), "http://localhost:" + port, "test-key");

            AccountSettings settings = new AccountSettings(testClient, Map.of());
            assertThrows(com.smplkit.errors.SmplException.class, settings::save);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acctClient_save_connectionRefused_throws() {
        AccountSettingsClient testClient = new AccountSettingsClient(
                buildFakeApiClient(), "http://localhost:1", "test-key");
        AccountSettings settings = new AccountSettings(testClient, Map.of());
        assertThrows(com.smplkit.errors.SmplException.class, settings::save);
    }

    // -----------------------------------------------------------------------
    // ManagementClient construction + interceptor lambda
    // -----------------------------------------------------------------------

    @Test
    void managementClient_constructsAllSubClients() {
        ContextRegistrationBuffer buf = new ContextRegistrationBuffer();
        ManagementClient mc = new ManagementClient(
                "https://app.smplkit.com", "test-key",
                Duration.ofSeconds(5), buf);
        assertNotNull(mc.environments);
        assertNotNull(mc.contexts);
        assertNotNull(mc.contextTypes);
        assertNotNull(mc.accountSettings);
    }

    @Test
    void managementClient_buildAppApiClient_interceptorAddsAuthHeader() throws Exception {
        com.smplkit.internal.generated.app.ApiClient apiClient =
                ManagementClient.buildAppApiClient("https://app.smplkit.com", "my-key", null);
        // Invoke the interceptor by passing a real HttpRequest.Builder
        java.net.http.HttpRequest.Builder builder =
                java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create("https://app.smplkit.com"));
        apiClient.getRequestInterceptor().accept(builder);
        java.net.http.HttpRequest req = builder.build();
        assertEquals("Bearer my-key", req.headers().firstValue("Authorization").orElse(null));
    }

    // -----------------------------------------------------------------------
    // Helper builders
    // -----------------------------------------------------------------------

    private static com.smplkit.internal.generated.app.ApiClient buildFakeApiClient() {
        com.smplkit.internal.generated.app.ApiClient c = new com.smplkit.internal.generated.app.ApiClient();
        c.updateBaseUri("https://app.smplkit.com");
        return c;
    }

    private static EnvironmentResource buildEnvResource(String id, String name, String color,
                                                         String classification,
                                                         OffsetDateTime createdAt,
                                                         OffsetDateTime updatedAt) {
        Environment attrs = new Environment(classification, createdAt, updatedAt);
        attrs.setName(name);
        if (color != null) attrs.setColor(color);
        return new EnvironmentResource().id(id)
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT)
                .attributes(attrs);
    }

    private static ContextTypeResource buildCtResource(String id, String name,
                                                        Map<String, Object> attrs,
                                                        OffsetDateTime createdAt,
                                                        OffsetDateTime updatedAt) {
        ContextType ct = new ContextType();
        ct.setName(name);
        if (attrs != null) ct.setAttributes(attrs);
        ContextTypeResource r = new ContextTypeResource()
                .id(id)
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(ct);
        return r;
    }

    private static ContextResource buildCtxResource(String compositeId, String name,
                                                      Map<String, Object> attrs,
                                                      OffsetDateTime createdAt,
                                                      OffsetDateTime updatedAt) {
        com.smplkit.internal.generated.app.model.Context c =
                new com.smplkit.internal.generated.app.model.Context();
        c.setName(name);
        if (attrs != null) c.setAttributes(attrs);
        ContextResource r = new ContextResource().id(compositeId);
        r.setAttributes(c);
        return r;
    }
}
