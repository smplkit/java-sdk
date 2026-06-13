package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagBulkResponse;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import com.smplkit.flags.types.FlagDeclaration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coverage for {@link AsyncFlagsClient} — the thin async wrapper. Each method
 * delegates to a wired sync {@link FlagsClient} backed by mocked generated
 * {@code *Api}s; the {@link java.util.concurrent.CompletableFuture}s run on an
 * inline executor so the delegation is exercised synchronously and
 * deterministically (no {@code Thread.sleep}).
 */
class AsyncFlagsClientTest {

    private static final Executor INLINE = Runnable::run;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private ContextsApi mockContextsApi;
    private FlagsClient sync;
    private AsyncFlagsClient async;

    @BeforeEach
    void setUp() {
        mockApi = mock(FlagsApi.class);
        mockContextsApi = mock(ContextsApi.class);
        sync = new FlagsClient(mockApi, mockContextsApi, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        sync.setEnvironment("staging");
        async = AsyncFlagsClient.wrap(sync, INLINE);
    }

    @AfterEach
    void tearDown() {
        async.close();
    }

    // -------------------------------------------------------- wrap / accessors

    @Test
    void wrap_exposesSyncAndExecutor() {
        assertSame(sync, async.sync());
        assertSame(INLINE, async.executor());
    }

    @Test
    void wrap_defaultExecutor_usesCommonPool() {
        AsyncFlagsClient a = AsyncFlagsClient.wrap(sync);
        assertSame(sync, a.sync());
        assertNotNull(a.executor());
    }

    @Test
    void create_withApiKey_buildsStandaloneAsync() {
        try (AsyncFlagsClient a = AsyncFlagsClient.create("sk_test")) {
            assertNotNull(a.sync());
            assertNotNull(a.executor());
        }
    }

    @Test
    void create_default_resolvesFromEnv() throws Exception {
        // Inject an API key via the environment so the no-arg factory resolves
        // credentials hermetically (CI has no ~/.smplkit).
        setEnv("SMPLKIT_API_KEY", "sk_async_flags_create");
        try (AsyncFlagsClient a = AsyncFlagsClient.create()) {
            assertNotNull(a.sync());
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(env)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearEnv(String key) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(env)).remove(key);
    }

    // -------------------------------------------------------- factory methods

    @Test
    void factoryMethods_delegateToSync() {
        assertEquals("BOOLEAN", async.newBooleanFlag("b", false).getType());
        assertEquals("BOOLEAN", async.newBooleanFlag("b2", false, "B", "d").getType());
        assertEquals("STRING", async.newStringFlag("s", "x").getType());
        assertEquals("STRING", async.newStringFlag("s2", "x", "S", "d").getType());
        assertEquals("STRING", async.newStringFlag("s3", "x", "S", "d",
                List.of(Map.of("name", "X", "value", "x"))).getType());
        assertEquals("NUMERIC", async.newNumberFlag("n", 1).getType());
        assertEquals("NUMERIC", async.newNumberFlag("n2", 1, "N", "d").getType());
        assertEquals("NUMERIC", async.newNumberFlag("n3", 1, "N", "d",
                List.of(Map.of("name", "One", "value", 1))).getType());
        assertEquals("JSON", async.newJsonFlag("j", Map.of()).getType());
        assertEquals("JSON", async.newJsonFlag("j2", Map.of(), "J", "d").getType());
        assertEquals("JSON", async.newJsonFlag("j3", Map.of(), "J", "d",
                List.of(Map.of("name", "Empty", "value", Map.of()))).getType());
    }

    // -------------------------------------------------------- CRUD (async)

    @Test
    void get_delegates() throws Exception {
        when(mockApi.getFlag("f")).thenReturn(makeResponse("f", "BOOLEAN", false));
        Flag<?> flag = async.get("f").get();
        assertEquals("f", flag.getId());
        verify(mockApi).getFlag("f");
    }

    @Test
    void list_noArgs_delegates() throws Exception {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull())).thenReturn(makeListResponse("f"));
        List<Flag<?>> result = async.list().get();
        assertEquals(1, result.size());
    }

    @Test
    void list_withPagination_delegates() throws Exception {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(10), isNull())).thenReturn(makeListResponse("f"));
        List<Flag<?>> result = async.list(2, 10).get();
        assertEquals(1, result.size());
    }

    @Test
    void delete_delegates() throws Exception {
        async.delete("f").get();
        verify(mockApi).deleteFlag("f");
    }

    // -------------------------------------------------------- discovery buffer

    @Test
    void register_single_andFlush_delegates() throws Exception {
        when(mockApi.bulkRegisterFlags(any())).thenReturn(new FlagBulkResponse());

        async.register(new FlagDeclaration("d1", "BOOLEAN", false));
        assertEquals(1, async.pendingCount());

        async.flush().get();
        verify(mockApi).bulkRegisterFlags(any());
        assertEquals(0, async.pendingCount());
    }

    @Test
    void register_list_delegates() {
        async.register(List.of(
                new FlagDeclaration("d1", "BOOLEAN", false),
                new FlagDeclaration("d2", "STRING", "x")
        ));
        assertEquals(2, async.pendingCount());
    }

    // -------------------------------------------------------- live handles

    @Test
    void typedHandles_delegate() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), isNull())).thenReturn(new FlagListResponse().data(List.of()));

        assertEquals("BOOLEAN", async.booleanFlag("b", false).getType());
        assertEquals("STRING", async.stringFlag("s", "x").getType());
        assertEquals("NUMERIC", async.numberFlag("n", 1).getType());
        assertEquals("JSON", async.jsonFlag("j", Map.of()).getType());
    }

    // -------------------------------------------------------- refresh / stats / onChange

    @Test
    void refresh_delegates() throws Exception {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), isNull())).thenReturn(new FlagListResponse().data(List.of()));
        async.refresh().get();
        assertTrue(sync.isConnected());
    }

    @Test
    void stats_delegates() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), isNull())).thenReturn(new FlagListResponse().data(List.of()));
        FlagStats stats = async.stats();
        assertEquals(0, stats.cacheHits());
    }

    @Test
    void onChange_global_delegates() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), isNull())).thenReturn(makeListResponse("flag-x"));
        sync.ensureConnected();

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        async.onChange(received::set);

        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), isNull())).thenReturn(makeListResponse2("flag-x", true));
        sync.refresh();

        assertNotNull(received.get());
    }

    @Test
    void onChange_keyed_delegates() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), isNull())).thenReturn(makeListResponse("flag-x"));
        sync.ensureConnected();

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        async.onChange("flag-x", received::set);

        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), isNull())).thenReturn(makeListResponse2("flag-x", true));
        sync.refresh();

        assertNotNull(received.get());
        assertEquals("flag-x", received.get().id());
    }

    // -------------------------------------------------------- helpers

    private static FlagResponse makeResponse(String id, String type, Object def) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", def);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        return OBJECT_MAPPER.convertValue(
                Map.of("data", Map.of("id", id, "type", "flag", "attributes", attrs)),
                FlagResponse.class);
    }

    private static FlagListResponse makeListResponse(String id) {
        return makeListResponse2(id, false);
    }

    private static FlagListResponse makeListResponse2(String id, Object def) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", "BOOLEAN");
        attrs.put("default", def);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        return OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of("id", id, "type", "flag", "attributes", attrs))),
                FlagListResponse.class);
    }
}
