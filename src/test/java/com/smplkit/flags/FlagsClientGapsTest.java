package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.SharedWebSocket;
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
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Closes the remaining {@link FlagsClient} line-coverage gaps:
 * <ul>
 *   <li>{@code ensureConnected}: the deferred-start hook, the retry-scheduled
 *       early-return, and the inner double-checked-locking guard.</li>
 *   <li>{@code ensureWs}: the null-return when no app transport is owned.</li>
 *   <li>{@code thresholdFlush}: the catch block on an eager-flush failure.</li>
 *   <li>{@code refresh} / {@code handleFlagsChanged}: the changed-key stream and
 *       the {@code flags_changed} fetch-failure catch and not-connected guard.</li>
 *   <li>{@code _evaluateHandle}: the cached-null-sentinel hit branch.</li>
 *   <li>{@code isTruthy}: null and numeric branches.</li>
 *   <li>{@code handleFlagDeleted}: delete of an unknown key (not-in-store).</li>
 *   <li>{@code parseFlagData}: the flat (no {@code attributes}) shape and
 *       null id/name/type fallbacks.</li>
 *   <li>{@code buildEnvironments}: a rule with no {@code logic} key.</li>
 * </ul>
 */
class FlagsClientGapsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private ContextsApi mockContextsApi;
    private FlagsClient client;

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextsApi, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
        // Inject a mock WS so a successful connect never dials a real socket.
        client.setSharedWs(Mockito.mock(SharedWebSocket.class));
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    // ------------------------------------------------------------------
    // ensureConnected: deferred-start hook (line 868)
    // ------------------------------------------------------------------

    @Test
    void ensureConnected_runsDeferredStartHook() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyList());
        AtomicInteger hookCalls = new AtomicInteger();
        client.setEnsureStarted(hookCalls::incrementAndGet);

        client.ensureConnected();

        assertTrue(client.isConnected());
        assertEquals(1, hookCalls.get(), "deferred-start hook must run on first connect");
    }

    // ------------------------------------------------------------------
    // ensureConnected: retry-scheduled early return (line 873)
    // ------------------------------------------------------------------

    @Test
    void ensureConnected_whenRetryScheduled_returnsEarly() throws ApiException {
        // First connect fails the flush → retryScheduled becomes true.
        when(mockApi.bulkRegisterFlags(any()))
                .thenThrow(new ApiException(500, "Service Unavailable"));
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyList());

        client.register(new FlagDeclaration("feature-x", "BOOLEAN", false), false);
        client.ensureConnected();
        assertTrue(client.isRetryScheduled());

        // Second call, synchronously and immediately (well within the 1s backoff
        // before the scheduled retry could fire): retryScheduled is still true, so
        // ensureConnected hits the inner `if (retryScheduled) return;` and exits
        // without connecting.
        client.ensureConnected();

        assertFalse(client.isConnected());
        assertTrue(client.isRetryScheduled());
        // Cancel the pending background retry so it can't keep hammering the mock
        // after the test asserts.
        client.disconnect();
    }

    // ------------------------------------------------------------------
    // ensureConnected: inner double-checked-locking guard (line 872)
    // A second thread that passed the outer `connected` check while it was false
    // blocks on the lock, then sees connected==true once it acquires it.
    // ------------------------------------------------------------------

    @Test
    void ensureConnected_innerDoubleCheck_returnsWhenConnectedUnderLock() throws Exception {
        CountDownLatch flushEntered = new CountDownLatch(1); // T1 is inside flushFlags holding the lock
        CountDownLatch releaseFlush = new CountDownLatch(1); // main lets T1 finish flushing

        when(mockApi.bulkRegisterFlags(any())).thenAnswer(inv -> {
            flushEntered.countDown();
            assertTrue(releaseFlush.await(5, TimeUnit.SECONDS), "flush gate must open");
            return new FlagBulkResponse();
        });
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyList());

        // A pending declaration so flushFlags actually calls bulkRegisterFlags
        // (and therefore parks inside the synchronized block holding connectLock).
        client.register(new FlagDeclaration("feature-x", "BOOLEAN", false), false);

        Thread t1 = new Thread(client::ensureConnected, "connect-1");
        t1.start();

        // Wait until T1 holds connectLock (parked inside flushFlags).
        assertTrue(flushEntered.await(5, TimeUnit.SECONDS), "T1 must enter flushFlags");
        assertFalse(client.isConnected(), "still connecting; outer guard sees connected==false");

        // T2 enters ensureConnected: passes the outer (connected==false) check,
        // then blocks acquiring connectLock that T1 holds.
        Thread t2 = new Thread(client::ensureConnected, "connect-2");
        t2.start();
        // Give T2 a chance to reach the synchronized block and block on the monitor.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t2.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.BLOCKED, t2.getState(),
                "T2 must be blocked on connectLock while T1 holds it");

        // Let T1 finish: it sets connected=true and releases the lock; T2 then
        // acquires the lock, hits the inner `if (connected) return;`, and exits.
        releaseFlush.countDown();
        t1.join(5_000);
        t2.join(5_000);

        assertTrue(client.isConnected());
        // The whole sequence performed exactly one successful flush.
        verify(mockApi, times(1)).bulkRegisterFlags(any());
    }

    // ------------------------------------------------------------------
    // ensureWs: returns null when no shared WS and no owned app transport
    // (line 923)
    // ------------------------------------------------------------------

    @Test
    void ensureConnected_withoutAppTransportOrSharedWs_skipsWsAndConnects() throws ApiException {
        // appBaseUrl == null and no shared WS injected → ensureWs() returns null,
        // so the WS-handler registration block is skipped but connect succeeds.
        FlagsApi api = Mockito.mock(FlagsApi.class);
        when(api.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyList());
        try (FlagsClient noWs = new FlagsClient(api, Mockito.mock(ContextsApi.class),
                HttpClient.newHttpClient(), "test-key",
                "https://flags.smplkit.com", null /* appBaseUrl */, Duration.ofSeconds(5))) {
            noWs.setEnvironment("staging");

            noWs.ensureConnected();

            assertTrue(noWs.isConnected());
        }
    }

    // ------------------------------------------------------------------
    // thresholdFlush: catch block on eager-flush failure (lines 742-743)
    // ------------------------------------------------------------------

    @Test
    void thresholdFlush_eagerFlushFailure_isSwallowed() throws Exception {
        // The eager flush thread (spawned when the buffer crosses 50) calls
        // flushOrThrow, which raises a mapped exception; thresholdFlush catches it.
        CountDownLatch flushAttempted = new CountDownLatch(1);
        when(mockApi.bulkRegisterFlags(any())).thenAnswer(inv -> {
            flushAttempted.countDown();
            throw new ApiException(500, "Service Unavailable");
        });

        // 50 declarations cross the batch flush threshold and spawn the eager thread.
        java.util.List<FlagDeclaration> decls = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            decls.add(new FlagDeclaration("eager-" + i, "BOOLEAN", false));
        }
        assertDoesNotThrow(() -> client.register(decls, false));

        assertTrue(flushAttempted.await(5, TimeUnit.SECONDS),
                "eager flush should have attempted the bulk register");
        // Buffer is retained because the flush failed (committed only on success).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.pendingCount() != 50 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(50, client.pendingCount(), "failed eager flush retains the buffer");
    }

    // ------------------------------------------------------------------
    // refresh: changed-key stream (lines 976-979)
    // ------------------------------------------------------------------

    @Test
    void refresh_changedContent_firesGlobalListenerWithChangedKey() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(list("flag-1", "BOOLEAN", false));
        client.ensureConnected();

        AtomicReference<FlagChangeEvent> global = new AtomicReference<>();
        client.onChange(global::set);

        // refresh returns changed content → anyChanged=true → firstKey stream runs.
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(list("flag-1", "BOOLEAN", true));
        client.refresh();

        assertNotNull(global.get());
        assertEquals("manual", global.get().source());
        assertEquals("flag-1", global.get().id());
    }

    // ------------------------------------------------------------------
    // handleFlagsChanged: not-connected guard (line 1369)
    // ------------------------------------------------------------------

    @Test
    void flagsChanged_whenNotConnected_isNoOp() {
        // No ensureConnected() — handleFlagsChanged should return at the guard
        // and never touch the API.
        assertDoesNotThrow(client::simulateFlagsChanged);
        assertFalse(client.isConnected());
    }

    // ------------------------------------------------------------------
    // handleFlagsChanged: fetchAllFlags failure catch (lines 1378-1380)
    // ------------------------------------------------------------------

    @Test
    void flagsChanged_fetchFailure_isSwallowed() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(list("flag-1", "BOOLEAN", false));
        client.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        // The flags_changed handler re-fetches; make that fetch throw.
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenThrow(new ApiException(500, "Service Unavailable"));

        assertDoesNotThrow(client::simulateFlagsChanged);
        assertEquals(0, count.get(), "no listeners fire when the refresh fetch fails");
    }

    // ------------------------------------------------------------------
    // handleFlagsChanged: changed-key stream for the global event (line 1402)
    // ------------------------------------------------------------------

    @Test
    void flagsChanged_changedContent_firesGlobalWithChangedKey() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(list("flag-1", "BOOLEAN", false));
        client.ensureConnected();

        AtomicReference<FlagChangeEvent> global = new AtomicReference<>();
        client.onChange(global::set);

        // Re-fetch returns changed content → anyChanged=true → the firstKey stream
        // (with its filter lambda) runs to pick a changed key for the global event.
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(list("flag-1", "BOOLEAN", true));
        client.simulateFlagsChanged();

        assertNotNull(global.get());
        assertEquals("websocket", global.get().source());
        assertEquals("flag-1", global.get().id());
    }

    // ------------------------------------------------------------------
    // _evaluateHandle: cached null-sentinel hit branch (line 1065)
    // ------------------------------------------------------------------

    @Test
    void evaluateHandle_cachedNullSentinel_returnsDefaultOnHit() throws ApiException {
        // Flag exists but its environment yields a null value, so the first
        // evaluation caches CACHE_NULL_SENTINEL. The second identical evaluation
        // is a cache hit that returns the handle default via the sentinel branch.
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", "Null Flag");
        attrs.put("type", "STRING");
        attrs.put("default", null);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of()); // no env match → evaluateFlag returns null default
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(OBJECT_MAPPER.convertValue(
                        Map.of("data", List.of(Map.of("id", "null-flag", "type", "flag", "attributes", attrs))),
                        FlagListResponse.class));
        client.ensureConnected();

        Flag<String> handle = client.stringFlag("null-flag", "fallback");
        assertEquals("fallback", handle.get(List.of())); // miss → stores sentinel
        assertEquals("fallback", handle.get(List.of())); // hit → sentinel branch

        FlagStats stats = client.stats();
        assertEquals(1, stats.cacheHits(), "second eval must be a cache hit");
        assertEquals(1, stats.cacheMisses());
    }

    // ------------------------------------------------------------------
    // internal context buffer LRU removeEldestEntry runs on put (line 169)
    // ------------------------------------------------------------------

    @Test
    void evaluatingWithContext_putsIntoInternalContextBuffer() throws ApiException {
        // No shared context buffer is wired, so observing a context falls back to
        // the internal LinkedHashMap whose put() invokes removeEldestEntry.
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of(new com.smplkit.Context("user", "u-1", Map.of("plan", "pro")))));
    }

    // ------------------------------------------------------------------
    // isTruthy: null and numeric branches (lines 1131, 1133)
    // ------------------------------------------------------------------

    @Test
    void evaluation_logicReturnsNull_ruleDoesNotMatch() throws ApiException {
        // An `if` with a false condition and no else-branch resolves to null →
        // isTruthy(null)=false (line 1131) → rule skipped → env default served.
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", true,
                        "rules", List.of(Map.of(
                                "description", "Null logic result",
                                "logic", Map.of("if", List.of(false, true)),
                                "value", false)))));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()), "null logic result must not match the rule");
    }

    @Test
    void evaluation_logicReturnsNonZeroNumber_ruleMatches() throws ApiException {
        // JSON Logic returns a non-zero Number → isTruthy Number branch → true.
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns 5",
                                "logic", Map.of("+", List.of(2, 3)),
                                "value", true)))));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void evaluation_logicReturnsZeroNumber_ruleDoesNotMatch() throws ApiException {
        // Number branch with a zero result → isTruthy returns false.
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", true,
                        "rules", List.of(Map.of(
                                "description", "Returns 0",
                                "logic", Map.of("-", List.of(3, 3)),
                                "value", false)))));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()), "zero numeric logic result must not match");
    }

    // ------------------------------------------------------------------
    // handleFlagDeleted: delete of an unknown key (line 1361)
    // ------------------------------------------------------------------

    @Test
    void flagDeleted_unknownKey_isNoOp() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(list("known-flag", "BOOLEAN", false));
        client.ensureConnected();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        // Key not in the store → existed=false → early return, no listener fires.
        client.simulateFlagDeleted("never-seen");

        assertEquals(0, count.get(), "deleting an unknown key fires no listeners");
    }

    // ------------------------------------------------------------------
    // parseFlagData: flat shape (no "attributes") + null id/name/type
    // (lines 1460, 1472-1473)
    // ------------------------------------------------------------------

    @Test
    void parseSingleResponse_flatShape_usesDataAsAttributes() throws ApiException {
        // A FlagResource whose `attributes` envelope is null: parseFlagData sees
        // attrs==null (line 1460) and falls back to reading fields off `data`.
        com.smplkit.internal.generated.flags.model.FlagResource resource =
                new com.smplkit.internal.generated.flags.model.FlagResource()
                        .id("flat-flag")
                        .type(com.smplkit.internal.generated.flags.model.FlagResource.TypeEnum.FLAG);
        // attributes deliberately left null → serialized data map has no usable
        // "attributes" object, so parseFlagData uses the data object itself.
        FlagResponse resp = new FlagResponse().data(resource);
        when(mockApi.getFlag("flat-flag")).thenReturn(resp);

        Flag<?> flag = client.get("flat-flag");
        assertEquals("flat-flag", flag.getId());
        // type lives at the data level in the flat shape ("flag" resource type)
        assertEquals("flag", flag.getType());
    }

    @Test
    void parseSingleResponse_missingIdNameType_fallBackToEmptyStrings() throws ApiException {
        // A FlagResource with neither id nor attributes: parseFlagData falls back
        // to `data` (line 1460) and every field is missing → the three ternary
        // fallbacks (`x != null ? x : ""`) all resolve to the empty string.
        com.smplkit.internal.generated.flags.model.FlagResource resource =
                new com.smplkit.internal.generated.flags.model.FlagResource();
        // no id, no type, no attributes
        FlagResponse resp = new FlagResponse().data(resource);
        when(mockApi.getFlag("ghost")).thenReturn(resp);

        Flag<?> flag = client.get("ghost");
        assertEquals("", flag.getId(), "missing id falls back to empty string");
        assertEquals("", flag.getName(), "missing name falls back to empty string");
        assertEquals("", flag.getType(), "missing type falls back to empty string");
    }

    // ------------------------------------------------------------------
    // buildEnvironments: a rule with no "logic" key (line 1511 — Map.of() branch)
    // ------------------------------------------------------------------

    @Test
    void createFlag_ruleWithoutLogic_buildsEmptyLogic() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", "logicless", "type", "flag", "attributes", Map.of(
                        "name", "Logicless", "type", "BOOLEAN", "default", false,
                        "values", List.of(), "environments", Map.of(),
                        "created_at", "2024-06-01T12:00:00Z",
                        "updated_at", "2024-06-01T12:00:00Z"))), FlagResponse.class);
        when(mockApi.createFlag(any())).thenReturn(response);

        Flag<Boolean> flag = client.newBooleanFlag("logicless", false);
        flag.enableRules("staging");
        // A rule map that omits the "logic" key entirely → buildEnvironments hits
        // the `logic != null ? logic : Map.of()` false branch.
        Map<String, Object> ruleNoLogic = new HashMap<>();
        ruleNoLogic.put("environment", "staging");
        ruleNoLogic.put("value", true);
        ruleNoLogic.put("description", "no logic here");
        flag.addRule(ruleNoLogic);

        assertDoesNotThrow(flag::save);
        verify(mockApi).createFlag(any());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void setupFlagStore(String id, String type, Object defaultValue,
                                Map<String, Object> environments) throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(list(id, type, defaultValue, environments));
        client.ensureConnected();
    }

    private static FlagListResponse emptyList() {
        return new FlagListResponse().data(List.of());
    }

    private static FlagListResponse list(String id, String type, Object defaultValue) {
        return list(id, type, defaultValue, Map.of());
    }

    private static FlagListResponse list(String id, String type, Object defaultValue,
                                         Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        return OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of("id", id, "type", "flag", "attributes", attrs))),
                FlagListResponse.class);
    }
}
