package com.smplkit.platform;

import com.smplkit.Color;
import com.smplkit.Context;
import com.smplkit.errors.NotFoundError;
import com.smplkit.errors.SmplError;
import com.smplkit.internal.ContextRegistrationBuffer;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.ContextTypesApi;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.api.EnvironmentsApi;
import com.smplkit.internal.generated.app.api.ServicesApi;
import com.smplkit.internal.generated.app.model.ContextBulkItem;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.app.model.ContextListResponse;
import com.smplkit.internal.generated.app.model.ContextResource;
import com.smplkit.internal.generated.app.model.ContextResponse;
import com.smplkit.internal.generated.app.model.ContextTypeListResponse;
import com.smplkit.internal.generated.app.model.ContextTypeResource;
import com.smplkit.internal.generated.app.model.ContextTypeResponse;
import com.smplkit.internal.generated.app.model.EnvironmentCreateRequest;
import com.smplkit.internal.generated.app.model.EnvironmentCreateResource;
import com.smplkit.internal.generated.app.model.EnvironmentListResponse;
import com.smplkit.internal.generated.app.model.EnvironmentResource;
import com.smplkit.internal.generated.app.model.EnvironmentResponse;
import com.smplkit.internal.generated.app.model.ServiceListResponse;
import com.smplkit.internal.generated.app.model.ServiceResource;
import com.smplkit.internal.generated.app.model.ServiceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the {@code com.smplkit.platform} package: models, sync clients,
 * async wrappers, the wired/standalone {@link PlatformClient}, and pagination.
 *
 * <p>No real network. Sub-client {@code api} fields are mocked via reflection;
 * the {@link PlatformClient} itself is built from a fake {@link ApiClient}
 * pointed at a non-dialed base URI (construction makes no HTTP calls).</p>
 */
class PlatformTest {

    // =======================================================================
    // EnvironmentClassification enum
    // =======================================================================

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

    // =======================================================================
    // Environment model
    // =======================================================================

    @Test
    void environment_getters() {
        Instant now = Instant.now();
        Environment env = new Environment(
                null, "prod", "Production", "#ff0000", EnvironmentClassification.STANDARD, true, now, now);
        assertEquals("prod", env.getId());
        assertEquals("Production", env.getName());
        assertEquals("#ff0000", env.getColor());
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
        assertTrue(env.isManaged());
        assertEquals(now, env.getCreatedAt());
        assertEquals(now, env.getUpdatedAt());
    }

    @Test
    void environment_setters() {
        Environment env = new Environment(
                null, "prod", "Production", null, null, true, null, null);
        env.setName("Prod 2");
        env.setColor("#00ff00");
        env.setClassification(EnvironmentClassification.AD_HOC);
        env.setManaged(false);
        assertEquals("Prod 2", env.getName());
        assertEquals("#00ff00", env.getColor());
        assertEquals(EnvironmentClassification.AD_HOC, env.getClassification());
        assertFalse(env.isManaged());
    }

    @Test
    void environment_classificationDefaultsToStandard() {
        Environment env = new Environment(
                null, "prod", "Production", null, null, true, null, null);
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
    }

    @Test
    void environment_typedColorAccessor_returnsColorOrNull() {
        Environment env = new Environment(
                null, "e", "E", "#ff0000", EnvironmentClassification.STANDARD, true, null, null);
        assertNotNull(env.color());
        assertEquals("#ff0000", env.color().hex());

        Environment envNoColor = new Environment(
                null, "n", "N", null, EnvironmentClassification.STANDARD, true, null, null);
        assertNull(envNoColor.color());
    }

    @Test
    void environment_setColor_typedColor_writesHex() {
        Environment env = new Environment(
                null, "e", "E", "#000000", EnvironmentClassification.STANDARD, true, null, null);
        env.setColor(new Color("#abcdef"));
        assertEquals("#abcdef", env.getColor());
        env.setColor((Color) null);
        assertNull(env.getColor());
    }

    @Test
    void environment_setColor_invalidHex_throws() {
        Environment env = new Environment(
                null, "e", "E", null, EnvironmentClassification.STANDARD, true, null, null);
        assertThrows(IllegalArgumentException.class, () -> env.setColor("not-a-color"));
    }

