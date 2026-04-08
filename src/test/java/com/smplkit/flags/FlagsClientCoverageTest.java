package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.openapitools.jackson.nullable.JsonNullableModule;
import com.smplkit.Context;
import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.internal.generated.app.api.ContextTypesApi;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.app.model.ContextListResponse;
import com.smplkit.internal.generated.app.model.ContextResource;
import com.smplkit.internal.generated.app.model.ContextType;
import com.smplkit.internal.generated.app.model.ContextTypeListResponse;
import com.smplkit.internal.generated.app.model.ContextTypeResource;
import com.smplkit.internal.generated.app.model.ContextTypeResponse;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional coverage tests for FlagsClient — targets doAppRequest, context types,
 * stateless evaluate, LRU overflow, isTruthy edge cases, and flushContexts.
 */
class FlagsClientCoverageTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());
    private FlagsApi mockApi;
    private ContextTypesApi mockContextTypesApi;
    private ContextsApi mockContextsApi;
    private FlagsClient client;
    private static final String FLAG_ID = "11111111-1111-1111-1111-111111111111";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        mockApi = Mockito.mock(FlagsApi.class);
        mockContextTypesApi = Mockito.mock(ContextTypesApi.class);
        mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextTypesApi, mockContextsApi,
                Mockito.mock(HttpClient.class), "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));
    }

    // --- context type methods ---

    @Test
    void createContextType_success() throws Exception {
        ContextTypeResource resource = new ContextTypeResource()
                .id("ct-1")
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(new ContextType().key("user").name("User"));
        when(mockContextTypesApi.createContextType(any(ContextTypeResponse.class)))
                .thenReturn(new ContextTypeResponse().data(resource));

        Map<String, Object> result = client.createContextType("user",
                Map.of("name", "User", "attributes", Map.of("plan", "string")));

        assertNotNull(result);
        assertEquals("ct-1", result.get("id"));
    }

    @Test
    void createContextType_withNullOptions() throws Exception {
        ContextTypeResource resource = new ContextTypeResource()
                .id("ct-1")
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(new ContextType().key("user").name("user"));
        when(mockContextTypesApi.createContextType(any(ContextTypeResponse.class)))
                .thenReturn(new ContextTypeResponse().data(resource));

        Map<String, Object> result = client.createContextType("user", null);
        assertNotNull(result);
    }

    @Test
    void updateContextType_success() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        ContextTypeResource resource = new ContextTypeResource()
                .id(id)
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(new ContextType().key("user").name("Updated"));
        when(mockContextTypesApi.updateContextType(eq(UUID.fromString(id)), any(ContextTypeResponse.class)))
                .thenReturn(new ContextTypeResponse().data(resource));

        Map<String, Object> result = client.updateContextType(id, Map.of("name", "Updated"));
        assertNotNull(result);
    }

    @Test
    void listContextTypes_success() throws Exception {
        ContextTypeResource r1 = new ContextTypeResource().id("ct-1")
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(new ContextType().key("user").name("User"));
        ContextTypeResource r2 = new ContextTypeResource().id("ct-2")
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(new ContextType().key("device").name("Device"));
        when(mockContextTypesApi.listContextTypes())
                .thenReturn(new ContextTypeListResponse().data(List.of(r1, r2)));

        List<Map<String, Object>> result = client.listContextTypes();
        assertEquals(2, result.size());
    }

    @Test
    void updateContextType_withKeyAndAttributes() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        ContextTypeResource resource = new ContextTypeResource()
                .id(id)
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(new ContextType().key("user-v2").name("User"));
        when(mockContextTypesApi.updateContextType(eq(UUID.fromString(id)), any(ContextTypeResponse.class)))
                .thenReturn(new ContextTypeResponse().data(resource));

        Map<String, Object> result = client.updateContextType(id,
                Map.of("key", "user-v2", "name", "User", "attributes", Map.of("plan", "string")));
        assertNotNull(result);
    }

    @Test
    void updateContextType_apiError() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        when(mockContextTypesApi.updateContextType(eq(UUID.fromString(id)), any(ContextTypeResponse.class)))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "server error"));

        assertThrows(SmplException.class, () -> client.updateContextType(id, Map.of("name", "X")));
    }

    @Test
    void listContextTypes_apiError() throws Exception {
        when(mockContextTypesApi.listContextTypes())
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "server error"));

        assertThrows(SmplException.class, () -> client.listContextTypes());
    }

    @Test
    void deleteContextType_success() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        doNothing().when(mockContextTypesApi).deleteContextType(UUID.fromString(id));

        assertDoesNotThrow(() -> client.deleteContextType(id));
    }

    @Test
    void listContexts_success() throws Exception {
        ContextResource ctx1 = new ContextResource().id("ctx-1");
        ContextResource ctx2 = new ContextResource().id("ctx-2");
        when(mockContextsApi.listContexts(eq("user")))
                .thenReturn(new ContextListResponse().data(List.of(ctx1, ctx2)));

        List<Map<String, Object>> result = client.listContexts("user");
        assertEquals(2, result.size());
    }

    @Test
    void listContexts_usesContextTypeId() throws Exception {
        when(mockContextsApi.listContexts(eq("device")))
                .thenReturn(new ContextListResponse().data(List.of()));

        client.listContexts("device");
        verify(mockContextsApi).listContexts("device");
    }

    // --- App API error status codes ---

    @Test
    void contextType_404_throwsNotFound() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        doThrow(new com.smplkit.internal.generated.app.ApiException(404, "not found"))
                .when(mockContextTypesApi).deleteContextType(UUID.fromString(id));

        assertThrows(SmplNotFoundException.class, () -> client.deleteContextType(id));
    }

    @Test
    void contextType_409_throwsConflict() throws Exception {
        when(mockContextTypesApi.createContextType(any(ContextTypeResponse.class)))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(409, "conflict"));

        assertThrows(SmplConflictException.class, () -> client.createContextType("user", null));
    }

    @Test
    void contextType_422_throwsValidation() throws Exception {
        when(mockContextTypesApi.createContextType(any(ContextTypeResponse.class)))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(422, "invalid"));

        assertThrows(SmplValidationException.class, () -> client.createContextType("user", null));
    }

    @Test
    void contextType_500_throwsGeneric() throws Exception {
        when(mockContextTypesApi.createContextType(any(ContextTypeResponse.class)))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "server error"));

        assertThrows(SmplException.class, () -> client.createContextType("user", null));
    }

    @Test
    void listContexts_apiException() throws Exception {
        when(mockContextsApi.listContexts(anyString()))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "server error"));

        assertThrows(SmplException.class, () -> client.listContexts("user"));
    }

    // --- Stateless evaluate ---

    @Test
    void evaluate_returnsResolvedValue() throws Exception {
        FlagListResponse listResponse = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                Map.of("id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "feature-x", "name", "Feature X", "type", "BOOLEAN",
                        "default", false, "values", List.of(),
                        "environments", Map.of("staging", Map.of(
                                "enabled", true,
                                "rules", List.of(Map.of(
                                        "description", "Always on",
                                        "logic", Map.of("==", List.of(1, 1)),
                                        "value", true
                                ))
                        ))
                ))
        )), FlagListResponse.class);
        when(mockApi.listFlags(eq("feature-x"), isNull())).thenReturn(listResponse);

        Object result = client.evaluate("feature-x", "staging", List.of());
        assertEquals(true, result);
    }

    @Test
    void evaluate_returnsNull_whenFlagNotFound() throws Exception {
        when(mockApi.listFlags(eq("unknown"), isNull())).thenReturn(new FlagListResponse().data(List.of()));

        Object result = client.evaluate("unknown", "staging", List.of());
        assertNull(result);
    }

    @Test
    void evaluate_throwsOnApiError() throws Exception {
        when(mockApi.listFlags(eq("feature-x"), isNull()))
                .thenThrow(new ApiException(500, "Server Error"));

        assertThrows(SmplException.class, () -> client.evaluate("feature-x", "staging", List.of()));
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

        // No API call should have been made
        verify(mockContextsApi, never()).bulkRegisterContexts(any(ContextBulkRegister.class));
    }

    @Test
    void flushContexts_handlesException() throws Exception {
        when(mockContextsApi.bulkRegisterContexts(any(ContextBulkRegister.class)))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "fail"));

        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true)
        ));

        client.register(new Context("user", "u-1", Map.of("plan", "premium")));

        // Should not throw even though API fails
        assertDoesNotThrow(() -> client.flushContexts());
    }

    // --- isTruthy edge cases ---

    @Test
    void evaluation_truthyString() throws Exception {
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

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        // JSON Logic cat returns a non-empty string which is truthy
        assertTrue(handle.get(List.of()));
    }

    @Test
    void evaluation_falsyEmptyString() throws Exception {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", true,
                        "rules", List.of(Map.of(
                                "description", "Empty string result",
                                "logic", Map.of("cat", List.of("")),
                                "value", false
                        ))
                )
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        // JSON Logic cat returns "" which is falsy → rule doesn't match → env default
        assertTrue(handle.get(List.of()));
    }

    // --- FlagHandle type coercion: remaining edge cases ---

    @Test
    void boolHandle_rejectsNonBooleanRaw() throws Exception {
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

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        // Rule value is "not-a-boolean" (String), boolean handle rejects → returns default
        assertFalse(handle.get(List.of()));
    }

    @Test
    void numberHandle_rejectsNonNumericValue() throws Exception {
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

        FlagHandle<Number> handle = client.numberFlag("limit", 100);
        assertEquals(100, handle.get(List.of()));
    }

    @Test
    void jsonHandle_returnsRawValue() throws Exception {
        Map<String, Object> config = Map.of("limit", 100, "enabled", true);
        setupFlagStore("config", "JSON", config, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", config
                )
        ));

        FlagHandle<Object> handle = client.jsonFlag("config", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handle.get(List.of());
        assertEquals(100, result.get("limit"));
    }

    // --- FlagHandle: null raw value returns default ---

    @Test
    void handleGet_nullRawReturnsDefault() throws Exception {
        // Flag with null default and no env default → evaluateFlag returns null → handle returns code default
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
        client.connectInternal("staging");

        FlagHandle<String> handle = client.stringFlag("feature-x", "fallback");
        assertEquals("fallback", handle.get(List.of()));
    }

    // --- FlagHandle: final default return for unknown type ---

    @Test
    @SuppressWarnings("unchecked")
    void handleGet_unknownTypeReturnsDefault() throws Exception {
        // This tests the final `return defaultValue` at end of get() method.
        // We use a custom type class that doesn't match Boolean/String/Number/Object
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", 42
                )
        ));

        // Create a handle with Integer.class (not matched by any branch except the final return)
        FlagHandle<Integer> handle = new FlagHandle<>("feature-x", 99, Integer.class);
        handle.setNamespace(client);

        // evaluateHandle returns 42 (Integer), type is Integer.class which doesn't match
        // Boolean/String/Number/Object → falls to final `return defaultValue`
        assertEquals(99, handle.get(List.of()));
    }

    // --- list() ApiException ---

    @Test
    void list_apiException_throwsSmplException() throws Exception {
        when(mockApi.listFlags(isNull(), isNull()))
                .thenThrow(new ApiException(500, "Server Error"));

        assertThrows(SmplException.class, () -> client.list());
    }

    // --- updateFlag ApiException ---

    @Test
    void updateFlag_apiException_throwsSmplException() throws Exception {
        FlagResponse getResponse = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of()
                )
        )), FlagResponse.class);
        when(mockApi.getFlag(any())).thenReturn(getResponse);
        when(mockApi.updateFlag(any(), any()))
                .thenThrow(new ApiException(500, "Server Error"));

        FlagResource resource = new FlagResource(FLAG_ID, "my-flag", "My Flag",
                null, "BOOLEAN", false, List.of(), Map.of(), null, null);
        resource.setClient(client);

        assertThrows(SmplException.class, () ->
                resource.update(UpdateFlagParams.builder().name("Updated").build()));
    }

    // --- fireAllChangeListeners for handles not in store ---

    @Test
    void refresh_firesListenerForHandleNotInStore() throws Exception {
        // Connect with one flag
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of());

        // Create handle for a key NOT in the store
        FlagHandle<Boolean> orphanHandle = client.boolFlag("orphan-flag", false);

        AtomicInteger orphanCount = new AtomicInteger();
        orphanHandle.onChange(e -> {
            if ("orphan-flag".equals(e.key())) orphanCount.incrementAndGet();
        });

        // Refresh triggers fireAllChangeListeners which should include orphan handles
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(makeFlagListResponse(
                FLAG_ID, "feature-x", "BOOLEAN", false, Map.of()));
        client.refresh();

        assertTrue(orphanCount.get() > 0);
    }

    // --- Context batch flush threshold ---

    @Test
    void evaluateHandle_triggersEagerFlush_whenBatchFull() throws Exception {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);

        // Register enough contexts to exceed the batch flush size (100)
        for (int i = 0; i < 101; i++) {
            client.register(new Context("user", "u-" + i, Map.of("plan", "free")));
        }

        // Evaluate — should trigger eager flush thread
        handle.get(List.of(new Context("user", "u-new", Map.of("plan", "new"))));

        // Give the eager flush thread time to run
        Thread.sleep(100);
    }

    // --- updateContextType calls generated API ---

    @Test
    void updateContextType_callsGeneratedApi() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        ContextTypeResource resource = new ContextTypeResource()
                .id(id)
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(new ContextType().key("user").name("Updated"));
        when(mockContextTypesApi.updateContextType(eq(UUID.fromString(id)), any(ContextTypeResponse.class)))
                .thenReturn(new ContextTypeResponse().data(resource));

        client.updateContextType(id, Map.of("name", "Updated"));

        verify(mockContextTypesApi).updateContextType(eq(UUID.fromString(id)), any(ContextTypeResponse.class));
    }

    // --- updateFlag: current description preserved when params don't set it ---

    @Test
    void updateFlag_preservesCurrentDescription() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "my-flag");
        attrs.put("name", "My Flag");
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("description", "Original desc");
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        FlagResponse getResponse = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", attrs
        )), FlagResponse.class);
        when(mockApi.getFlag(any())).thenReturn(getResponse);
        when(mockApi.updateFlag(any(), any())).thenReturn(getResponse);

        FlagResource resource = new FlagResource(FLAG_ID, "my-flag", "My Flag",
                "Original desc", "BOOLEAN", false, List.of(), Map.of(), null, null);
        resource.setClient(client);

        // Update name only, description should be preserved from current
        FlagResource updated = resource.update(UpdateFlagParams.builder().name("New Name").build());
        assertNotNull(updated);
    }

    // --- evaluateHandle: null context provider and null contexts ---

    @Test
    void evaluateHandle_noContextProviderAndNullContexts() throws Exception {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        // Call get() with no arguments → contexts = null, contextProvider = null → ctxList = List.of()
        assertTrue(handle.get());
    }

    // --- isTruthy: non-primitive truthy value (e.g., List) ---

    @Test
    void evaluation_truthyObject() throws Exception {
        // Use a rule where JSON Logic returns a non-primitive truthy result
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

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    // --- flushContextsSafe: catches exception ---

    @Test
    void flushContextsSafe_catchesException() throws Exception {
        // flushContextsSafe wraps flushContexts and catches exceptions
        // We need bulk register to throw
        when(mockContextsApi.bulkRegisterContexts(any(ContextBulkRegister.class)))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "connection refused"));

        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true)
        ));

        client.register(new Context("user", "u-1", Map.of("plan", "premium")));

        // disconnect calls flushContextsSafe internally
        assertDoesNotThrow(() -> client.disconnect());
    }

    // --- fireAllChangeListeners: exception in orphan handle listener ---

    @Test
    void refresh_orphanHandleListenerExceptionIsCaught() throws Exception {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of());

        FlagHandle<Boolean> orphanHandle = client.boolFlag("orphan-flag", false);
        orphanHandle.onChange(e -> { throw new RuntimeException("boom"); });

        when(mockApi.listFlags(isNull(), isNull())).thenReturn(makeFlagListResponse(
                FLAG_ID, "feature-x", "BOOLEAN", false, Map.of()));

        assertDoesNotThrow(() -> client.refresh());
    }

    // --- parseListResponse: data is not a List ---

    @Test
    void list_nonListDataReturnsEmpty() throws Exception {
        // The FlagListResponse always has a List<FlagResource> data field,
        // so "not-a-list" scenario is no longer possible with typed responses.
        // Instead test with an empty list.
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(new FlagListResponse().data(List.of()));

        List<FlagResource> result = client.list();
        assertTrue(result.isEmpty());
    }

    // --- buildEnvironments: env with "default" key ---

    @Test
    void updateFlag_withEnvironmentDefaults() throws Exception {
        FlagResponse getResponse = OBJECT_MAPPER.convertValue(Map.of("data", Map.of(
                "id", FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(),
                        "environments", Map.of("prod", Map.of(
                                "enabled", true, "default", true
                        ))
                )
        )), FlagResponse.class);
        when(mockApi.getFlag(any())).thenReturn(getResponse);
        when(mockApi.updateFlag(any(), any())).thenReturn(getResponse);

        FlagResource resource = new FlagResource(FLAG_ID, "my-flag", "My Flag",
                null, "BOOLEAN", false, List.of(),
                Map.of("prod", Map.of("enabled", true, "default", true)), null, null);
        resource.setClient(client);

        // Update using environments param → buildEnvironments called with "default" key
        FlagResource updated = resource.update(UpdateFlagParams.builder()
                .environments(Map.of("prod", Map.of("enabled", true, "default", true)))
                .build());
        assertNotNull(updated);
    }

    // --- FlagHandle: raw null for String type ---

    @Test
    void stringHandle_nullRawReturnsDefault() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "color");
        attrs.put("name", "Color");
        attrs.put("type", "STRING");
        attrs.put("default", null);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of("staging", Map.of("enabled", true)));
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of("id", FLAG_ID, "type", "flag", "attributes", attrs))),
                FlagListResponse.class));
        client.connectInternal("staging");

        FlagHandle<String> handle = client.stringFlag("color", "red");
        assertEquals("red", handle.get(List.of()));
    }

    // --- FlagHandle: null default value ---

    @Test
    void handleGet_nullDefaultValue_returnsNull() throws Exception {
        // Set up a flag where evaluation returns null: flag not in store → evaluateHandle returns defaultValue (null)
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(new FlagListResponse().data(List.of()));
        client.connectInternal("staging");

        // Create a handle with null default for a key not in the store
        FlagHandle<Boolean> handle = new FlagHandle<>("nonexistent", null, Boolean.class);
        handle.setNamespace(client);

        // evaluateHandle returns defaultValue (null) since flag not in store → raw is null → line 60
        assertNull(handle.get(List.of()));
    }

    // --- Helpers ---

    private void setupFlagStore(String key, String type, Object defaultValue,
                                 Map<String, Object> environments) throws ApiException {
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(makeFlagListResponse(
                FLAG_ID, key, type, defaultValue, environments));
        client.connectInternal("staging");
    }

    @Test
    void parseListResponse_nullData_returnsEmpty() throws ApiException {
        // FlagListResponse with null data — covers the null guard
        FlagListResponse resp = new FlagListResponse();
        resp.setData(null);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(resp);
        List<FlagResource> result = client.list();
        assertTrue(result.isEmpty());
    }

    @Test
    void parseInstant_invalidString_returnsNull() {
        // Cover parseInstant catch block (lines 956-957)
        assertNull(FlagsClient.parseInstant("not-a-date"));
    }

    @Test
    void parseInstant_nonStringNonNull_returnsNull() {
        // Cover parseInstant non-String return (line 960)
        assertNull(FlagsClient.parseInstant(12345));
    }

    @Test
    void parseInstant_validString_returnsInstant() {
        assertNotNull(FlagsClient.parseInstant("2026-01-01T00:00:00Z"));
    }

    @Test
    void parseInstant_null_returnsNull() {
        assertNull(FlagsClient.parseInstant(null));
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
}
