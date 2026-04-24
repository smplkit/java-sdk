package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.Context;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the {@link Flag} model class.
 */
class FlagTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private FlagsClient client;
    private static final String FLAG_ID = "my-flag";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        client = new FlagsClient(mockApi, null, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
    }

    // --- Constructor and getters ---

    @Test
    void constructor_setsAllFields() {
        Instant now = Instant.now();
        List<Map<String, Object>> values = List.of(Map.of("name", "True", "value", true));
        Map<String, Object> envs = Map.of("staging", Map.of("enabled", true));

        Flag<Boolean> flag = new Flag<>(client, "my-flag", "My Flag", "BOOLEAN", false,
                values, "A test flag", envs, now, now, Boolean.class);

        assertEquals("my-flag", flag.getId());
        assertEquals("My Flag", flag.getName());
        assertEquals("BOOLEAN", flag.getType());
        assertFalse(flag.getDefault());
        assertEquals(1, flag.getValues().size());
        assertEquals("A test flag", flag.getDescription());
        assertNotNull(flag.getEnvironments());
        assertEquals(now, flag.getCreatedAt());
        assertEquals(now, flag.getUpdatedAt());
        assertEquals(Boolean.class, flag.getValueType());
    }

    @Test
    void constructor_nullValues_remainsNull() {
        Flag<Boolean> flag = new Flag<>(client, "my-id", "Name", "BOOLEAN", false,
                null, null, null, null, null, Boolean.class);

        assertNull(flag.getValues(), "null values should remain null (unconstrained)");
        assertNotNull(flag.getEnvironments());
        assertTrue(flag.getEnvironments().isEmpty());
        assertNull(flag.getCreatedAt());
        assertNull(flag.getUpdatedAt());
    }

    // --- Setters for mutable fields ---

    @Test
    void setName_changesName() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setName("Updated Name");
        assertEquals("Updated Name", flag.getName());
    }

    @Test
    void setDescription_changesDescription() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setDescription("New description");
        assertEquals("New description", flag.getDescription());
    }

    @Test
    void setDefault_changesDefault() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setDefault(true);
        assertTrue(flag.getDefault());
    }

    @Test
    void setValues_replacesValues() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        List<Map<String, Object>> newValues = List.of(Map.of("name", "On", "value", true));
        flag.setValues(newValues);
        assertEquals(1, flag.getValues().size());
    }

    @Test
    void setValues_null_becomesNull() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setValues(null);
        assertNull(flag.getValues(), "setting values to null should result in null (unconstrained)");
    }

    @Test
    void setEnvironments_replacesEnvironments() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Map<String, Object> envs = Map.of("prod", Map.of("enabled", true));
        flag.setEnvironments(envs);
        assertEquals(1, flag.getEnvironments().size());
        assertTrue(flag.getEnvironments().containsKey("prod"));
    }

    @Test
    void setEnvironments_null_becomesEmpty() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setEnvironments(null);
        assertTrue(flag.getEnvironments().isEmpty());
    }

    // --- Package-private setters ---

    @Test
    void setId_changesId() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setId("new-id");
        assertEquals("new-id", flag.getId());
    }

    @Test
    void setClient_changesClient() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setClient(null);
        assertNull(flag.getClient());
    }

    @Test
    void setType_changesType() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setType("STRING");
        assertEquals("STRING", flag.getType());
    }

    @Test
    void setCreatedAt_changesCreatedAt() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Instant now = Instant.now();
        flag.setCreatedAt(now);
        assertEquals(now, flag.getCreatedAt());
    }

    @Test
    void setUpdatedAt_changesUpdatedAt() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Instant now = Instant.now();
        flag.setUpdatedAt(now);
        assertEquals(now, flag.getUpdatedAt());
    }

    // --- get() returns default when client is null ---

    @Test
    void get_returnsDefaultWhenClientIsNull() {
        Flag<Boolean> flag = new Flag<>(null, "my-flag", "My Flag", "BOOLEAN", false,
                null, null, null, null, null, Boolean.class);
        assertFalse(flag.get());
    }

    @Test
    void get_withContextsAndNullClient_returnsDefault() {
        Flag<String> flag = new Flag<>(null, "color", "Color", "STRING", "red",
                null, null, null, null, null, String.class);
        assertEquals("red", flag.get(List.of(new Context("user", "u-1", Map.of()))));
    }

    // --- get() no-arg delegates to get(null) ---

    @Test
    void get_noArg_delegatesToGetWithNullContexts() throws ApiException {
        connectWithFlag("simple-flag", "BOOLEAN", false,
                Map.of("staging", Map.of("enabled", true, "default", true)));

        Flag<Boolean> handle = client.booleanFlag("simple-flag", false);
        assertTrue(handle.get()); // no-arg form
    }

    // --- get() with type coercion ---

    @Test
    void get_booleanCoercion_returnsCorrectType() throws ApiException {
        connectWithFlag("bool-flag", "BOOLEAN", false,
                Map.of("staging", Map.of("enabled", true, "default", true)));

        Flag<Boolean> handle = client.booleanFlag("bool-flag", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void get_booleanCoercion_rejectsNonBoolean() throws ApiException {
        connectWithFlag("bool-flag", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns string",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", "yes"
                        ))
                )
        ));

        Flag<Boolean> handle = client.booleanFlag("bool-flag", false);
        assertFalse(handle.get(List.of())); // returns default
    }

    @Test
    void get_stringCoercion_returnsCorrectType() throws ApiException {
        connectWithFlag("str-flag", "STRING", "red",
                Map.of("staging", Map.of("enabled", true, "default", "blue")));

        Flag<String> handle = client.stringFlag("str-flag", "red");
        assertEquals("blue", handle.get(List.of()));
    }

    @Test
    void get_stringCoercion_rejectsNonString() throws ApiException {
        connectWithFlag("str-flag", "STRING", "red", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns number",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", 42
                        ))
                )
        ));

        Flag<String> handle = client.stringFlag("str-flag", "red");
        assertEquals("red", handle.get(List.of())); // returns default
    }

    @Test
    void get_numberCoercion_returnsCorrectType() throws ApiException {
        connectWithFlag("num-flag", "NUMERIC", 100,
                Map.of("staging", Map.of("enabled", true, "default", 500)));

        Flag<Number> handle = client.numberFlag("num-flag", 100);
        assertEquals(500, ((Number) handle.get(List.of())).intValue());
    }

    @Test
    void get_numberCoercion_rejectsBoolean() throws ApiException {
        connectWithFlag("num-flag", "NUMERIC", 100, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns boolean",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", true
                        ))
                )
        ));

        Flag<Number> handle = client.numberFlag("num-flag", 100);
        assertEquals(100, handle.get(List.of())); // returns default
    }

    @Test
    void get_evaluateReturnsNull_returnsDefault() throws ApiException {
        // Flag exists in store but environment is not enabled and has no default,
        // so evaluateFlag returns the flag-level default. The coercion path
        // then returns it. We test here that a null raw value yields the handle default.
        Flag<String> flag = new Flag<>(null, "null-flag", "Null Flag", "STRING", "fallback",
                null, null, null, null, null, String.class);
        assertEquals("fallback", flag.get());
    }

    @Test
    void get_numberCoercion_rejectsNonNumber() throws ApiException {
        connectWithFlag("num-flag", "NUMERIC", 100, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Returns string",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", "not a number"
                        ))
                )
        ));

        Flag<Number> handle = client.numberFlag("num-flag", 100);
        assertEquals(100, handle.get(List.of())); // returns default for non-Number
    }

    @Test
    void get_jsonCoercion_returnsRawObject() throws ApiException {
        Map<String, Object> config = Map.of("limit", 100);
        connectWithFlag("json-flag", "JSON", config,
                Map.of("staging", Map.of("enabled", true, "default", config)));

        Flag<Object> handle = client.jsonFlag("json-flag", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handle.get(List.of());
        assertEquals(100, result.get("limit"));
    }

    // --- save() when id is null calls _createFlag ---

    @Test
    void save_whenCreatedAtNull_callsCreate() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", "new-flag", "type", "flag", "attributes", Map.of(
                        "name", "New Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of(),
                        "created_at", "2024-06-01T12:00:00Z", "updated_at", "2024-06-01T12:00:00Z"
                )
        )), FlagResponse.class);
        when(mockApi.createFlag(any(FlagResponse.class))).thenReturn(response);

        Flag<Boolean> flag = client.management().newBooleanFlag("new-flag", false, "New Flag", null);
        assertNull(flag.getCreatedAt());

        flag.save();

        assertEquals("new-flag", flag.getId());
        verify(mockApi).createFlag(any(FlagResponse.class));
        verify(mockApi, never()).updateFlag(any(), any());
    }

    // --- save() when id is set calls _updateFlag ---

    @Test
    void save_whenCreatedAtSet_callsUpdate() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "name", "Updated", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of(),
                        "created_at", "2024-06-01T12:00:00Z", "updated_at", "2024-06-01T12:00:00Z"
                )
        )), FlagResponse.class);
        when(mockApi.updateFlag(eq(FLAG_ID), any(FlagResponse.class)))
                .thenReturn(response);

        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false, "My Flag", null);
        flag.setCreatedAt(java.time.Instant.parse("2024-06-01T12:00:00Z"));
        flag.setName("Updated");
        flag.save();

        verify(mockApi).updateFlag(eq(FLAG_ID), any(FlagResponse.class));
        verify(mockApi, never()).createFlag(any());
    }

    // --- save() applies response back ---

    @Test
    void save_create_appliesResponseFieldsBack() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", "applied-flag", "type", "flag", "attributes", Map.of(
                        "name", "Applied Flag", "type", "BOOLEAN",
                        "default", true, "values", List.of(Map.of("name", "On", "value", true)),
                        "description", "from server",
                        "environments", Map.of("staging", Map.of("enabled", true)),
                        "created_at", "2024-06-01T12:00:00Z",
                        "updated_at", "2024-06-01T12:00:00Z"
                )
        )), FlagResponse.class);
        when(mockApi.createFlag(any(FlagResponse.class))).thenReturn(response);

        Flag<Boolean> flag = client.management().newBooleanFlag("applied-flag", false, "Local Name", null);
        flag.save();

        assertEquals("applied-flag", flag.getId());
        assertEquals("Applied Flag", flag.getName());
        assertTrue(flag.getDefault());
        assertEquals("from server", flag.getDescription());
        assertNotNull(flag.getCreatedAt());
        assertNotNull(flag.getUpdatedAt());
    }

    @Test
    void save_update_appliesResponseFieldsBack() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "name", "Server Name", "type", "BOOLEAN",
                        "default", true, "values", List.of(), "environments", Map.of(),
                        "description", "server desc",
                        "created_at", "2024-06-01T12:00:00Z", "updated_at", "2024-06-01T12:00:00Z"
                )
        )), FlagResponse.class);
        when(mockApi.updateFlag(eq(FLAG_ID), any(FlagResponse.class)))
                .thenReturn(response);

        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false, "Local Name", null);
        flag.setCreatedAt(java.time.Instant.parse("2024-06-01T12:00:00Z"));
        flag.save();

        assertEquals("Server Name", flag.getName());
        assertTrue(flag.getDefault());
        assertEquals("server desc", flag.getDescription());
    }

    // --- save() with null client throws ---

    @Test
    void save_withNullClient_throwsIllegalState() {
        Flag<Boolean> flag = new Flag<>(null, "my-flag", "My Flag", "BOOLEAN", false,
                null, null, null, null, null, Boolean.class);
        assertThrows(IllegalStateException.class, flag::save);
    }

    // --- addRule() mutates environments locally ---

    @Test
    void addRule_appendsRuleToEnvironment() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Map<String, Object> rule = Map.of(
                "environment", "staging",
                "description", "Enterprise",
                "logic", Map.of("==", List.of(Map.of("var", "user.plan"), "enterprise")),
                "value", true
        );

        flag.addRule(rule);

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("staging");
        assertNotNull(envData);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) envData.get("rules");
        assertEquals(1, rules.size());
        // Environment key should NOT be in the stored rule
        assertFalse(rules.get(0).containsKey("environment"));
    }

    @Test
    void addRule_appendsToExistingRules() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setEnvironmentEnabled("staging", true);

        Map<String, Object> rule1 = Map.of(
                "environment", "staging", "description", "Rule 1",
                "logic", Map.of("==", List.of(1, 1)), "value", true);
        Map<String, Object> rule2 = Map.of(
                "environment", "staging", "description", "Rule 2",
                "logic", Map.of("==", List.of(1, 1)), "value", false);

        flag.addRule(rule1);
        flag.addRule(rule2);

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("staging");
        @SuppressWarnings("unchecked")
        List<?> rules = (List<?>) envData.get("rules");
        assertEquals(2, rules.size());
    }

    @Test
    void addRule_returnsThis_forChaining() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Map<String, Object> rule = Map.of(
                "environment", "staging", "description", "R",
                "logic", Map.of("==", List.of(1, 1)), "value", true);
        assertSame(flag, flag.addRule(rule));
    }

    // --- addRule() without environment key throws ---

    @Test
    void addRule_withoutEnvironmentKey_throwsIllegalArgument() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Map<String, Object> ruleWithoutEnv = Map.of(
                "description", "No env",
                "logic", Map.of("==", List.of(1, 1)),
                "value", true
        );

        assertThrows(IllegalArgumentException.class, () -> flag.addRule(ruleWithoutEnv));
    }

    // --- setEnvironmentEnabled() local mutation ---

    @Test
    void setEnvironmentEnabled_setsEnabledTrue() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setEnvironmentEnabled("production", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("production");
        assertEquals(true, envData.get("enabled"));
    }

    @Test
    void setEnvironmentEnabled_setsEnabledFalse() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setEnvironmentEnabled("staging", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("staging");
        assertEquals(false, envData.get("enabled"));
    }

    @Test
    void setEnvironmentEnabled_createsEnvIfAbsent() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        assertFalse(flag.getEnvironments().containsKey("new-env"));

        flag.setEnvironmentEnabled("new-env", true);

        assertTrue(flag.getEnvironments().containsKey("new-env"));
    }

    // --- setEnvironmentDefault() local mutation ---

    @Test
    void setEnvironmentDefault_setsDefault() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setEnvironmentDefault("staging", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("staging");
        assertEquals(true, envData.get("default"));
    }

    @Test
    void setEnvironmentDefault_createsEnvIfAbsent() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.setEnvironmentDefault("production", "fallback");

        assertTrue(flag.getEnvironments().containsKey("production"));
    }

    // --- clearRules() local mutation ---

    @Test
    void clearRules_emptiesRulesList() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        Map<String, Object> rule = Map.of(
                "environment", "staging", "description", "Rule",
                "logic", Map.of("==", List.of(1, 1)), "value", true);
        flag.addRule(rule);

        flag.clearRules("staging");

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("staging");
        @SuppressWarnings("unchecked")
        List<?> rules = (List<?>) envData.get("rules");
        assertTrue(rules.isEmpty());
    }

    @Test
    void clearRules_createsEnvWithEmptyRulesIfAbsent() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        flag.clearRules("production");

        @SuppressWarnings("unchecked")
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("production");
        assertNotNull(envData);
        @SuppressWarnings("unchecked")
        List<?> rules = (List<?>) envData.get("rules");
        assertNotNull(rules);
        assertTrue(rules.isEmpty());
    }

    // --- _apply() copies all fields ---

    @Test
    void apply_copiesAllFieldsFromOther() {
        Instant now = Instant.now();
        Flag<Boolean> source = new Flag<>(client, "source-id", "Source Name", "BOOLEAN", true,
                List.of(Map.of("name", "On", "value", true)),
                "Source desc",
                Map.of("prod", Map.of("enabled", true)),
                now, now, Boolean.class);

        Flag<Boolean> target = client.management().newBooleanFlag("target-id", false);
        target._apply(source);

        assertEquals("source-id", target.getId());
        assertEquals("Source Name", target.getName());
        assertEquals("BOOLEAN", target.getType());
        assertTrue(target.getDefault());
        assertEquals(1, target.getValues().size());
        assertEquals("Source desc", target.getDescription());
        assertTrue(target.getEnvironments().containsKey("prod"));
        assertEquals(now, target.getCreatedAt());
        assertEquals(now, target.getUpdatedAt());
    }

    @Test
    void apply_withNullValues_setsNull() {
        Flag<Boolean> source = new Flag<>(client, "my-id", "Name", "BOOLEAN", false,
                null, null, null, null, null, Boolean.class);

        Flag<Boolean> target = client.management().newBooleanFlag("target-id", false);
        target._apply(source);

        assertNull(target.getValues(), "apply with null values should result in null");
        assertNotNull(target.getEnvironments());
        assertTrue(target.getEnvironments().isEmpty());
    }

    @Test
    void apply_doesNotShareMutableState() {
        Flag<Boolean> source = client.management().newBooleanFlag("source-id", false);
        source.setEnvironmentEnabled("staging", true);

        Flag<Boolean> target = client.management().newBooleanFlag("target-id", false);
        target._apply(source);

        // Modifying target's environments should not affect source
        target.setEnvironmentEnabled("production", true);
        assertFalse(source.getEnvironments().containsKey("production"));
    }

    // --- toString ---

    @Test
    void toString_containsIdTypeAndDefault() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        String str = flag.toString();
        assertEquals("Flag{id='my-flag', type='BOOLEAN', default=false}", str);
    }

    @Test
    void toString_stringFlag() {
        Flag<String> flag = client.management().newStringFlag("color", "red");
        assertEquals("Flag{id='color', type='STRING', default=red}", flag.toString());
    }

    @Test
    void toString_numberFlag() {
        Flag<Number> flag = client.management().newNumberFlag("limit", 42);
        assertEquals("Flag{id='limit', type='NUMERIC', default=42}", flag.toString());
    }

    // --- Helpers ---

    private void connectWithFlag(String id, String type, Object defaultValue,
                                  Map<String, Object> environments) throws ApiException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of("id", id, "type", "flag", "attributes", attrs))),
                FlagListResponse.class));
        client._connectInternal();
    }
}