    @Test
    void environment_toString() {
        Environment env = new Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, true, null, null);
        assertTrue(env.toString().contains("prod"));
        assertTrue(env.toString().contains("managed=true"));
    }

    @Test
    void environment_save_withoutClient_throws() {
        Environment env = new Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, true, null, null);
        assertThrows(IllegalStateException.class, env::save);
    }

    @Test
    void environment_delete_withoutClient_throws() {
        Environment env = new Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, true, null, null);
        assertThrows(IllegalStateException.class, env::delete);
    }

    @Test
    void environment_saveAsync_default_returnsFuture() {
        Environment env = new Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, true, null, null);
        var future = env.saveAsync();
        assertNotNull(future);
    }

    @Test
    void environment_saveAsync_runsOnProvidedExecutor() {
        Environment env = new Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, true, null, null);
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor inline = r -> { ran.incrementAndGet(); r.run(); };
        env.saveAsync(inline).exceptionally(t -> null).join();
        assertEquals(1, ran.get());
    }

    @Test
    void environment_deleteAsync_default_returnsFuture() {
        Environment env = new Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, true, null, null);
        assertNotNull(env.deleteAsync());
    }

    @Test
    void environment_deleteAsync_runsOnProvidedExecutor() {
        Environment env = new Environment(
                null, "prod", "Production", null, EnvironmentClassification.STANDARD, true, null, null);
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor inline = r -> { ran.incrementAndGet(); r.run(); };
        env.deleteAsync(inline).exceptionally(t -> null).join();
        assertEquals(1, ran.get());
    }

    // =======================================================================
    // Service model
    // =======================================================================

    @Test
    void service_getters() {
        Instant now = Instant.now();
        Service svc = new Service(null, "user_service", "User Service", now, now);
        assertEquals("user_service", svc.getId());
        assertEquals("User Service", svc.getName());
        assertEquals(now, svc.getCreatedAt());
        assertEquals(now, svc.getUpdatedAt());
    }

    @Test
    void service_setName() {
        Service svc = new Service(null, "user_service", "User Service", null, null);
        svc.setName("Renamed");
        assertEquals("Renamed", svc.getName());
    }

    @Test
    void service_toString() {
        Service svc = new Service(null, "user_service", "User Service", null, null);
        assertTrue(svc.toString().contains("user_service"));
        assertTrue(svc.toString().contains("User Service"));
    }

    @Test
    void service_save_withoutClient_throws() {
        Service svc = new Service(null, "user_service", "User Service", null, null);
        assertThrows(IllegalStateException.class, svc::save);
    }

    @Test
    void service_delete_withoutClient_throws() {
        Service svc = new Service(null, "user_service", "User Service", null, null);
        assertThrows(IllegalStateException.class, svc::delete);
    }

    @Test
    void service_saveAsync_default_returnsFuture() {
        Service svc = new Service(null, "user_service", "User Service", null, null);
        assertNotNull(svc.saveAsync());
    }

    @Test
    void service_saveAsync_runsOnProvidedExecutor() {
        Service svc = new Service(null, "user_service", "User Service", null, null);
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor inline = r -> { ran.incrementAndGet(); r.run(); };
        svc.saveAsync(inline).exceptionally(t -> null).join();
        assertEquals(1, ran.get());
    }

    @Test
    void service_deleteAsync_default_returnsFuture() {
        Service svc = new Service(null, "user_service", "User Service", null, null);
        assertNotNull(svc.deleteAsync());
    }

    @Test
    void service_deleteAsync_runsOnProvidedExecutor() {
        Service svc = new Service(null, "user_service", "User Service", null, null);
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor inline = r -> { ran.incrementAndGet(); r.run(); };
        svc.deleteAsync(inline).exceptionally(t -> null).join();
        assertEquals(1, ran.get());
    }

    // =======================================================================
    // ContextType model
    // =======================================================================

    @Test
    void contextType_addAttribute_noMetadata() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        ct.addAttribute("plan");
        assertTrue(ct.getAttributes().containsKey("plan"));
        assertTrue(ct.getAttributes().get("plan").isEmpty());
    }

    @Test
    void contextType_addAttribute_withMap() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        ct.addAttribute("plan", Map.of("description", "User plan"));
        assertEquals("User plan", ct.getAttributes().get("plan").get("description"));
    }

    @Test
    void contextType_addAttribute_withVarargs() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        ct.addAttribute("plan", "description", "User plan", "required", true);
        assertEquals("User plan", ct.getAttributes().get("plan").get("description"));
        assertEquals(true, ct.getAttributes().get("plan").get("required"));
    }

    @Test
    void contextType_addAttribute_withNullMap() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        ct.addAttribute("plan", (Map<String, Object>) null);
        assertTrue(ct.getAttributes().get("plan").isEmpty());
    }

    @Test
    void contextType_removeAttribute() {
        Map<String, Map<String, Object>> attrs = new HashMap<>();
        attrs.put("plan", new HashMap<>());
        ContextType ct = new ContextType(null, "user", "User", attrs, null, null);
        ct.removeAttribute("plan");
        assertFalse(ct.getAttributes().containsKey("plan"));
    }

    @Test
    void contextType_removeAttribute_nonExistent_noThrow() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        assertDoesNotThrow(() -> ct.removeAttribute("nonexistent"));
    }

    @Test
    void contextType_updateAttribute() {
        Map<String, Map<String, Object>> attrs = new HashMap<>();
        attrs.put("plan", Map.of("desc", "old"));
        ContextType ct = new ContextType(null, "user", "User", attrs, null, null);
        ct.updateAttribute("plan", Map.of("desc", "new"));
        assertEquals("new", ct.getAttributes().get("plan").get("desc"));
    }

    @Test
    void contextType_updateAttribute_withNullMap() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        ct.updateAttribute("plan", null);
        assertTrue(ct.getAttributes().get("plan").isEmpty());
    }

    @Test
    void contextType_setName() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        ct.setName("Users");
        assertEquals("Users", ct.getName());
    }

    @Test
    void contextType_toString() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        assertTrue(ct.toString().contains("user"));
    }

    @Test
    void contextType_getCreatedAt_getUpdatedAt() {
        Instant now = Instant.now();
        ContextType ct = new ContextType(null, "x", "X", null, now, now);
        assertEquals(now, ct.getCreatedAt());
        assertEquals(now, ct.getUpdatedAt());
    }

    @Test
    void contextType_save_withoutClient_throws() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        assertThrows(IllegalStateException.class, ct::save);
    }

    @Test
    void contextType_delete_withoutClient_throws() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        assertThrows(IllegalStateException.class, ct::delete);
    }

    @Test
    void contextType_saveAsync_propagatesUnboundClient() {
        ContextType unbound = new ContextType(null, "id", "Name", null, null, null);
        var fut = unbound.saveAsync();
        Exception ex = assertThrows(java.util.concurrent.CompletionException.class, fut::join);
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void contextType_saveAsyncWithExecutor_usesExecutor() {
        ContextType unbound = new ContextType(null, "id", "Name", null, null, null);
        java.util.concurrent.atomic.AtomicBoolean used = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.Executor inline = r -> { used.set(true); r.run(); };
        unbound.saveAsync(inline).exceptionally(t -> null).join();
        assertTrue(used.get());
    }

    @Test
    void contextType_deleteAsync_default_returnsFuture() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        assertNotNull(ct.deleteAsync());
    }

    @Test
    void contextType_deleteAsync_runsOnProvidedExecutor() {
        ContextType ct = new ContextType(null, "user", "User", null, null, null);
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor inline = r -> { ran.incrementAndGet(); r.run(); };
        ct.deleteAsync(inline).exceptionally(t -> null).join();
        assertEquals(1, ran.get());
    }

    // =======================================================================
    // ContextEntity model
    // =======================================================================

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

    @Test
    void contextEntity_timestamps() {
        Instant now = Instant.now();
        ContextEntity entity = new ContextEntity("user", "k", null, null, now, now);
        assertEquals(now, entity.getCreatedAt());
        assertEquals(now, entity.getUpdatedAt());
    }

    // =======================================================================
    // EnvironmentsClient — via reflection-injected mock api
    // =======================================================================

    private EnvironmentsApi mockEnvApi;
    private EnvironmentsClient envClient;

    @BeforeEach
    void setUpEnvClient() throws Exception {
        mockEnvApi = mock(EnvironmentsApi.class);
        envClient = new EnvironmentsClient(buildFakeApiClient());
        inject(EnvironmentsClient.class, envClient, "api", mockEnvApi);
    }

    @Test
    void envClient_new_fourArg() {
        Environment env = envClient.new_("prod", "Production", null, EnvironmentClassification.STANDARD);
        assertEquals("prod", env.getId());
        assertEquals("Production", env.getName());
        assertNull(env.getColor());
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
        assertTrue(env.isManaged());
    }

    @Test
    void envClient_new_twoArg_defaultsColorNullClassificationStandard() {
        Environment env = envClient.new_("prod", "Production");
        assertEquals("prod", env.getId());
        assertEquals("Production", env.getName());
        assertNull(env.getColor());
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
    }

    @Test
    void envClient_new_nullClassification_defaultsToStandard() {
        Environment env = envClient.new_("prod", "Production", null, null);
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
    }

    @Test
    void envClient_list() throws Exception {
        EnvironmentListResponse resp = new EnvironmentListResponse();
        resp.setData(List.of(buildEnvResource("prod", "Production", null, "STANDARD",
                OffsetDateTime.now(), OffsetDateTime.now())));
        when(mockEnvApi.listEnvironments(null, null, null, null, null, null, null)).thenReturn(resp);

        List<Environment> envs = envClient.list();
        assertEquals(1, envs.size());
        assertEquals("prod", envs.get(0).getId());
    }

    @Test
    void envClient_list_emptyData() throws Exception {
        EnvironmentListResponse resp = new EnvironmentListResponse();
        resp.setData(null);
        when(mockEnvApi.listEnvironments(null, null, null, null, null, null, null)).thenReturn(resp);
        assertTrue(envClient.list().isEmpty());
    }

    @Test
    void envClient_get() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(buildEnvResource("prod", "Production", "#ff0000", "STANDARD",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockEnvApi.getEnvironment("prod")).thenReturn(resp);

        Environment env = envClient.get("prod");
        assertEquals("prod", env.getId());
        assertEquals("#ff0000", env.getColor());
    }

    @Test
    void envClient_get_nullAttributes() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        EnvironmentResource r = new EnvironmentResource().id("prod")
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT);
        resp.setData(r);
        when(mockEnvApi.getEnvironment("prod")).thenReturn(resp);

        Environment env = envClient.get("prod");
        assertEquals(EnvironmentClassification.STANDARD, env.getClassification());
        assertEquals("", env.getName());
        assertFalse(env.isManaged());
    }

    @Test
    void envClient_get_adHocAndManagedAttributes() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        com.smplkit.internal.generated.app.model.Environment attrs =
                new com.smplkit.internal.generated.app.model.Environment(
                        OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName("Ad Hoc");
        attrs.setClassification(
                com.smplkit.internal.generated.app.model.Environment.ClassificationEnum.AD_HOC);
        attrs.setManaged(true);
        EnvironmentResource r = new EnvironmentResource().id("adhoc")
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT)
                .attributes(attrs);
        resp.setData(r);
        when(mockEnvApi.getEnvironment("adhoc")).thenReturn(resp);

        Environment env = envClient.get("adhoc");
        assertEquals(EnvironmentClassification.AD_HOC, env.getClassification());
        assertTrue(env.isManaged());
        assertNotNull(env.getCreatedAt());
        assertNotNull(env.getUpdatedAt());
    }

    @Test
    void envClient_delete() throws Exception {
        doNothing().when(mockEnvApi).deleteEnvironment("prod", null);
        assertDoesNotThrow(() -> envClient.delete("prod"));
        verify(mockEnvApi).deleteEnvironment("prod", null);
    }

    @Test
    void envClient_create_viaModelSave() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(buildEnvResource("prod", "Production", null, "STANDARD",
                OffsetDateTime.now(), OffsetDateTime.now()));
        ArgumentCaptor<EnvironmentCreateRequest> captor =
                ArgumentCaptor.forClass(EnvironmentCreateRequest.class);
        when(mockEnvApi.createEnvironment(captor.capture())).thenReturn(resp);

        Environment env = envClient.new_("prod", "Production", null, EnvironmentClassification.STANDARD);
        env.save();

        assertEquals("prod", env.getId());
        assertNotNull(env.getCreatedAt());
        verify(mockEnvApi).createEnvironment(any(EnvironmentCreateRequest.class));
        EnvironmentCreateResource data = captor.getValue().getData();
        assertEquals("prod", data.getId());
        assertEquals(EnvironmentCreateResource.TypeEnum.ENVIRONMENT, data.getType());
    }

    @Test
    void envClient_update_viaModelSave() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(buildEnvResource("prod", "Production Updated", "#00ff00", "STANDARD",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockEnvApi.updateEnvironment(eq("prod"), any())).thenReturn(resp);

        Environment env = new Environment(
                envClient, "prod", "Production", null, EnvironmentClassification.STANDARD, true,
                Instant.now(), Instant.now());
        env.setName("Production Updated");
        env.setColor("#00ff00");
        env.save();

        assertEquals("Production Updated", env.getName());
        verify(mockEnvApi).updateEnvironment(eq("prod"), any());
    }

    @Test
    void envClient_buildRequest_withColorAndAdHoc() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(buildEnvResource("prod", "Production", "#ff0000", "AD_HOC",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockEnvApi.createEnvironment(any())).thenReturn(resp);

        Environment env = envClient.new_("prod", "Production", "#ff0000", EnvironmentClassification.AD_HOC);
        env.save();

        assertEquals(EnvironmentClassification.AD_HOC, env.getClassification());
    }

    @Test
    void envClient_apiException_mapped() throws Exception {
        when(mockEnvApi.listEnvironments(null, null, null, null, null, null, null))
                .thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplError.class, () -> envClient.list());
    }

    @Test
    void envClient_apiException_zeroCode() throws Exception {
        when(mockEnvApi.listEnvironments(null, null, null, null, null, null, null))
                .thenThrow(new ApiException(0, "network error"));
        assertThrows(SmplError.class, () -> envClient.list());
    }

    @Test
    void envClient_get_nullResponse_throws() throws Exception {
        EnvironmentResponse resp = new EnvironmentResponse();
        when(mockEnvApi.getEnvironment("x")).thenReturn(resp);
        assertThrows(IllegalStateException.class, () -> envClient.get("x"));
    }

    @Test
    void envClient_get_apiException() throws Exception {
        when(mockEnvApi.getEnvironment(any())).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplError.class, () -> envClient.get("prod"));
    }

    @Test
    void envClient_delete_apiException() throws Exception {
        doThrow(new ApiException(500, "error")).when(mockEnvApi).deleteEnvironment(any(), any());
        assertThrows(SmplError.class, () -> envClient.delete("prod"));
    }

    @Test
    void envClient_create_apiException() throws Exception {
        when(mockEnvApi.createEnvironment(any())).thenThrow(new ApiException(400, "bad request"));
        Environment env = envClient.new_("prod", "Production", null, EnvironmentClassification.STANDARD);
        assertThrows(SmplError.class, env::save);
    }

    @Test
    void envClient_update_apiException() throws Exception {
        when(mockEnvApi.updateEnvironment(any(), any())).thenThrow(new ApiException(409, "conflict"));
        Environment env = new Environment(
                envClient, "prod", "Production", null, EnvironmentClassification.STANDARD, true,
                Instant.now(), Instant.now());
        assertThrows(SmplError.class, env::save);
    }

    @Test
    void envClient_delete_viaModel() throws Exception {
        doNothing().when(mockEnvApi).deleteEnvironment("prod", null);
        Environment env = new Environment(
                envClient, "prod", "Production", null, EnvironmentClassification.STANDARD, true,
                Instant.now(), Instant.now());
        assertDoesNotThrow(env::delete);
        verify(mockEnvApi).deleteEnvironment("prod", null);
    }

    // =======================================================================
    // ServicesClient — via reflection-injected mock api
    // =======================================================================

    private ServicesApi mockSvcApi;
    private ServicesClient svcClient;

    @BeforeEach
    void setUpSvcClient() throws Exception {
        mockSvcApi = mock(ServicesApi.class);
        svcClient = new ServicesClient(buildFakeApiClient());
        inject(ServicesClient.class, svcClient, "api", mockSvcApi);
    }

    @Test
    void svcClient_new_() {
        Service svc = svcClient.new_("user_service", "User Service");
        assertEquals("user_service", svc.getId());
        assertEquals("User Service", svc.getName());
        assertNull(svc.getCreatedAt());
    }

    @Test
    void svcClient_list() throws Exception {
        ServiceListResponse resp = new ServiceListResponse();
        resp.setData(List.of(buildSvcResource("user_service", "User Service",
                OffsetDateTime.now(), OffsetDateTime.now())));
        when(mockSvcApi.listServices(null, null, null, null, null)).thenReturn(resp);

        List<Service> svcs = svcClient.list();
        assertEquals(1, svcs.size());
        assertEquals("user_service", svcs.get(0).getId());
    }

    @Test
    void svcClient_list_emptyData() throws Exception {
        ServiceListResponse resp = new ServiceListResponse();
        resp.setData(null);
        when(mockSvcApi.listServices(null, null, null, null, null)).thenReturn(resp);
        assertTrue(svcClient.list().isEmpty());
    }

    @Test
    void svcClient_list_withPagination() throws Exception {
        ServiceListResponse resp = new ServiceListResponse();
        resp.setData(List.of());
        when(mockSvcApi.listServices(null, null, 2, 50, null)).thenReturn(resp);
        assertNotNull(svcClient.list(2, 50));
        verify(mockSvcApi).listServices(null, null, 2, 50, null);
    }

    @Test
    void svcClient_get() throws Exception {
        ServiceResponse resp = new ServiceResponse();
        resp.setData(buildSvcResource("user_service", "User Service",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockSvcApi.getService("user_service")).thenReturn(resp);

        Service svc = svcClient.get("user_service");
        assertEquals("user_service", svc.getId());
        assertEquals("User Service", svc.getName());
    }

    @Test
    void svcClient_get_nullAttributes() throws Exception {
        ServiceResponse resp = new ServiceResponse();
        ServiceResource r = new ServiceResource().id("svc")
                .type(ServiceResource.TypeEnum.SERVICE);
        resp.setData(r);
        when(mockSvcApi.getService("svc")).thenReturn(resp);

        Service svc = svcClient.get("svc");
        assertEquals("svc", svc.getId());
        assertEquals("", svc.getName());
    }

    @Test
    void svcClient_delete() throws Exception {
        doNothing().when(mockSvcApi).deleteService("user_service");
        assertDoesNotThrow(() -> svcClient.delete("user_service"));
        verify(mockSvcApi).deleteService("user_service");
    }

    @Test
    void svcClient_create_viaModelSave() throws Exception {
        ServiceResponse resp = new ServiceResponse();
        resp.setData(buildSvcResource("user_service", "User Service",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockSvcApi.createService(any())).thenReturn(resp);

        Service svc = svcClient.new_("user_service", "User Service");
        svc.save();

        assertEquals("user_service", svc.getId());
        assertNotNull(svc.getCreatedAt());
        verify(mockSvcApi).createService(any());
    }

    @Test
    void svcClient_update_viaModelSave() throws Exception {
        ServiceResponse resp = new ServiceResponse();
        resp.setData(buildSvcResource("user_service", "Renamed",
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockSvcApi.updateService(eq("user_service"), any())).thenReturn(resp);

        Service svc = new Service(svcClient, "user_service", "User Service", Instant.now(), Instant.now());
        svc.setName("Renamed");
        svc.save();

        assertEquals("Renamed", svc.getName());
        verify(mockSvcApi).updateService(eq("user_service"), any());
    }

    @Test
    void svcClient_delete_viaModel() throws Exception {
        doNothing().when(mockSvcApi).deleteService("user_service");
        Service svc = new Service(svcClient, "user_service", "User Service", Instant.now(), Instant.now());
        assertDoesNotThrow(svc::delete);
        verify(mockSvcApi).deleteService("user_service");
    }

    @Test
    void svcClient_apiException_mapped() throws Exception {
        when(mockSvcApi.listServices(null, null, null, null, null))
                .thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplError.class, () -> svcClient.list());
    }

    @Test
    void svcClient_apiException_zeroCode() throws Exception {
        when(mockSvcApi.listServices(null, null, null, null, null))
                .thenThrow(new ApiException(0, "network error"));
        assertThrows(SmplError.class, () -> svcClient.list());
    }

    @Test
    void svcClient_get_nullResponse_throws() throws Exception {
        ServiceResponse resp = new ServiceResponse();
        when(mockSvcApi.getService("x")).thenReturn(resp);
        assertThrows(IllegalStateException.class, () -> svcClient.get("x"));
    }

    @Test
    void svcClient_get_notFound_mappedToNotFoundError() throws Exception {
        when(mockSvcApi.getService("missing")).thenThrow(new ApiException(404, "not found"));
        assertThrows(NotFoundError.class, () -> svcClient.get("missing"));
    }

    @Test
    void svcClient_delete_apiException_mapped() throws Exception {
        doThrow(new ApiException(404, "not found")).when(mockSvcApi).deleteService("missing");
        assertThrows(SmplError.class, () -> svcClient.delete("missing"));
    }

    @Test
    void svcClient_create_apiException_mapped() throws Exception {
        when(mockSvcApi.createService(any())).thenThrow(new ApiException(409, "conflict"));
        Service svc = svcClient.new_("user_service", "User Service");
        assertThrows(SmplError.class, svc::save);
    }

    @Test
    void svcClient_update_apiException_mapped() throws Exception {
        when(mockSvcApi.updateService(eq("user_service"), any()))
                .thenThrow(new ApiException(500, "server error"));
        Service svc = new Service(svcClient, "user_service", "User Service", Instant.now(), Instant.now());
        assertThrows(SmplError.class, svc::save);
    }

    // =======================================================================
    // ContextTypesClient — via reflection-injected mock api
    // =======================================================================

    private ContextTypesApi mockCtApi;
    private ContextTypesClient ctClient;

    @BeforeEach
    void setUpCtClient() throws Exception {
        mockCtApi = mock(ContextTypesApi.class);
        ctClient = new ContextTypesClient(buildFakeApiClient());
        inject(ContextTypesClient.class, ctClient, "api", mockCtApi);
    }

    @Test
    void ctClient_new_() {
        ContextType ct = ctClient.new_("user");
        assertEquals("user", ct.getId());
        assertNotNull(ct.getName());
    }

    @Test
    void ctClient_new_withName() {
        ContextType ct = ctClient.new_("user", "User", null);
        assertEquals("User", ct.getName());
    }

    @Test
    void ctClient_new_nullName_usesDisplayName() {
        ContextType ct = ctClient.new_("user", null, null);
        assertNotNull(ct.getName());
    }

    @Test
    void ctClient_list() throws Exception {
        ContextTypeListResponse resp = new ContextTypeListResponse();
        resp.setData(List.of(buildCtResource("user", "User", new HashMap<>(),
                OffsetDateTime.now(), OffsetDateTime.now())));
        when(mockCtApi.listContextTypes(null, null, null, null)).thenReturn(resp);

        List<ContextType> cts = ctClient.list();
        assertEquals(1, cts.size());
        assertEquals("user", cts.get(0).getId());
    }

    @Test
    void ctClient_list_emptyData() throws Exception {
        ContextTypeListResponse resp = new ContextTypeListResponse();
        resp.setData(null);
        when(mockCtApi.listContextTypes(null, null, null, null)).thenReturn(resp);
        assertTrue(ctClient.list().isEmpty());
    }

    @Test
    void ctClient_get() throws Exception {
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User", new HashMap<>(),
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockCtApi.getContextType("user")).thenReturn(resp);

        ContextType ct = ctClient.get("user");
        assertEquals("user", ct.getId());
        assertNotNull(ct.getCreatedAt());
    }

    @Test
    void ctClient_get_withAttributes() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("plan", Map.of("desc", "User plan"));
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User", attrs,
                OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockCtApi.getContextType("user")).thenReturn(resp);

        ContextType ct = ctClient.get("user");
        assertTrue(ct.getAttributes().containsKey("plan"));
        assertEquals("User plan", ct.getAttributes().get("plan").get("desc"));
    }

    @Test
    void ctClient_get_withNonMapAttributeValue() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("plan", "string-value");
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(buildCtResource("user", "User", attrs, null, null));
        when(mockCtApi.getContextType("user")).thenReturn(resp);

        ContextType ct = ctClient.get("user");
        assertTrue(ct.getAttributes().get("plan").isEmpty());
    }

    @Test
    void ctClient_get_nullAttributes() throws Exception {
        ContextTypeResource r = new ContextTypeResource().id("user")
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE);
        ContextTypeResponse resp = new ContextTypeResponse().data(r);
        when(mockCtApi.getContextType("user")).thenReturn(resp);

        ContextType ct = ctClient.get("user");
        assertTrue(ct.getAttributes().isEmpty());
        assertEquals("user", ct.getName());
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

        ContextType ct = ctClient.new_("user");
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

        ContextType ct = new ContextType(ctClient, "user", "User", new HashMap<>(), Instant.now(), Instant.now());
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

        ContextType ct = ctClient.new_("user");
        ct.save();
        verify(mockCtApi).createContextType(any());
    }

    @Test
    void ctClient_delete_viaModel() throws Exception {
        doNothing().when(mockCtApi).deleteContextType("user");
        ContextType ct = new ContextType(ctClient, "user", "User", new HashMap<>(), Instant.now(), Instant.now());
        assertDoesNotThrow(ct::delete);
        verify(mockCtApi).deleteContextType("user");
    }

    @Test
    void ctClient_get_nullResponse_throws() throws Exception {
        ContextTypeResponse resp = new ContextTypeResponse();
        when(mockCtApi.getContextType("x")).thenReturn(resp);
        assertThrows(IllegalStateException.class, () -> ctClient.get("x"));
    }

    @Test
    void ctClient_apiException_mapped() throws Exception {
        when(mockCtApi.listContextTypes(null, null, null, null)).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplError.class, () -> ctClient.list());
    }

    @Test
    void ctClient_apiException_zeroCode() throws Exception {
        when(mockCtApi.listContextTypes(null, null, null, null)).thenThrow(new ApiException(0, "network"));
        assertThrows(SmplError.class, () -> ctClient.list());
    }

    @Test
    void ctClient_get_apiException() throws Exception {
        when(mockCtApi.getContextType(any())).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplError.class, () -> ctClient.get("user"));
    }

    @Test
    void ctClient_delete_apiException() throws Exception {
        doThrow(new ApiException(500, "error")).when(mockCtApi).deleteContextType(any());
        assertThrows(SmplError.class, () -> ctClient.delete("user"));
    }

    @Test
    void ctClient_create_apiException() throws Exception {
        when(mockCtApi.createContextType(any())).thenThrow(new ApiException(400, "bad request"));
        ContextType ct = ctClient.new_("user");
        assertThrows(SmplError.class, ct::save);
    }

    @Test
    void ctClient_update_apiException() throws Exception {
        when(mockCtApi.updateContextType(any(), any())).thenThrow(new ApiException(409, "conflict"));
        ContextType ct = new ContextType(ctClient, "user", "User", Map.of(), Instant.now(), Instant.now());
        assertThrows(SmplError.class, ct::save);
    }

    // =======================================================================
    // ContextsClient — via reflection-injected mock api
    // =======================================================================

    private ContextsApi mockCtxApi;
    private ContextsClient ctxClient;
    private ContextRegistrationBuffer ctxBuffer;

    @BeforeEach
    void setUpCtxClient() throws Exception {
        mockCtxApi = mock(ContextsApi.class);
        ctxBuffer = new ContextRegistrationBuffer();
        ctxClient = new ContextsClient(buildFakeApiClient(), ctxBuffer);
        inject(ContextsClient.class, ctxClient, "api", mockCtxApi);
    }

    @Test
    void ctxClient_register_single() {
        ctxClient.register(new Context("user", "u1", Map.of("plan", "free")));
        assertEquals(1, ctxBuffer.pendingCount());
        assertEquals(1, ctxClient.pendingCount());
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
        ctxClient.flush();
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
        assertThrows(SmplError.class, () -> ctxClient.flush());
    }

    @Test
    void ctxClient_list() throws Exception {
        ContextListResponse resp = new ContextListResponse();
        resp.setData(List.of(buildCtxResource("user:u1", "Alice", new HashMap<>(),
                OffsetDateTime.now(), OffsetDateTime.now())));
        when(mockCtxApi.listContexts("user", null, null, null, null, null)).thenReturn(resp);

        List<ContextEntity> entities = ctxClient.list("user");
        assertEquals(1, entities.size());
        assertEquals("user", entities.get(0).getType());
        assertEquals("u1", entities.get(0).getKey());
    }

    @Test
    void ctxClient_list_emptyData() throws Exception {
        ContextListResponse resp = new ContextListResponse();
        resp.setData(null);
        when(mockCtxApi.listContexts("user", null, null, null, null, null)).thenReturn(resp);
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
        ContextResponse resp = new ContextResponse();
        when(mockCtxApi.getContext("user:u1")).thenReturn(resp);
        assertThrows(NotFoundError.class, () -> ctxClient.get("user:u1"));
    }

    @Test
    void ctxClient_get_noColonId_throws() {
        assertThrows(IllegalArgumentException.class, () -> ctxClient.get("nocolon"));
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
    void ctxClient_delete_noColonId_throws() {
        assertThrows(IllegalArgumentException.class, () -> ctxClient.delete("nocolon"));
    }

    @Test
    void ctxClient_delete_apiException() throws Exception {
        doThrow(new ApiException(404, "not found")).when(mockCtxApi).deleteContext(any());
        assertThrows(SmplError.class, () -> ctxClient.delete("user:u1"));
    }

    @Test
    void ctxClient_get_apiException() throws Exception {
        when(mockCtxApi.getContext(any())).thenThrow(new ApiException(404, "not found"));
        assertThrows(SmplError.class, () -> ctxClient.get("user:u1"));
    }

    @Test
    void ctxClient_list_apiException() throws Exception {
        when(mockCtxApi.listContexts(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(0, "error"));
        assertThrows(SmplError.class, () -> ctxClient.list("user"));
    }

    @Test
    void ctxClient_resource_withAttributes() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("plan", "free");
        ContextResponse resp = new ContextResponse();
        resp.setData(buildCtxResource("user:u1", "Alice", attrs, OffsetDateTime.now(), OffsetDateTime.now()));
        when(mockCtxApi.getContext("user:u1")).thenReturn(resp);

        ContextEntity e = ctxClient.get("user:u1");
        assertEquals("free", e.getAttributes().get("plan"));
        assertEquals("Alice", e.getName());
        assertNotNull(e.getCreatedAt());
        assertNotNull(e.getUpdatedAt());
    }

    @Test
    void ctxClient_resourceToEntity_noColonInResourceId() throws Exception {
        // The wrapper fetches with a normalized "type:key" id, but the server's
        // resource id may itself lack a colon — resourceToEntity falls back to
        // type=id, key="".
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

    @Test
    void ctxClient_resourceToEntity_nullResourceId() throws Exception {
        ContextResponse resp = new ContextResponse();
        ContextResource r = new ContextResource(); // id == null
        resp.setData(r);
        when(mockCtxApi.getContext("user:u1")).thenReturn(resp);

        ContextEntity e = ctxClient.get("user:u1");
        assertEquals("", e.getType());
        assertEquals("", e.getKey());
    }

    // =======================================================================
    // Pagination — sync pass-through + async delegation
    // =======================================================================

    @Test
    void env_listWithPagination_passesThrough() throws ApiException {
        EnvironmentListResponse resp = new EnvironmentListResponse();
        resp.setData(List.of(buildEnvResource("prod", "Production", null, "STANDARD", null, null)));
        when(mockEnvApi.listEnvironments(isNull(), isNull(), isNull(), isNull(), eq(2), eq(50), isNull()))
                .thenReturn(resp);

        List<Environment> envs = envClient.list(2, 50);
        assertEquals(1, envs.size());
        verify(mockEnvApi).listEnvironments(isNull(), isNull(), isNull(), isNull(), eq(2), eq(50), isNull());
    }

    @Test
    void env_asyncListWithPagination_delegatesToSync() throws Exception {
        EnvironmentListResponse resp = new EnvironmentListResponse();
        resp.setData(List.of(buildEnvResource("staging", "Staging", null, "STANDARD", null, null)));
        when(mockEnvApi.listEnvironments(isNull(), isNull(), isNull(), isNull(), eq(3), eq(10), isNull()))
                .thenReturn(resp);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            AsyncEnvironmentsClient async = new AsyncEnvironmentsClient(envClient, exec);
            List<Environment> envs = async.list(3, 10).get();
            assertEquals(1, envs.size());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void ct_listWithPagination_passesThrough() throws ApiException {
        ContextTypeListResponse resp = new ContextTypeListResponse();
        resp.setData(List.of(buildCtResource("user", "User", new HashMap<>(), null, null)));
        when(mockCtApi.listContextTypes(isNull(), eq(4), eq(25), isNull())).thenReturn(resp);

        List<ContextType> result = ctClient.list(4, 25);
        assertEquals(1, result.size());
        verify(mockCtApi).listContextTypes(isNull(), eq(4), eq(25), isNull());
    }

    @Test
    void ct_asyncListWithPagination_delegatesToSync() throws Exception {
        ContextTypeListResponse resp = new ContextTypeListResponse();
        resp.setData(List.of(buildCtResource("org", "Org", new HashMap<>(), null, null)));
        when(mockCtApi.listContextTypes(isNull(), eq(1), eq(5), isNull())).thenReturn(resp);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            AsyncContextTypesClient async = new AsyncContextTypesClient(ctClient, exec);
            List<ContextType> result = async.list(1, 5).get();
            assertEquals(1, result.size());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void ctx_listWithPagination_passesThrough() throws ApiException {
        ContextListResponse resp = new ContextListResponse();
        resp.setData(List.of(buildCtxResource("user:42", "42", new HashMap<>(), null, null)));
        when(mockCtxApi.listContexts(eq("user"), isNull(), isNull(), eq(2), eq(100), isNull())).thenReturn(resp);

        List<ContextEntity> result = ctxClient.list("user", 2, 100);
        assertEquals(1, result.size());
    }

    @Test
    void ctx_asyncListWithPagination_delegatesToSync() throws Exception {
        ContextListResponse resp = new ContextListResponse();
        resp.setData(List.of(buildCtxResource("org:acme", "acme", new HashMap<>(), null, null)));
        when(mockCtxApi.listContexts(eq("org"), isNull(), isNull(), eq(7), eq(20), isNull())).thenReturn(resp);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            AsyncContextsClient async = new AsyncContextsClient(ctxClient, exec);
            List<ContextEntity> result = async.list("org", 7, 20).get();
            assertEquals(1, result.size());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void svc_asyncListWithPagination_delegatesToSync() throws Exception {
        ServiceListResponse resp = new ServiceListResponse();
        resp.setData(List.of(buildSvcResource("user_service", "User Service", null, null)));
        when(mockSvcApi.listServices(null, null, 1, 100, null)).thenReturn(resp);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            AsyncServicesClient async = new AsyncServicesClient(svcClient, exec);
            List<Service> result = async.list(1, 100).get();
            assertEquals(1, result.size());
        } finally {
            exec.shutdownNow();
        }
    }

    // =======================================================================
    // Async wrapper construction + delegation (no real HTTP)
    // =======================================================================

    @Test
    void asyncPlatformClient_create_buildsAllNamespaces() {
        try (AsyncPlatformClient platform = AsyncPlatformClient.create("test-key")) {
            assertNotNull(platform.environments);
            assertNotNull(platform.services);
            assertNotNull(platform.contexts);
            assertNotNull(platform.contextTypes);
        }
    }

    @Test
    void asyncPlatformClient_wrap_reusesSyncClient_andExposesAccessors() {
        try (PlatformClient sync = PlatformClient.create("test-key");
             AsyncPlatformClient async = AsyncPlatformClient.wrap(sync)) {
            assertSame(sync, async.sync());
            assertNotNull(async.executor());
        }
    }

    @Test
    void asyncPlatformClient_wrap_customExecutor() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try (PlatformClient sync = PlatformClient.create("test-key");
             AsyncPlatformClient async = AsyncPlatformClient.wrap(sync, exec)) {
            assertSame(exec, async.executor());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void asyncPlatformClient_create_noArg_coversFactory() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_async_platform_create");
        try (AsyncPlatformClient platform = AsyncPlatformClient.create()) {
            assertNotNull(platform.environments);
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void asyncEnvironmentsClient_new_andDelegationReturnFutures() {
        AsyncEnvironmentsClient async = new AsyncEnvironmentsClient(envClient, java.util.concurrent.ForkJoinPool.commonPool());
        assertNotNull(async.new_("e", "E"));
        assertNotNull(async.new_("e", "E", "#ff0000", EnvironmentClassification.STANDARD));
        assertNotNull(async.list());
        assertNotNull(async.get("e"));
        assertNotNull(async.delete("e"));
    }

    @Test
    void asyncServicesClient_new_andDelegationReturnFutures() {
        AsyncServicesClient async = new AsyncServicesClient(svcClient, java.util.concurrent.ForkJoinPool.commonPool());
        assertNotNull(async.new_("s", "S"));
        assertNotNull(async.list());
        assertNotNull(async.get("s"));
        assertNotNull(async.delete("s"));
    }

    @Test
    void asyncContextTypesClient_new_andDelegationReturnFutures() {
        AsyncContextTypesClient async = new AsyncContextTypesClient(ctClient, java.util.concurrent.ForkJoinPool.commonPool());
        assertNotNull(async.new_("user"));
        assertNotNull(async.new_("user", "User", Map.of("plan", Map.of("type", "STRING"))));
        assertNotNull(async.list());
        assertNotNull(async.get("user"));
        assertNotNull(async.delete("user"));
    }

    @Test
    void asyncContextsClient_allOverloadsReturnFutures() {
        AsyncContextsClient async = new AsyncContextsClient(ctxClient, java.util.concurrent.ForkJoinPool.commonPool());
        assertNotNull(async.register(List.of(new Context("user", "u-1", Map.of()))));
        assertNotNull(async.register(new Context("user", "u-2", Map.of())));
        assertNotNull(async.flush());
        assertNotNull(async.list("user"));
        assertNotNull(async.get("user:u-1"));
        assertNotNull(async.get("user", "u-1"));
        assertNotNull(async.delete("user:u-1"));
        assertNotNull(async.delete("user", "u-1"));
    }

    @Test
    void asyncContextsClient_pendingCount_delegates() {
        AsyncContextsClient async = new AsyncContextsClient(ctxClient, java.util.concurrent.ForkJoinPool.commonPool());
        ctxClient.register(new Context("user", "u-pending", null));
        assertEquals(1, async.pendingCount());
    }

    // =======================================================================
    // PlatformClient — wired + standalone construction, close, builder
    // =======================================================================

    @Test
    void platformClient_wired_borrowsTransport_closeIsNoopOnTransport() {
        ApiClient appApiClient = buildFakeApiClient();
        ContextRegistrationBuffer buffer = new ContextRegistrationBuffer();
        PlatformClient wired = PlatformClient.wired(appApiClient, buffer);
        assertNotNull(wired.environments);
        assertNotNull(wired.services);
        assertNotNull(wired.contexts);
        assertNotNull(wired.contextTypes);
        // Wired client borrows the buffer: register via contexts, observe it lands in the shared buffer.
        wired.contexts.register(new Context("user", "wired", null));
        assertEquals(1, buffer.pendingCount());
        // close() on a wired client tears down nothing.
        assertDoesNotThrow(wired::close);
    }

    @Test
    void platformClient_create_withApiKey_standalone() {
        try (PlatformClient platform = PlatformClient.create("test-key")) {
            assertNotNull(platform.environments);
            assertNotNull(platform.services);
            assertNotNull(platform.contexts);
            assertNotNull(platform.contextTypes);
        }
    }

    @Test
    void platformClient_create_noArg_coversFactory() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_platform_create");
        try (PlatformClient platform = PlatformClient.create()) {
            assertNotNull(platform.environments);
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void platformClient_close_isIdempotent() {
        PlatformClient platform = PlatformClient.create("test-key");
        platform.close();
        platform.close();
    }

    @Test
    void platformClientBuilder_allSetters_build() {
        try (PlatformClient platform = PlatformClient.builder()
                .profile("default")
                .apiKey("test-key")
                .baseDomain("smplkit.example")
                .scheme("https")
                .debug(false)
                .extraHeaders(Map.of("X-Test", "1"))
                .timeout(java.time.Duration.ofSeconds(7))
                .build()) {
            assertNotNull(platform.environments);
        }
    }

    @Test
    void platformClientBuilder_debugTrue_enablesDebug() {
        try (PlatformClient platform = PlatformClient.builder()
                .apiKey("test-key")
                .debug(true)
                .build()) {
            assertNotNull(platform.contexts);
        }
    }

    @Test
    void platformClientBuilder_extraHeaders_null_clears() {
        try (PlatformClient platform = PlatformClient.builder()
                .apiKey("test-key")
                .extraHeaders(null)
                .build()) {
            assertNotNull(platform.environments);
        }
    }

    @Test
    void platformClientBuilder_rejectsNullArgs() {
        assertThrows(NullPointerException.class, () -> PlatformClient.builder().profile(null));
        assertThrows(NullPointerException.class, () -> PlatformClient.builder().apiKey(null));
        assertThrows(NullPointerException.class, () -> PlatformClient.builder().baseDomain(null));
        assertThrows(NullPointerException.class, () -> PlatformClient.builder().scheme(null));
        assertThrows(NullPointerException.class, () -> PlatformClient.builder().timeout(null));
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private static ApiClient buildFakeApiClient() {
        ApiClient c = new ApiClient();
        c.updateBaseUri("https://app.smplkit.example");
        return c;
    }

    private static void inject(Class<?> cls, Object instance, String field, Object value) throws Exception {
        var f = cls.getDeclaredField(field);
        f.setAccessible(true);
        f.set(instance, value);
    }

    private static EnvironmentResource buildEnvResource(String id, String name, String color,
                                                        String classification,
                                                        OffsetDateTime createdAt,
                                                        OffsetDateTime updatedAt) {
        com.smplkit.internal.generated.app.model.Environment attrs =
                new com.smplkit.internal.generated.app.model.Environment(createdAt, updatedAt);
        attrs.setName(name);
        if (color != null) attrs.setColor(color);
        if (classification != null) {
            attrs.setClassification(
                    com.smplkit.internal.generated.app.model.Environment.ClassificationEnum.fromValue(classification));
        }
        return new EnvironmentResource().id(id)
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT)
                .attributes(attrs);
    }

    private static ServiceResource buildSvcResource(String id, String name,
                                                    OffsetDateTime createdAt,
                                                    OffsetDateTime updatedAt) {
        com.smplkit.internal.generated.app.model.Service attrs =
                new com.smplkit.internal.generated.app.model.Service(createdAt, updatedAt);
        attrs.setName(name);
        return new ServiceResource().id(id)
                .type(ServiceResource.TypeEnum.SERVICE)
                .attributes(attrs);
    }

    private static ContextTypeResource buildCtResource(String id, String name,
                                                       Map<String, Object> attrs,
                                                       OffsetDateTime createdAt,
                                                       OffsetDateTime updatedAt) {
        com.smplkit.internal.generated.app.model.ContextType ct =
                new com.smplkit.internal.generated.app.model.ContextType(createdAt, updatedAt);
        ct.setName(name);
        if (attrs != null) ct.setAttributes(attrs);
        return new ContextTypeResource()
                .id(id)
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(ct);
    }

    private static ContextResource buildCtxResource(String compositeId, String name,
                                                    Map<String, Object> attrs,
                                                    OffsetDateTime createdAt,
                                                    OffsetDateTime updatedAt) {
        com.smplkit.internal.generated.app.model.Context c =
                new com.smplkit.internal.generated.app.model.Context(null, createdAt, updatedAt);
        c.setName(name);
        if (attrs != null) c.setAttributes(attrs);
        ContextResource r = new ContextResource().id(compositeId);
        r.setAttributes(c);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((Map<String, String>) field.get(env)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearEnv(String key) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((Map<String, String>) field.get(env)).remove(key);
    }
}
