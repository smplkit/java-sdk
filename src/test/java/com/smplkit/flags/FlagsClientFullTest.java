package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.openapitools.jackson.nullable.JsonNullableModule;
import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import com.smplkit.internal.generated.flags.model.ResponseFlag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
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
 * Comprehensive tests for FlagsClient — covers evaluation edge cases,
 * context provider, cache behavior, disconnect, refresh, and management.
 */
class FlagsClientFullTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());
    private FlagsApi mockApi;
    private FlagsClient client;
    private static final String FLAG_ID = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        client = new FlagsClient(mockApi, HttpClient.newHttpClient(), "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));
    }

    // --- Evaluation: first-rule-wins ---

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
        FlagHandle<String> handle = client.stringFlag("color", "red");
        assertEquals("blue", handle.get(List.of()));
    }

    // --- Evaluation: empty logic skipped ---

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
        FlagHandle<String> handle = client.stringFlag("color", "red");
        assertEquals("blue", handle.get(List.of()));
    }

    // --- Evaluation: env default when no rule matches ---

    @Test
    void evaluation_envDefaultWhenNoMatch() throws ApiException {
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
        FlagHandle<String> handle = client.stringFlag("color", "red");
        assertEquals("yellow", handle.get(List.of()));
    }

    // --- Evaluation: disabled env with no env default falls back to flag default ---

    @Test
    void evaluation_disabledEnvFallsToFlagDefault() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", false)
        ));
        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertFalse(handle.get(List.of()));
    }

    // --- Evaluation: null rules list ---

    @Test
    void evaluation_nullRulesReturnsDefault() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true)
        ));
        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertFalse(handle.get(List.of()));
    }

    // --- Evaluation: invalid JSON logic is caught and falls through ---

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
        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        // Should fall through bad rule to env default
        assertTrue(handle.get(List.of()));
    }

    // --- Evaluation: null logic in rule ---

    @Test
    void evaluation_nullLogicSkipped() throws ApiException {
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
        FlagHandle<String> handle = client.stringFlag("color", "red");
        assertEquals("yellow", handle.get(List.of()));
    }

    // --- Evaluation: JSON Logic truthiness ---

    @Test
    void evaluation_truthyNumber() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(
                                Map.of("description", "Returns 1",
                                        "logic", Map.of("+", List.of(1, 0)),
                                        "value", true)
                        )
                )
        ));
        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        // The rule logic evaluates to 1 (truthy number), so the value is served
        assertTrue(handle.get(List.of()));
    }

    // --- Disconnect ---

    @Test
    void disconnect_clearsStateAndStatus() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());
        assertEquals("connected", client.connectionStatus());

        client.disconnect();
        assertEquals("disconnected", client.connectionStatus());

        // After disconnect, handle returns default
        FlagHandle<Boolean> handle = client.boolFlag("after-disconnect", true);
        assertTrue(handle.get());
    }

    // --- Refresh ---

    @Test
    void refresh_reloadsFlags() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertTrue(handle.get(List.of()));

        // Now change the mock to return different data
        setupList("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", false)
        ));

        client.refresh();

        // Cache was cleared, so re-evaluation happens
        assertFalse(handle.get(List.of()));
    }

    @Test
    void refresh_firesChangeListeners() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        setupList("feature-x", "BOOLEAN", false, Map.of());
        client.refresh();

        assertTrue(count.get() > 0);
    }

    // --- Context provider integration ---

    @Test
    void contextProvider_usedWhenNoExplicitContext() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Enterprise only",
                                "logic", Map.of("==", List.of(Map.of("var", "user.plan"), "enterprise")),
                                "value", true
                        ))
                )
        ));

        client.setContextProvider(() -> List.of(
                new Context("user", "u-1", Map.of("plan", "enterprise"))
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        // Provider provides matching context
        assertTrue(handle.get());
    }

    // --- Multiple contexts ---

    @Test
    void multipleContextTypes() throws ApiException {
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

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);

        // Both conditions match
        assertTrue(handle.get(List.of(
                new Context("user", "u-1", Map.of("plan", "enterprise")),
                new Context("account", "acme", Map.of("region", "us"))
        )));

        // Only one condition matches
        assertFalse(handle.get(List.of(
                new Context("user", "u-1", Map.of("plan", "enterprise")),
                new Context("account", "acme", Map.of("region", "eu"))
        )));
    }

    // --- Cache behavior with different contexts ---

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

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);

        Context enterprise = new Context("user", "u-1", Map.of("plan", "enterprise"));
        Context free = new Context("user", "u-2", Map.of("plan", "free"));

        assertTrue(handle.get(List.of(enterprise)));
        assertFalse(handle.get(List.of(free)));

        // Verify cache stats (2 misses since different contexts)
        FlagStats stats = client.stats();
        assertEquals(2, stats.cacheMisses());
    }

    // --- Number flag evaluation ---

    @Test
    void numberFlagEvaluation() throws ApiException {
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

        FlagHandle<Number> handle = client.numberFlag("rate-limit", 100);
        Context premium = new Context("user", "u-1", Map.of("plan", "premium"));
        assertEquals(1000, handle.get(List.of(premium)));

        Context free = new Context("user", "u-2", Map.of("plan", "free"));
        assertEquals(100, handle.get(List.of(free)));
    }

    // --- JSON flag evaluation ---

    @Test
    void jsonFlagEvaluation() throws ApiException {
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

        FlagHandle<Object> handle = client.jsonFlag("config", defaultConfig);
        Context premium = new Context("user", "u-1", Map.of("plan", "premium"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handle.get(List.of(premium));
        assertEquals(100, result.get("limit"));
    }

    // --- Flag-specific change listener ---

    @Test
    void flagSpecificOnChange() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        handle.onChange(received::set);

        setupList("feature-x", "BOOLEAN", false, Map.of());
        client.refresh();

        assertNotNull(received.get());
        assertEquals("feature-x", received.get().key());
    }

    // --- Register contexts ---

    @Test
    void register_addsContextToBuffer() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());

        Context ctx = new Context("user", "u-123", Map.of("plan", "enterprise"));
        client.register(ctx);

        // Registering the same context again shouldn't add a duplicate
        client.register(ctx);
    }

    // --- Create with auto boolean values ---

    @Test
    void create_autoBooleanValues() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(
                                Map.of("name", "True", "value", true),
                                Map.of("name", "False", "value", false)
                        ), "environments", Map.of()
                )
        )), FlagResponse.class);
        when(mockApi.createFlag(any(ResponseFlag.class))).thenReturn(response);

        FlagResource result = client.create(CreateFlagParams.builder(
                "my-flag", "My Flag", FlagType.BOOLEAN).defaultValue(false).build());
        assertEquals("my-flag", result.key());
    }

    // --- Create with explicit values ---

    @Test
    void create_withExplicitValues() throws ApiException {
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "color", "name", "Color", "type", "STRING",
                        "default", "red", "values", List.of(
                                Map.of("name", "Red", "value", "red"),
                                Map.of("name", "Blue", "value", "blue")
                        ), "environments", Map.of()
                )
        )), FlagResponse.class);
        when(mockApi.createFlag(any(ResponseFlag.class))).thenReturn(response);

        FlagResource result = client.create(CreateFlagParams.builder(
                "color", "Color", FlagType.STRING)
                .defaultValue("red")
                .description("Pick a color")
                .values(List.of(
                        Map.of("name", "Red", "value", "red"),
                        Map.of("name", "Blue", "value", "blue")
                ))
                .build());
        assertEquals("color", result.key());
    }

    // --- Exception mapping ---

    @Test
    void apiException409_throwsSmplConflictException() throws ApiException {
        when(mockApi.createFlag(any(ResponseFlag.class)))
                .thenThrow(new ApiException(409, "Conflict"));
        assertThrows(SmplConflictException.class,
                () -> client.create(CreateFlagParams.builder("k", "n", FlagType.BOOLEAN).build()));
    }

    @Test
    void apiExceptionGeneric_throwsSmplException() throws ApiException {
        when(mockApi.createFlag(any(ResponseFlag.class)))
                .thenThrow(new ApiException(500, "Server Error"));
        assertThrows(SmplException.class,
                () -> client.create(CreateFlagParams.builder("k", "n", FlagType.BOOLEAN).build()));
    }

    @Test
    void apiExceptionNullMessage() throws ApiException {
        when(mockApi.getFlag(any(UUID.class)))
                .thenThrow(new ApiException(500, null));
        assertThrows(SmplException.class, () -> client.get(FLAG_ID));
    }

    // --- List with empty response ---

    @Test
    void list_emptyResponse() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(new FlagListResponse().data(List.of()));
        List<FlagResource> result = client.list();
        assertTrue(result.isEmpty());
    }

    // --- Delete not found ---

    @Test
    void delete_notFound() throws ApiException {
        doThrow(new ApiException(404, "Not Found")).when(mockApi).deleteFlag(any(UUID.class));
        assertThrows(SmplNotFoundException.class, () -> client.delete(FLAG_ID));
    }

    // --- Disconnect without connect ---

    @Test
    void disconnect_whenNotConnected() {
        // Should not throw
        assertDoesNotThrow(() -> client.disconnect());
    }

    // --- Stats when empty ---

    @Test
    void stats_whenEmpty() {
        FlagStats stats = client.stats();
        assertEquals(0, stats.cacheHits());
        assertEquals(0, stats.cacheMisses());
    }

    // --- Parse response with created_at/updated_at ---

    @Test
    void parseResponse_withTimestamps() throws ApiException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "flag");
        attrs.put("name", "Flag");
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        attrs.put("created_at", "2026-01-01T00:00:00Z");
        attrs.put("updated_at", "2026-01-02T00:00:00Z");
        FlagResponse response = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", attrs
        )), FlagResponse.class);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(response);

        FlagResource result = client.get(FLAG_ID);
        assertNotNull(result.createdAt());
        assertNotNull(result.updatedAt());
    }

    @Test
    void parseResponse_withNullTimestamps() throws ApiException {
        FlagResponse response = makeResponse(FLAG_ID, "flag", "Flag", "BOOLEAN", false);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(response);

        FlagResource result = client.get(FLAG_ID);
        assertNull(result.createdAt());
        assertNull(result.updatedAt());
    }

    @Test
    void parseResponse_withMissingTimestamp() throws ApiException {
        // With typed responses, invalid timestamps are rejected by Jackson at deserialization.
        // Test that a response with no created_at/updated_at returns null for timestamps.
        FlagResponse response = makeResponse(FLAG_ID, "flag", "Flag", "BOOLEAN", false);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(response);

        FlagResource result = client.get(FLAG_ID);
        assertNull(result.createdAt());
    }

    @Test
    void parseResponse_withEpochTimestamp() throws ApiException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "flag");
        attrs.put("name", "Flag");
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        attrs.put("created_at", 12345);
        FlagResponse response = OBJECT_MAPPER.convertValue(
                Map.of("data", Map.of("id", FLAG_ID, "type", "flag", "attributes", attrs)),
                FlagResponse.class);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(response);

        FlagResource result = client.get(FLAG_ID);
        // Jackson interprets integer 12345 as epoch seconds, so timestamp is resolved
        assertNotNull(result.createdAt());
    }

    // --- Update flag ---

    @Test
    void updateFlag_viaResource() throws ApiException {
        // Setup mock for get (re-fetch) and update
        FlagResponse getResponse = makeResponse(FLAG_ID, "my-flag", "My Flag", "BOOLEAN", false);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(getResponse);

        FlagResponse updateResponse = makeResponse(FLAG_ID, "my-flag", "Updated Flag", "BOOLEAN", false);
        when(mockApi.updateFlag(eq(UUID.fromString(FLAG_ID)), any(ResponseFlag.class)))
                .thenReturn(updateResponse);

        FlagResource resource = new FlagResource(FLAG_ID, "my-flag", "My Flag",
                null, "BOOLEAN", false, List.of(), Map.of(), null, null);
        resource.setClient(client);

        FlagResource updated = resource.update(UpdateFlagParams.builder()
                .name("Updated Flag").build());
        assertEquals("Updated Flag", updated.name());
    }

    // --- Add rule ---

    @Test
    void addRule_viaResource() throws ApiException {
        // Setup mock for get (re-fetch) and update
        FlagResponse getResponse = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(
                                Map.of("name", "True", "value", true),
                                Map.of("name", "False", "value", false)
                        ),
                        "environments", Map.of(
                                "production", Map.of("enabled", true, "rules", List.of())
                        )
                )
        )), FlagResponse.class);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(getResponse);

        FlagResponse updateResponse = getResponse;
        when(mockApi.updateFlag(eq(UUID.fromString(FLAG_ID)), any(ResponseFlag.class)))
                .thenReturn(updateResponse);

        FlagResource resource = new FlagResource(FLAG_ID, "my-flag", "My Flag",
                null, "BOOLEAN", false, List.of(), Map.of(), null, null);
        resource.setClient(client);

        Map<String, Object> rule = new Rule("Enterprise only")
                .environment("production")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();

        FlagResource updated = resource.addRule(rule);
        assertNotNull(updated);
    }

    @Test
    void addRule_withoutEnvironment_throwsValidation() throws ApiException {
        FlagResponse getResponse = makeResponse(FLAG_ID, "my-flag", "My Flag", "BOOLEAN", false);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(getResponse);

        FlagResource resource = new FlagResource(FLAG_ID, "my-flag", "My Flag",
                null, "BOOLEAN", false, List.of(), Map.of(), null, null);
        resource.setClient(client);

        Map<String, Object> ruleWithoutEnv = new Rule("No env")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();

        assertThrows(SmplValidationException.class, () -> resource.addRule(ruleWithoutEnv));
    }

    @Test
    void addRule_toNewEnvironment() throws ApiException {
        FlagResponse getResponse = makeResponse(FLAG_ID, "my-flag", "My Flag", "BOOLEAN", false);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(getResponse);
        when(mockApi.updateFlag(eq(UUID.fromString(FLAG_ID)), any(ResponseFlag.class)))
                .thenReturn(getResponse);

        FlagResource resource = new FlagResource(FLAG_ID, "my-flag", "My Flag",
                null, "BOOLEAN", false, List.of(), Map.of(), null, null);
        resource.setClient(client);

        Map<String, Object> rule = new Rule("New env rule")
                .environment("staging")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();

        FlagResource updated = resource.addRule(rule);
        assertNotNull(updated);
    }

    // --- Update with description and environments ---

    @Test
    void updateFlag_withDescriptionAndEnvironments() throws ApiException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "my-flag");
        attrs.put("name", "My Flag");
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("description", "Original desc");
        attrs.put("values", List.of(Map.of("name", "T", "value", true)));
        attrs.put("environments", Map.of("prod", Map.of(
                "enabled", true,
                "default", false,
                "rules", List.of(Map.of(
                        "description", "R1",
                        "logic", Map.of("==", List.of(1, 1)),
                        "value", true
                ))
        )));
        FlagResponse getResponse = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", attrs
        )), FlagResponse.class);
        when(mockApi.getFlag(UUID.fromString(FLAG_ID))).thenReturn(getResponse);

        FlagResponse updateResponse = getResponse;
        when(mockApi.updateFlag(eq(UUID.fromString(FLAG_ID)), any(ResponseFlag.class)))
                .thenReturn(updateResponse);

        FlagResource resource = new FlagResource(FLAG_ID, "my-flag", "My Flag",
                "Original desc", "BOOLEAN", false, List.of(Map.of("name", "T", "value", true)),
                Map.of(), null, null);
        resource.setClient(client);

        FlagResource updated = resource.update(UpdateFlagParams.builder()
                .description("New desc")
                .defaultValue(true)
                .values(List.of(Map.of("name", "T", "value", true), Map.of("name", "F", "value", false)))
                .environments(Map.of("prod", Map.of("enabled", true)))
                .build());
        assertNotNull(updated);
    }

    // --- OnChange exception handling ---

    @Test
    void onChange_listenerExceptionIsCaught() throws ApiException {
        connectWithFlag("feature-x", "BOOLEAN", false, Map.of());

        client.onChange(e -> { throw new RuntimeException("boom"); });

        // Refresh should not throw despite listener exception
        setupList("feature-x", "BOOLEAN", false, Map.of());
        assertDoesNotThrow(() -> client.refresh());
    }

    // --- Test constructor ---

    @Test
    void testConstructor() {
        FlagsClient testClient = new FlagsClient();
        assertEquals("disconnected", testClient.connectionStatus());
        assertEquals(0, testClient.stats().cacheHits());
    }

    // --- FlagHandle type coercion edge cases ---

    @Test
    void boolHandle_nullReturnsDefault() throws ApiException {
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
        client.connect("staging");

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", true);
        // Flag default is null, env has no specific default, no rules → returns null → handle returns code default
        assertTrue(handle.get(List.of()));
    }

    // --- Helpers ---

    private void connectWithFlag(String key, String type, Object defaultValue,
                                  Map<String, Object> environments) throws ApiException {
        setupList(key, type, defaultValue, environments);
        client.connect("staging");
    }

    private void setupList(String key, String type, Object defaultValue,
                            Map<String, Object> environments) throws ApiException {
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(makeFlagListResponse(
                FLAG_ID, key, type, defaultValue, environments));
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
                "id", id,
                "type", "flag",
                "attributes", attrs
        )));
        return OBJECT_MAPPER.convertValue(map, FlagListResponse.class);
    }

    private static FlagResponse makeResponse(String id, String key, String name,
                                               String type, Object defaultValue) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", key);
        attrs.put("name", name);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        Map<String, Object> map = Map.of("data", Map.of(
                "id", id,
                "type", "flag",
                "attributes", attrs
        ));
        return OBJECT_MAPPER.convertValue(map, FlagResponse.class);
    }
}
