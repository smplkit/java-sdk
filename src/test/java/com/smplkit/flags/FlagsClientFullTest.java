package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.Context;
import com.smplkit.Rule;
import com.smplkit.errors.ConflictError;
import com.smplkit.errors.SmplError;
import com.smplkit.errors.NotFoundError;
import com.smplkit.errors.ValidationError;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests for FlagsClient covering Flag.save(), addRule(),
 * environment mutations, disconnect/reconnect, and evaluation scenarios.
 */
class FlagsClientFullTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private ContextsApi mockContextsApi;
    private FlagsClient client;
    private static final String FLAG_ID = "my-flag";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextsApi, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
    }

    // --- Flag.save() create path (id=null -> POST) ---

    @Test
    void flagSave_create_callsPostWhenCreatedAtNull() throws ApiException {
        FlagResponse response = makeResponse(FLAG_ID, "My Flag", "BOOLEAN", false);
        when(mockApi.createFlag(any(FlagResponse.class))).thenReturn(response);

        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false, "My Flag", null);
        assertNull(flag.getCreatedAt());

        flag.save();

        assertEquals(FLAG_ID, flag.getId());
        verify(mockApi).createFlag(any(FlagResponse.class));
        verify(mockApi, never()).updateFlag(any(), any());
    }

    @Test
    void flagSave_create_withValues() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", "color", "type", "flag", "attributes", Map.of(
                        "name", "Color", "type", "STRING",
                        "default", "red", "values", List.of(
                                Map.of("name", "Red", "value", "red"),
                                Map.of("name", "Blue", "value", "blue")
                        ), "environments", Map.of(),
                        "created_at", "2024-06-01T12:00:00Z", "updated_at", "2024-06-01T12:00:00Z"
                )
        )), FlagResponse.class);
        when(mockApi.createFlag(any(FlagResponse.class))).thenReturn(response);

        Flag<String> flag = client.management().newStringFlag("color", "red", "Color", "Pick a color",
                List.of(Map.of("name", "Red", "value", "red"), Map.of("name", "Blue", "value", "blue")));
        flag.save();

        assertEquals("color", flag.getId());
        verify(mockApi).createFlag(any(FlagResponse.class));
    }

    // --- Flag.save() update path (id set -> PUT) ---

    @Test
    void flagSave_update_callsPutWhenCreatedAtSet() throws ApiException {
        FlagResponse response = makeResponse(FLAG_ID, "Updated Flag", "BOOLEAN", false);
        when(mockApi.updateFlag(eq(FLAG_ID), any(FlagResponse.class)))
                .thenReturn(response);

        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false, "My Flag", null);
        flag.setCreatedAt(java.time.Instant.parse("2024-06-01T12:00:00Z"));
        flag.setName("Updated Flag");
        flag.save();

        assertEquals("Updated Flag", flag.getName());
        verify(mockApi).updateFlag(eq(FLAG_ID), any(FlagResponse.class));
        verify(mockApi, never()).createFlag(any());
    }

    // --- Flag.addRule() local mutation + save ---

    @Test
    void flagAddRule_mutatesEnvironmentsLocally() throws ApiException {
        FlagResponse response = makeResponse(FLAG_ID, "My Flag", "BOOLEAN", false);
        when(mockApi.createFlag(any(FlagResponse.class))).thenReturn(response);
        when(mockApi.updateFlag(eq(FLAG_ID), any(FlagResponse.class)))
                .thenReturn(response);

        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);

        Map<String, Object> rule = new Rule("Enterprise only")
                .environment("staging")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();

        flag.addRule(rule);

        // Verify the rule was added locally
        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("staging");
        assertNotNull(envData);
        List<?> rules = (List<?>) envData.get("rules");
        assertEquals(1, rules.size());
    }

    @Test
    void flagAddRule_thenSave_callsCreate() throws ApiException {
        FlagResponse response = makeResponse(FLAG_ID, "My Flag", "BOOLEAN", false);
        when(mockApi.createFlag(any(FlagResponse.class))).thenReturn(response);

        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Map<String, Object> rule = new Rule("Enterprise")
                .environment("staging")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();
        flag.addRule(rule);
        flag.save();

        verify(mockApi).createFlag(any(FlagResponse.class));
    }

    @Test
    void flagAddRule_withoutEnvironmentKey_throws() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);

        Map<String, Object> ruleWithoutEnv = new Rule("No env")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();

        assertThrows(IllegalArgumentException.class, () -> flag.addRule(ruleWithoutEnv));
    }

    // --- Flag.setEnvironmentEnabled() local mutation ---

    @Test
    void flagSetEnvironmentEnabled_mutatesLocally() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setEnvironmentEnabled("production", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("production");
        assertNotNull(envData);
        assertEquals(true, envData.get("enabled"));
    }

    // --- Flag.setEnvironmentDefault() local mutation ---

    @Test
    void flagSetEnvironmentDefault_mutatesLocally() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setEnvironmentDefault("production", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("production");
        assertNotNull(envData);
        assertEquals(true, envData.get("default"));
    }

    // --- Flag.clearRules() local mutation ---

    @Test
    void flagClearRules_clearsRulesLocally() throws ApiException {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Map<String, Object> rule = new Rule("Rule")
                .environment("staging")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();
        flag.addRule(rule);

        flag.clearRules("staging");

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("staging");
        List<?> rules = (List<?>) envData.get("rules");
        assertTrue(rules.isEmpty());
    }

    // --- Disconnect and reconnect behavior ---

    @Test
    void disconnect_clearsState() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        assertTrue(client.isConnected());

        client.disconnect();

        assertFalse(client.isConnected());
    }

    @Test
    void disconnect_thenEval_reconnects() throws ApiException {
        // First connect
        setupList("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));
        client._connectInternal();
        assertTrue(client.isConnected());

        client.disconnect();
        assertFalse(client.isConnected());

        // Re-setup mock for the reconnect
        setupList("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", false)
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", true);
        // get() triggers lazy reconnect; returns env default (false)
        assertFalse(handle.get(List.of()));
        assertTrue(client.isConnected());
    }

    // --- Context evaluation with explicit overrides ---

    @Test
    void contextEvaluation_withExplicitContextOverride() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Premium",
                                "logic", Map.of("==", List.of(Map.of("var", "user.plan"), "premium")),
                                "value", true
                        ))
                )
        ));

        client.setContextProvider(() -> List.of(
                new Context("user", "provider-user", Map.of("plan", "premium"))
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);

        // Explicit context with "free" should NOT match even though provider would
        assertFalse(handle.get(List.of(
                new Context("user", "explicit-user", Map.of("plan", "free"))
        )));
    }

    // --- Multiple flag types evaluation ---

    @Test
    void multipleFlagTypes_evaluateCorrectly() throws ApiException {
        // Set up multiple flags
        FlagListResponse listResponse = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                flagData("bool-flag", "BOOLEAN", false,
                        Map.of("staging", Map.of("enabled", true, "default", true))),
                flagData("str-flag", "STRING", "red",
                        Map.of("staging", Map.of("enabled", true, "default", "blue"))),
                flagData("num-flag", "NUMERIC", 100,
                        Map.of("staging", Map.of("enabled", true, "default", 500)))
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(listResponse);
        client._connectInternal();

        Flag<Boolean> boolHandle = client.booleanFlag("bool-flag", false);
        Flag<String> strHandle = client.stringFlag("str-flag", "red");
        Flag<Number> numHandle = client.numberFlag("num-flag", 100);

        assertTrue(boolHandle.get(List.of()));
        assertEquals("blue", strHandle.get(List.of()));
        assertEquals(500, ((Number) numHandle.get(List.of())).intValue());
    }

    // --- Refresh behavior ---

    @Test
    void refresh_reloadsFlags() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));

        // Change the mock to return different data
        setupList("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", false)
        ));
        client.refresh();

        // Cache was cleared; re-evaluation uses new data
        assertFalse(handle.get(List.of()));
    }

    @Test
    void refresh_firesChangeListeners() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        // Provide different data so the diff fires the global listener
        setupList("feature-x", "BOOLEAN", true, Map.of());
        client.refresh();

        assertTrue(count.get() > 0);
    }

    @Test
    void refresh_firesKeyedChangeListener() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange("feature-x", received::set);

        // Provide different data so the diff fires the keyed listener
        setupList("feature-x", "BOOLEAN", true, Map.of());
        client.refresh();

        assertNotNull(received.get());
        assertEquals("feature-x", received.get().id());
    }

    // --- Error mapping ---

    @Test
    void apiException409_throwsConflictError() throws ApiException {
        when(mockApi.createFlag(any(FlagResponse.class)))
                .thenThrow(new ApiException(409, "Conflict"));

        Flag<Boolean> flag = client.management().newBooleanFlag("dup-key", false);
        assertThrows(ConflictError.class, flag::save);
    }

    @Test
    void apiExceptionGeneric_throwsSmplError() throws ApiException {
        when(mockApi.createFlag(any(FlagResponse.class)))
                .thenThrow(new ApiException(500, "Server Error"));

        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        assertThrows(SmplError.class, flag::save);
    }

    @Test
    void apiException422_onUpdate_throwsValidationError() throws ApiException {
        when(mockApi.updateFlag(any(), any(FlagResponse.class)))
                .thenThrow(new ApiException(422, "Validation Error"));

        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setCreatedAt(java.time.Instant.parse("2024-06-01T12:00:00Z"));
        assertThrows(ValidationError.class, flag::save);
    }

    @Test
    void delete_notFound_throwsNotFoundError() throws ApiException {
        doThrow(new ApiException(404, "Not Found"))
                .when(mockApi).deleteFlag("my-flag");

        assertThrows(NotFoundError.class, () -> client.management().delete("my-flag"));
    }

    // --- Stats ---

    @Test
    void stats_tracksCacheHitsAndMisses() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        List<Context> ctx = List.of();

        handle.get(ctx);  // miss
        handle.get(ctx);  // hit
        handle.get(ctx);  // hit

        FlagStats stats = client.stats();
        assertEquals(2, stats.cacheHits());
        assertEquals(1, stats.cacheMisses());
    }

    @Test
    void stats_whenEmpty() {
        FlagStats stats = client.stats();
        assertEquals(0, stats.cacheHits());
        assertEquals(0, stats.cacheMisses());
    }

    // --- Cache: different contexts get different results ---

    @Test
    void cache_differentContextsGetDifferentResults() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Enterprise",
                                "logic", Map.of("==", List.of(Map.of("var", "user.plan"), "enterprise")),
                                "value", true
                        ))
                )
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);

        assertTrue(handle.get(List.of(new Context("user", "u-1", Map.of("plan", "enterprise")))));
        assertFalse(handle.get(List.of(new Context("user", "u-2", Map.of("plan", "free")))));

        FlagStats stats = client.stats();
        assertEquals(2, stats.cacheMisses());
    }

    // --- onChange listener exception is caught ---

    @Test
    void onChange_listenerExceptionIsCaught() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());
        client.onChange(e -> { throw new RuntimeException("boom"); });

        setupList("feature-x", "BOOLEAN", false, Map.of());
        assertDoesNotThrow(() -> client.refresh());
    }

    // --- disconnect without prior connect ---

    @Test
    void disconnect_whenNotConnected_doesNotThrow() {
        assertDoesNotThrow(() -> client.disconnect());
    }

    // --- Test default constructor ---

    @Test
    void defaultConstructor_returnsDisconnectedClient() {
        FlagsClient testClient = new FlagsClient();
        assertFalse(testClient.isConnected());
        assertEquals(0, testClient.stats().cacheHits());
    }

    // --- Evaluation edge cases ---

    @Test
    void evaluation_firstRuleWins() throws ApiException {
        connectWithFlag("color", "STRING", "red", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(
                                Map.of("description", "R1", "logic", Map.of("==", List.of(1, 1)), "value", "blue"),
                                Map.of("description", "R2", "logic", Map.of("==", List.of(1, 1)), "value", "green")
                        )
                )
        ));
        Flag<String> handle = client.stringFlag("color", "red");
        assertEquals("blue", handle.get(List.of()));
    }

    @Test
    void evaluation_emptyLogicSkipped() throws ApiException {
        connectWithFlag("color", "STRING", "red", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(
                                Map.of("description", "Empty", "logic", Map.of(), "value", "skip"),
                                Map.of("description", "R2", "logic", Map.of("==", List.of(1, 1)), "value", "blue")
                        )
                )
        ));
        Flag<String> handle = client.stringFlag("color", "red");
        assertEquals("blue", handle.get(List.of()));
    }

    @Test
    void evaluation_disabledEnvFallsToFlagDefault() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", false)
        ));
        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertFalse(handle.get(List.of()));
    }

    @Test
    void evaluation_disabledEnvWithEnvDefaultReturnsEnvDefault() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", false, "default", true)
        ));
        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void evaluation_envDefaultWhenNoRuleMatches() throws ApiException {
        connectWithFlag("color", "STRING", "red", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", "yellow",
                        "rules", List.of(
                                Map.of("description", "Never match",
                                        "logic", Map.of("==", List.of(1, 2)),
                                        "value", "blue")
                        )
                )
        ));
        Flag<String> handle = client.stringFlag("color", "red");
        assertEquals("yellow", handle.get(List.of()));
    }

    @Test
    void evaluation_multipleContextTypes() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Enterprise US only",
                                "logic", Map.of("and", List.of(
                                        Map.of("==", List.of(Map.of("var", "user.plan"), "enterprise")),
                                        Map.of("==", List.of(Map.of("var", "account.region"), "us"))
                                )),
                                "value", true
                        ))
                )
        ));

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);

        assertTrue(handle.get(List.of(
                new Context("user", "u-1", Map.of("plan", "enterprise")),
                new Context("account", "acme", Map.of("region", "us"))
        )));

        assertFalse(handle.get(List.of(
                new Context("user", "u-1", Map.of("plan", "enterprise")),
                new Context("account", "acme", Map.of("region", "eu"))
        )));
    }

    @Test
    void evaluation_nullRulesReturnsEnvDefault() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));
        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void evaluation_invalidJsonLogicFallsThrough() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", true,
                        "rules", List.of(
                                Map.of("description", "Bad rule",
                                        "logic", Map.of("invalid_op_xyzzy", List.of(1, 2)),
                                        "value", false)
                        )
                )
        ));
        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void evaluation_nullLogicRuleSkipped() throws ApiException {
        Map<String, Object> ruleWithNullLogic = new HashMap<>();
        ruleWithNullLogic.put("description", "Null logic");
        ruleWithNullLogic.put("logic", null);
        ruleWithNullLogic.put("value", "skip");

        connectWithFlag("color", "STRING", "red", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", "yellow",
                        "rules", List.of(ruleWithNullLogic)
                )
        ));
        Flag<String> handle = client.stringFlag("color", "red");
        assertEquals("yellow", handle.get(List.of()));
    }

    @Test
    void evaluation_numberFlag() throws ApiException {
        connectWithFlag("rate-limit", "NUMERIC", 100, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Premium limit",
                                "logic", Map.of("==", List.of(Map.of("var", "user.plan"), "premium")),
                                "value", 1000
                        ))
                )
        ));

        Flag<Number> handle = client.numberFlag("rate-limit", 100);
        assertEquals(1000, handle.get(List.of(new Context("user", "u-1", Map.of("plan", "premium")))));
        assertEquals(100, handle.get(List.of(new Context("user", "u-2", Map.of("plan", "free")))));
    }

    @Test
    void evaluation_jsonFlag() throws ApiException {
        Map<String, Object> defaultConfig = Map.of("limit", 10);
        Map<String, Object> premiumConfig = Map.of("limit", 100);

        connectWithFlag("config", "JSON", defaultConfig, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Premium config",
                                "logic", Map.of("==", List.of(Map.of("var", "user.plan"), "premium")),
                                "value", premiumConfig
                        ))
                )
        ));

        Flag<Object> handle = client.jsonFlag("config", defaultConfig);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handle.get(
                List.of(new Context("user", "u-1", Map.of("plan", "premium"))));
        assertEquals(100, result.get("limit"));
    }

    // --- register contexts ---

    @Test
    void register_addsContextToBuffer() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());

        Context ctx = new Context("user", "u-123", Map.of("plan", "enterprise"));
        client.register(ctx);
        client.register(ctx); // duplicate should not re-add
    }

    @Test
    void register_listOverload() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());

        List<Context> contexts = List.of(
                new Context("user", "u-1", Map.of("plan", "premium")),
                new Context("user", "u-2", Map.of("plan", "free"))
        );
        client.register(contexts);
    }

    // --- Helpers ---

    private void connectWithFlag(String id, String type, Object defaultValue,
                                  Map<String, Object> environments) throws ApiException {
        setupList(id, type, defaultValue, environments);
        client._connectInternal();
    }

    private void setupList(String id, String type, Object defaultValue,
                            Map<String, Object> environments) throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(
                makeFlagListResponse(id, type, defaultValue, environments));
    }

    private static Map<String, Object> flagData(String id, String type,
                                                  Object defaultValue,
                                                  Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        return Map.of("id", id, "type", "flag", "attributes", attrs);
    }

    private static FlagListResponse makeFlagListResponse(String id, String type,
                                                           Object defaultValue,
                                                           Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        Map<String, Object> map = Map.of("data", List.of(Map.of(
                "id", id, "type", "flag", "attributes", attrs)));
        return OBJECT_MAPPER.convertValue(map, FlagListResponse.class);
    }

    private static FlagResponse makeResponse(String id, String name,
                                               String type, Object defaultValue) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", name);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        attrs.put("created_at", "2024-06-01T12:00:00Z");
        attrs.put("updated_at", "2024-06-01T12:00:00Z");
        Map<String, Object> map = Map.of("data", Map.of(
                "id", id, "type", "flag", "attributes", attrs));
        return OBJECT_MAPPER.convertValue(map, FlagResponse.class);
    }
}
