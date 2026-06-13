package com.smplkit.platform;

import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.api.ContextTypesApi;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.api.EnvironmentsApi;
import com.smplkit.internal.generated.app.api.ServicesApi;
import com.smplkit.internal.generated.app.model.ContextListResponse;
import com.smplkit.internal.generated.app.model.ContextResource;
import com.smplkit.internal.generated.app.model.ContextResponse;
import com.smplkit.internal.generated.app.model.ContextTypeResource;
import com.smplkit.internal.generated.app.model.ContextTypeResponse;
import com.smplkit.internal.generated.app.model.EnvironmentResource;
import com.smplkit.internal.generated.app.model.EnvironmentResponse;
import com.smplkit.internal.generated.app.model.ServiceResource;
import com.smplkit.internal.generated.app.model.ServiceResponse;
import com.smplkit.internal.ContextRegistrationBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the async-wrapper {@code get(...)} / {@code list(type)} delegations
 * whose supplied lambda bodies the broad suite registers but never executes
 * (the futures are not awaited there). Each test awaits the future so the
 * lambda actually runs against a mocked generated {@code *Api}.
 *
 * <p>No real network: the generated {@code api} field of each sync sub-client
 * is replaced by a Mockito mock via reflection; the futures run on a dedicated
 * single-thread executor that is shut down in {@link #tearDown()}.</p>
 */
class PlatformAsyncDelegationTest {

    private ExecutorService exec;

    @BeforeEach
    void setUp() {
        exec = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    private static ApiClient fakeApiClient() {
        ApiClient c = new ApiClient();
        c.updateBaseUri("https://app.smplkit.example");
        return c;
    }

    private static void inject(Class<?> cls, Object instance, String field, Object value) throws Exception {
        var f = cls.getDeclaredField(field);
        f.setAccessible(true);
        f.set(instance, value);
    }

    // -----------------------------------------------------------------------
    // AsyncEnvironmentsClient.get(id) — line 60
    // -----------------------------------------------------------------------

    @Test
    void asyncEnvironments_get_runsLambda() throws Exception {
        EnvironmentsApi mockApi = mock(EnvironmentsApi.class);
        EnvironmentsClient sync = new EnvironmentsClient(fakeApiClient());
        inject(EnvironmentsClient.class, sync, "api", mockApi);

        var attrs = new com.smplkit.internal.generated.app.model.Environment(
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName("Production");
        EnvironmentResponse resp = new EnvironmentResponse();
        resp.setData(new EnvironmentResource().id("prod")
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT).attributes(attrs));
        when(mockApi.getEnvironment("prod")).thenReturn(resp);

        AsyncEnvironmentsClient async = new AsyncEnvironmentsClient(sync, exec);
        Environment env = async.get("prod").get(5, TimeUnit.SECONDS);
        assertEquals("prod", env.getId());
    }

    // -----------------------------------------------------------------------
    // AsyncServicesClient.get(id) — line 53
    // -----------------------------------------------------------------------

    @Test
    void asyncServices_get_runsLambda() throws Exception {
        ServicesApi mockApi = mock(ServicesApi.class);
        ServicesClient sync = new ServicesClient(fakeApiClient());
        inject(ServicesClient.class, sync, "api", mockApi);

        var attrs = new com.smplkit.internal.generated.app.model.Service(
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName("User Service");
        ServiceResponse resp = new ServiceResponse();
        resp.setData(new ServiceResource().id("user_service")
                .type(ServiceResource.TypeEnum.SERVICE).attributes(attrs));
        when(mockApi.getService("user_service")).thenReturn(resp);

        AsyncServicesClient async = new AsyncServicesClient(sync, exec);
        Service svc = async.get("user_service").get(5, TimeUnit.SECONDS);
        assertEquals("user_service", svc.getId());
    }

    // -----------------------------------------------------------------------
    // AsyncContextTypesClient.get(id) — line 68
    // -----------------------------------------------------------------------

    @Test
    void asyncContextTypes_get_runsLambda() throws Exception {
        ContextTypesApi mockApi = mock(ContextTypesApi.class);
        ContextTypesClient sync = new ContextTypesClient(fakeApiClient());
        inject(ContextTypesClient.class, sync, "api", mockApi);

        var attrs = new com.smplkit.internal.generated.app.model.ContextType(
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName("User");
        ContextTypeResponse resp = new ContextTypeResponse();
        resp.setData(new ContextTypeResource().id("user")
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE).attributes(attrs));
        when(mockApi.getContextType("user")).thenReturn(resp);

        AsyncContextTypesClient async = new AsyncContextTypesClient(sync, exec);
        ContextType ct = async.get("user").get(5, TimeUnit.SECONDS);
        assertEquals("user", ct.getId());
    }

    // -----------------------------------------------------------------------
    // AsyncContextsClient.list(type) — line 65,
    // .get(compositeId) — line 91, .get(type, key) — line 104
    // -----------------------------------------------------------------------

    private ContextsClient newCtxSync(ContextsApi mockApi) throws Exception {
        ContextsClient sync = new ContextsClient(fakeApiClient(), new ContextRegistrationBuffer());
        inject(ContextsClient.class, sync, "api", mockApi);
        return sync;
    }

    private static ContextResource ctxResource(String compositeId) {
        var c = new com.smplkit.internal.generated.app.model.Context(
                null, OffsetDateTime.now(), OffsetDateTime.now());
        c.setName("Ctx");
        c.setAttributes(new HashMap<>());
        ContextResource r = new ContextResource().id(compositeId);
        r.setAttributes(c);
        return r;
    }

    @Test
    void asyncContexts_listByType_runsLambda() throws Exception {
        ContextsApi mockApi = mock(ContextsApi.class);
        ContextsClient sync = newCtxSync(mockApi);
        ContextListResponse resp = new ContextListResponse();
        resp.setData(List.of(ctxResource("user:u1")));
        when(mockApi.listContexts(eq("user"), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(resp);

        AsyncContextsClient async = new AsyncContextsClient(sync, exec);
        List<ContextEntity> result = async.list("user").get(5, TimeUnit.SECONDS);
        assertEquals(1, result.size());
        assertEquals("user", result.get(0).getType());
        assertEquals("u1", result.get(0).getKey());
    }

    @Test
    void asyncContexts_getByCompositeId_runsLambda() throws Exception {
        ContextsApi mockApi = mock(ContextsApi.class);
        ContextsClient sync = newCtxSync(mockApi);
        ContextResponse resp = new ContextResponse();
        resp.setData(ctxResource("user:u1"));
        when(mockApi.getContext("user:u1")).thenReturn(resp);

        AsyncContextsClient async = new AsyncContextsClient(sync, exec);
        ContextEntity e = async.get("user:u1").get(5, TimeUnit.SECONDS);
        assertEquals("user", e.getType());
        assertEquals("u1", e.getKey());
    }

    @Test
    void asyncContexts_getByTypeAndKey_runsLambda() throws Exception {
        ContextsApi mockApi = mock(ContextsApi.class);
        ContextsClient sync = newCtxSync(mockApi);
        ContextResponse resp = new ContextResponse();
        resp.setData(ctxResource("user:u1"));
        when(mockApi.getContext("user:u1")).thenReturn(resp);

        AsyncContextsClient async = new AsyncContextsClient(sync, exec);
        ContextEntity e = async.get("user", "u1").get(5, TimeUnit.SECONDS);
        assertEquals("user", e.getType());
        assertEquals("u1", e.getKey());
    }

}
