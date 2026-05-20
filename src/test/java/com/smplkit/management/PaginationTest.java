package com.smplkit.management;

import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.ContextTypesApi;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.api.EnvironmentsApi;
import com.smplkit.internal.generated.app.model.ContextListResponse;
import com.smplkit.internal.generated.app.model.ContextResource;
import com.smplkit.internal.generated.app.model.ContextTypeListResponse;
import com.smplkit.internal.generated.app.model.ContextTypeResource;
import com.smplkit.internal.generated.app.model.ContextType;
import com.smplkit.internal.generated.app.model.Environment;
import com.smplkit.internal.generated.app.model.EnvironmentListResponse;
import com.smplkit.internal.generated.app.model.EnvironmentResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaginationTest {

    private EnvironmentsApi mockEnvApi;
    private EnvironmentsClient envClient;
    private ContextTypesApi mockCtApi;
    private ContextTypesClient ctClient;
    private ContextsApi mockCtxApi;
    private ContextsClient ctxClient;

    @BeforeEach
    void setUp() throws Exception {
        var fake = buildFakeApiClient();
        mockEnvApi = mock(EnvironmentsApi.class);
        envClient = new EnvironmentsClient(fake);
        inject(EnvironmentsClient.class, envClient, "api", mockEnvApi);

        mockCtApi = mock(ContextTypesApi.class);
        ctClient = new ContextTypesClient(fake);
        inject(ContextTypesClient.class, ctClient, "api", mockCtApi);

        mockCtxApi = mock(ContextsApi.class);
        ctxClient = new ContextsClient(fake, new ContextRegistrationBuffer());
        inject(ContextsClient.class, ctxClient, "api", mockCtxApi);
    }

    @Test
    void env_listWithPagination_passesThrough() throws ApiException {
        EnvironmentListResponse resp = new EnvironmentListResponse();
        resp.setData(List.of(buildEnvResource("prod")));
        when(mockEnvApi.listEnvironments(isNull(), isNull(), isNull(), eq(2), eq(50), isNull())).thenReturn(resp);

        List<com.smplkit.management.Environment> envs = envClient.list(2, 50);
        assertEquals(1, envs.size());
        verify(mockEnvApi).listEnvironments(isNull(), isNull(), isNull(), eq(2), eq(50), isNull());
    }

    @Test
    void env_asyncListWithPagination_delegatesToSync() throws Exception {
        EnvironmentListResponse resp = new EnvironmentListResponse();
        resp.setData(List.of(buildEnvResource("staging")));
        when(mockEnvApi.listEnvironments(isNull(), isNull(), isNull(), eq(3), eq(10), isNull())).thenReturn(resp);

        AsyncEnvironmentsClient async = new AsyncEnvironmentsClient(
                envClient, Executors.newSingleThreadExecutor());
        List<com.smplkit.management.Environment> envs = async.list(3, 10).get();
        assertEquals(1, envs.size());
    }

    @Test
    void ct_listWithPagination_passesThrough() throws ApiException {
        ContextTypeListResponse resp = new ContextTypeListResponse();
        resp.setData(List.of(buildCtResource("user")));
        when(mockCtApi.listContextTypes(isNull(), eq(4), eq(25), isNull())).thenReturn(resp);

        List<ContextType> ignored;
        List<com.smplkit.management.ContextType> result = ctClient.list(4, 25);
        assertEquals(1, result.size());
        verify(mockCtApi).listContextTypes(isNull(), eq(4), eq(25), isNull());
    }

    @Test
    void ct_asyncListWithPagination_delegatesToSync() throws Exception {
        ContextTypeListResponse resp = new ContextTypeListResponse();
        resp.setData(List.of(buildCtResource("org")));
        when(mockCtApi.listContextTypes(isNull(), eq(1), eq(5), isNull())).thenReturn(resp);

        AsyncContextTypesClient async = new AsyncContextTypesClient(
                ctClient, Executors.newSingleThreadExecutor());
        List<com.smplkit.management.ContextType> result = async.list(1, 5).get();
        assertEquals(1, result.size());
    }

    @Test
    void ctx_listWithPagination_passesThrough() throws ApiException {
        ContextListResponse resp = new ContextListResponse();
        resp.setData(List.of(buildCtxResource("user:42")));
        when(mockCtxApi.listContexts(eq("user"), isNull(), isNull(),
                eq(2), eq(100), isNull())).thenReturn(resp);

        List<ContextEntity> result = ctxClient.list("user", 2, 100);
        assertEquals(1, result.size());
    }

    @Test
    void ctx_asyncListWithPagination_delegatesToSync() throws Exception {
        ContextListResponse resp = new ContextListResponse();
        resp.setData(List.of(buildCtxResource("org:acme")));
        when(mockCtxApi.listContexts(eq("org"), isNull(), isNull(),
                eq(7), eq(20), isNull())).thenReturn(resp);

        AsyncContextsClient async = new AsyncContextsClient(
                ctxClient, Executors.newSingleThreadExecutor());
        List<ContextEntity> result = async.list("org", 7, 20).get();
        assertEquals(1, result.size());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static com.smplkit.internal.generated.app.ApiClient buildFakeApiClient() {
        var c = new com.smplkit.internal.generated.app.ApiClient();
        c.updateBaseUri("https://app.smplkit.com");
        return c;
    }

    private static void inject(Class<?> cls, Object instance, String field, Object value)
            throws Exception {
        var f = cls.getDeclaredField(field);
        f.setAccessible(true);
        f.set(instance, value);
    }

    private static EnvironmentResource buildEnvResource(String id) {
        Environment attrs = new Environment();
        attrs.setName(id);
        attrs.setClassification(Environment.ClassificationEnum.STANDARD);
        return new EnvironmentResource().id(id)
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT)
                .attributes(attrs);
    }

    private static ContextTypeResource buildCtResource(String id) {
        ContextType ct = new ContextType();
        ct.setName(id);
        return new ContextTypeResource()
                .id(id)
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(ct);
    }

    private static ContextResource buildCtxResource(String compositeId) {
        var c = new com.smplkit.internal.generated.app.model.Context();
        c.setName(compositeId);
        ContextResource r = new ContextResource().id(compositeId);
        r.setAttributes(c);
        return r;
    }
}
