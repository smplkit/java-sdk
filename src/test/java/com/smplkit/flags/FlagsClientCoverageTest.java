package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.Context;
import com.smplkit.Helpers;
import com.smplkit.errors.SmplException;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import com.smplkit.internal.generated.flags.model.ResponseFlag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional coverage for FlagsClient: newStringFlag/newNumberFlag/newJsonFlag variants,
 * register overloads, flushContexts, stats, onChange scoped listener, Helpers class,
 * LRU cache, isTruthy edge cases, parseInstant, and error paths.
 */
class FlagsClientCoverageTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private ContextsApi mockContextsApi;
    private FlagsClient client;
    private static final String FLAG_ID = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextsApi, Mockito.mock(HttpClient.class),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
    }

    // --- newStringFlag variants ---

    @Test
    void newStringFlag_withNameAndDescription() {
        Flag<String> flag = client.newStringFlag("color", "red", "Color", "Pick a color");
        assertNull(flag.getId());
        assertEquals("STRING", flag.getType());
        assertEquals("Color", flag.getName());
        assertEquals("Pick a color", flag.getDescription());
        assertEquals("red", flag.getDefault());
    }

    @Test
    void newStringFlag_withValues() {
        List<Map<String, Object>> values = List.of(
                Map.of("name", "Red", "value", "red"),
                Map.of("name", "Blue", "value", "blue")
        );
        Flag<String> flag = client.newStringFlag("color", "red", "Color", null, values);
        assertEquals(2, flag.getValues().size());
    }

    @Test
    void newStringFlag_withoutName_usesKeyToDisplayName() {
        Flag<String> flag = client.newStringFlag("bg-color", "red");
        assertEquals("Bg Color", flag.getName());
    }

    // --- newNumberFlag variants ---

    @Test
    void newNumberFlag_minimal() {
        Flag<Number> flag = client.newNumberFlag("rate-limit", 100);
        assertNull(flag.getId());
        assertEquals("NUMERIC", flag.getType());
        assertEquals(100, flag.getDefault());
    }

    @Test
    void newNumberFlag_withNameAndDescription() {
        Flag<Number> flag = client.newNumberFlag("rate-limit", 100, "Rate Limit", "Max requests");
        assertEquals("Rate Limit", flag.getName());
        assertEquals("Max requests", flag.getDescription());
    }

    @Test
    void newNumberFlag_withValues() {
        List<Map<String, Object>> values = List.of(
                Map.of("name", "Low", "value", 100),
                Map.of("name", "High", "value", 1000)
        );
        Flag<Number> flag = client.newNumberFlag("rate-limit", 100, null, null, values);
        assertEquals(2, flag.getValues().size());
        // name from key
        assertEquals("Rate Limit", flag.getName());
    }

    // --- newJsonFlag variants ---

    @Test
    void newJsonFlag_minimal() {
        Flag<Object> flag = client.newJsonFlag("config", Map.of("a", 1));
        assertNull(flag.getId());
        assertEquals("JSON", flag.getType());
    }

    @Test
    void newJsonFlag_withNameAndDescription() {
        Flag<Object> flag = client.newJsonFlag("config", Map.of(), "Config", "Feature config");
        assertEquals("Config", flag.getName());
        assertEquals("Feature config", flag.getDescription());
    }

    @Test
    void newJsonFlag_withValues() {
        List<Map<String, Object>> values = List.of(Map.of("name", "Preset A", "value", Map.of("x", 1)));
        Flag<Object> flag = client.newJsonFlag("config", Map.of(), null, null, values);
        assertEquals(1, flag.getValues().size());
    }

    // --- register(Context...) and register(List<Context>) ---

    @Test
    void register_varArgs() throws ApiException {
        setupFlagStore("f", "BOOLEAN", false, Map.of());
        Context c1 = new Context("user", "u-1", Map.of());
        Context c2 = new Context("user", "u-2", Map.of());
        assertDoesNotThrow(() -> client.register(c1, c2));
    }

    @Test
    void register_listOverload() throws ApiException {
        setupFlagStore("f", "BOOLEAN", false, Map.of());
        List<Context> list = List.of(
                new Context("user", "u-1", Map.of()),
                new Context("account", "acme", Map.of())
        );
        assertDoesNotThrow(() -> client.register(list));
    }

    @Test
    void register_duplicateContextIgnored() throws ApiException {
        setupFlagStore("f", "BOOLEAN", false, Map.of());
        Context ctx = new Context("user", "u-1", Map.of("plan", "premium"));
        client.register(ctx);
        client.register(ctx); // same type:key -- should not add again
    }

    // --- flushContexts ---

    @Test
    void flushContexts_sendsBufferedContexts() throws Exception {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        client.register(new Context("user", "u-1", Map.of("plan", "premium")));
        client.flushContexts();

        verify(mockContextsApi).bulkRegisterContexts(any(ContextBulkRegister.class));
    }

    @Test
    void flushContexts_noOp_whenEmpty() throws Exception {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of());

        client.flushContexts();

        verify(mockContextsApi, never()).bulkRegisterContexts(any(ContextBulkRegister.class));
    }

    @Test
    void flushContexts_handlesException() throws Exception {
        when(mockContextsApi.bulkRegisterContexts(any(ContextBulkRegister.class)))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "fail"));

        setupFlagStore("feature-x", "BOOLEAN", false, Map.of());
        client.register(new Context("user", "u-1", Map.of()));

        assertDoesNotThrow(() -> client.flushContexts());
    }

    // --- stats ---

    @Test
    void stats_tracksCacheHitsAndMisses() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        List<Context> ctx = List.of();

        handle.get(ctx); // miss
        handle.get(ctx); // hit
        handle.get(ctx); // hit

        FlagStats stats = client.stats();
        assertEquals(2, stats.cacheHits());
        assertEquals(1, stats.cacheMisses());
    }

    @Test
    void stats_zeroWhenEmpty() {
        FlagStats stats = client.stats();
        assertEquals(0, stats.cacheHits());
        assertEquals(0, stats.cacheMisses());
    }

    // --- onChange(key, listener) scoped listener ---

    @Test
    void onChange_keyScopedListener() throws ApiException {
        setupFlagStore("target-flag", "BOOLEAN", false, Map.of());

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        AtomicInteger otherCount = new AtomicInteger();
        client.onChange("target-flag", received::set);
        client.onChange("other-flag", e -> otherCount.incrementAndGet());

        // Refresh with only target-flag in store -- other-flag listener should NOT fire
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(
                makeFlagListResponse(FLAG_ID, "target-flag", "BOOLEAN", false, Map.of()));
        client.refresh();

        assertNotNull(received.get());
        assertEquals("target-flag", received.get().key());
        assertEquals(0, otherCount.get());
    }

    // --- Helpers.keyToDisplayName via FlagsClient factory methods ---

    @Test
    void keyToDisplayName_hyphenated() {
        assertEquals("My Feature Flag", Helpers.keyToDisplayName("my-feature-flag"));
    }

    @Test
    void keyToDisplayName_underscored() {
        assertEquals("My Feature Flag", Helpers.keyToDisplayName("my_feature_flag"));
    }

    @Test
    void keyToDisplayName_mixed() {
        assertEquals("Checkout V2 Feature", Helpers.keyToDisplayName("checkout-v2_feature"));
    }

    @Test
    void keyToDisplayName_singleWord() {
        assertEquals("Feature", Helpers.keyToDisplayName("feature"));
    }

    // --- parseInstant ---

    @Test
    void parseInstant_validIsoString_returnsInstant() {
        assertNotNull(FlagsClient.parseInstant("2026-01-01T00:00:00Z"));
    }

    @Test
    void parseInstant_invalidString_returnsNull() {
        assertNull(FlagsClient.parseInstant("not-a-date"));
    }

    @Test
    void parseInstant_null_returnsNull() {
        assertNull(FlagsClient.parseInstant(null));
    }

    @Test
    void parseInstant_nonString_returnsNull() {
        assertNull(FlagsClient.parseInstant(12345));
    }

    // --- isTruthy edge cases ---

    @Test
    void evaluation_truthyString() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "String result",
                                "logic", Map.of("cat", List.of("true")),
                                "value", true
                        ))
                )
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void evaluation_falsyEmptyString() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", true,
                        "rules", List.of(Map.of(
                                "description", "Empty string",
                                "logic", Map.of("cat", List.of("")),
                                "value", false
                        ))
                )
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        // Empty string is falsy -- rule doesn't match -- env default (true)
        assertTrue(handle.get(List.of()));
    }

    @Test
    void evaluation_truthyObject() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Merge returns array",
                                "logic", Map.of("merge", List.of(List.of(1), List.of(2))),
                                "value", true
                        ))
                )
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void evaluation_truthyNumber() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns 1",
                                "logic", Map.of("+", List.of(1, 0)),
                                "value", true
                        ))
                )
        ));
        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    // --- Type coercion edge cases ---

    @Test
    void booleanHandle_rejectsNonBoolean() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns string",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", "not-a-boolean"
                        ))
                )
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertFalse(handle.get(List.of()));
    }

    @Test
    void numberHandle_rejectsBoolean() throws ApiException {
        setupFlagStore("limit", "NUMERIC", 100, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns boolean",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", true
                        ))
                )
        ));

        Flag<Number> handle = client.numberFlag("limit", 100);
        assertEquals(100, handle.get(List.of()));
    }

    @Test
    void numberHandle_rejectsNonNumericString() throws ApiException {
        setupFlagStore("limit", "NUMERIC", 100, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns string",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", "not-a-number"
                        ))
                )
        ));

        Flag<Number> handle = client.numberFlag("limit", 100);
        assertEquals(100, handle.get(List.of()));
    }

    @Test
    void jsonHandle_returnsRawValue() throws ApiException {
        Map<String, Object> config = Map.of("limit", 100, "enabled", true);
        setupFlagStore("config", "JSON", config, Map.of(
                "staging", Map.of("enabled", true, "default", config)
        ));

        Flag<Object> handle = client.jsonFlag("config", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handle.get(List.of());
        assertEquals(100, result.get("limit"));
    }

    @Test
    void stringHandle_rejectsNonString() throws ApiException {
        setupFlagStore("color", "STRING", "red", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns int",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", 42
                        ))
                )
        ));

        Flag<String> handle = client.stringFlag("color", "red");
        assertEquals("red", handle.get(List.of()));
    }

    // --- handleGet null raw returns code default ---

    @Test
    void handleGet_nullRawReturnsCodeDefault() throws ApiException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "feature-x");
        attrs.put("name", "Feature X");
        attrs.put("type", "BOOLEAN");
        attrs.put("default", null);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of("staging", Map.of("enabled", true)));
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of("id", FLAG_ID, "type", "flag", "attributes", attrs))),
                FlagListResponse.class));
        client._connectInternal();

        Flag<String> handle = client.stringFlag("feature-x", "fallback");
        assertEquals("fallback", handle.get(List.of()));
    }

    // --- parseListResponse null data returns empty ---

    @Test
    void parseListResponse_nullData_returnsEmpty() throws ApiException {
        FlagListResponse resp = new FlagListResponse();
        resp.setData(null);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(resp);
        List<Flag<?>> result = client.list();
        assertTrue(result.isEmpty());
    }

    // --- list() ApiException ---

    @Test
    void list_apiException_throwsSmplException() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull()))
                .thenThrow(new ApiException(500, "Server Error"));
        assertThrows(SmplException.class, () -> client.list());
    }

    // --- _createFlag with environments ---

    @Test
    void createFlag_withEnvironmentsAndDescription() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of(
                                "staging", Map.of("enabled", true, "default", false)
                        ), "description", "A test flag"
                )
        )), FlagResponse.class);
        when(mockApi.createFlag(any(ResponseFlag.class))).thenReturn(response);

        Flag<Boolean> flag = client.newBooleanFlag("my-flag", false, "My Flag", "A test flag");
        flag.setEnvironmentEnabled("staging", true);
        flag.save();

        assertEquals(FLAG_ID, flag.getId());
        verify(mockApi).createFlag(any(ResponseFlag.class));
    }

    // --- Context batch flush threshold ---

    @Test
    void evaluateHandle_triggersEagerFlush_whenBatchFull() throws Exception {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);

        // Register enough contexts to exceed the batch flush size (100)
        for (int i = 0; i < 101; i++) {
            client.register(new Context("user", "u-" + i, Map.of("plan", "free")));
        }

        handle.get(List.of(new Context("user", "u-new", Map.of("plan", "new"))));
        Thread.sleep(100);
    }

    // --- flushContextsSafe catches exception ---

    @Test
    void flushContextsSafe_catchesException() throws Exception {
        when(mockContextsApi.bulkRegisterContexts(any(ContextBulkRegister.class)))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "connection refused"));

        setupFlagStore("feature-x", "BOOLEAN", false, Map.of("staging", Map.of("enabled", true)));

        client.register(new Context("user", "u-1", Map.of("plan", "premium")));

        assertDoesNotThrow(() -> client.disconnect());
    }

    // --- get() null coercion path (Flag line 111) ---

    @Test
    void get_nullDefaultValue_returnsNull() throws ApiException {
        // Flag with null default — evaluation returns null, get() handles it
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "null-flag");
        attrs.put("name", "Null Flag");
        attrs.put("type", "STRING");
        attrs.put("default", null);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of("id", FLAG_ID, "type", "flag", "attributes", attrs))),
                FlagListResponse.class));
        client._connectInternal();

        Flag<String> handle = client.stringFlag("null-flag", null);
        // _evaluateHandle returns null (the defaultValue), get() null check returns defaultValue (null)
        assertNull(handle.get(List.of()));
    }

    // --- delete() ApiException path ---

    @Test
    void delete_apiException_throwsSmplException() throws ApiException {
        // Setup a flag to be found by key
        when(mockApi.listFlags(eq("my-flag"), isNull())).thenReturn(
                makeFlagListResponse(FLAG_ID, "my-flag", "BOOLEAN", false, Map.of()));
        // Then make deleteFlag throw
        doThrow(new ApiException(500, "Server Error"))
                .when(mockApi).deleteFlag(UUID.fromString(FLAG_ID));

        assertThrows(SmplException.class, () -> client.delete("my-flag"));
    }

    // --- _updateFlag with description ---

    @Test
    void updateFlag_withDescription_setsDescription() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "description", "Updated desc"
                )
        )), FlagResponse.class);
        when(mockApi.updateFlag(eq(UUID.fromString(FLAG_ID)), any(ResponseFlag.class)))
                .thenReturn(response);

        Flag<Boolean> flag = client.newBooleanFlag("my-flag", false, "My Flag", "Updated desc");
        flag.setId(FLAG_ID);
        flag.save();

        verify(mockApi).updateFlag(eq(UUID.fromString(FLAG_ID)), any(ResponseFlag.class));
    }

    // --- evaluateFlag returns flagDefault when no environments ---

    @Test
    void evaluateFlag_noEnvironments_returnsFlagDefault() throws ApiException {
        // Flag with no environments at all
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(
                makeFlagListResponse(FLAG_ID, "simple-flag", "BOOLEAN", true, Map.of()));
        client._connectInternal();

        Flag<Boolean> handle = client.booleanFlag("simple-flag", false);
        // Flag store has default=true, no environment data for "staging" -> returns flagDefault (true)
        assertTrue(handle.get(List.of()));
    }

    // --- buildEnvironments with default value ---

    @Test
    void createFlag_withEnvironmentDefault_includesDefaultInPayload() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(),
                        "environments", Map.of("staging", Map.of("enabled", true, "default", true))
                )
        )), FlagResponse.class);
        when(mockApi.createFlag(any(ResponseFlag.class))).thenReturn(response);

        Flag<Boolean> flag = client.newBooleanFlag("my-flag", false);
        flag.setEnvironmentEnabled("staging", true);
        flag.setEnvironmentDefault("staging", true);
        flag.save();

        verify(mockApi).createFlag(any(ResponseFlag.class));
    }

    // --- Helpers ---

    private void setupFlagStore(String key, String type, Object defaultValue,
                                 Map<String, Object> environments) throws ApiException {
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(
                makeFlagListResponse(FLAG_ID, key, type, defaultValue, environments));
        client._connectInternal();
    }

    private static FlagListResponse makeFlagListResponse(String id, String key, String type,
                                                           Object defaultValue,
                                                           Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", key);
        attrs.put("name", key);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        Map<String, Object> map = Map.of("data", List.of(Map.of(
                "id", id, "type", "flag", "attributes", attrs)));
        return OBJECT_MAPPER.convertValue(map, FlagListResponse.class);
    }
}
