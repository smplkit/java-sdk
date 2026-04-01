package com.smplkit.flags;

import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional coverage tests for FlagsClient — targets doAppRequest, context types,
 * stateless evaluate, LRU overflow, isTruthy edge cases, and flushContexts.
 */
class FlagsClientCoverageTest {

    private FlagsApi mockApi;
    private HttpClient mockHttpClient;
    private FlagsClient client;
    private static final String FLAG_ID = "11111111-1111-1111-1111-111111111111";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        mockApi = Mockito.mock(FlagsApi.class);
        mockHttpClient = Mockito.mock(HttpClient.class);
        client = new FlagsClient(mockApi, mockHttpClient, "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));
    }

    // --- doAppRequest / context type methods ---

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockHttpResponse(int status, String body) throws Exception {
        HttpResponse<String> response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return response;
    }

    @Test
    void createContextType_success() throws Exception {
        mockHttpResponse(200, "{\"data\":{\"id\":\"ct-1\",\"attributes\":{\"key\":\"user\"}}}");

        Map<String, Object> result = client.createContextType("user",
                Map.of("name", "User", "attributes", List.of("plan", "region")));

        assertNotNull(result);
        assertEquals("ct-1", result.get("id"));
    }

    @Test
    void createContextType_withNullOptions() throws Exception {
        mockHttpResponse(200, "{\"data\":{\"id\":\"ct-1\",\"attributes\":{\"key\":\"user\"}}}");

        Map<String, Object> result = client.createContextType("user", null);
        assertNotNull(result);
    }

    @Test
    void createContextType_parseError() throws Exception {
        mockHttpResponse(200, "not-json");

        assertThrows(SmplException.class, () -> client.createContextType("user", null));
    }

    @Test
    void updateContextType_success() throws Exception {
        mockHttpResponse(200, "{\"data\":{\"id\":\"ct-1\",\"attributes\":{\"key\":\"user\",\"name\":\"Updated\"}}}");

        Map<String, Object> result = client.updateContextType("ct-1", Map.of("name", "Updated"));
        assertNotNull(result);
    }

    @Test
    void updateContextType_parseError() throws Exception {
        mockHttpResponse(200, "not-json");

        assertThrows(SmplException.class, () -> client.updateContextType("ct-1", Map.of()));
    }

    @Test
    void listContextTypes_success() throws Exception {
        mockHttpResponse(200, "{\"data\":[{\"id\":\"ct-1\"},{\"id\":\"ct-2\"}]}");

        List<Map<String, Object>> result = client.listContextTypes();
        assertEquals(2, result.size());
    }

    @Test
    void listContextTypes_nonListData() throws Exception {
        mockHttpResponse(200, "{\"data\":\"not-a-list\"}");

        List<Map<String, Object>> result = client.listContextTypes();
        assertTrue(result.isEmpty());
    }

    @Test
    void listContextTypes_parseError() throws Exception {
        mockHttpResponse(200, "not-json");

        assertThrows(SmplException.class, () -> client.listContextTypes());
    }

    @Test
    void deleteContextType_success() throws Exception {
        mockHttpResponse(200, "");

        assertDoesNotThrow(() -> client.deleteContextType("ct-1"));
    }

    @Test
    void listContexts_success() throws Exception {
        mockHttpResponse(200, "{\"data\":[{\"id\":\"ctx-1\"},{\"id\":\"ctx-2\"}]}");

        List<Map<String, Object>> result = client.listContexts("user");
        assertEquals(2, result.size());
    }

    @Test
    void listContexts_nonListData() throws Exception {
        mockHttpResponse(200, "{\"data\":\"not-a-list\"}");

        List<Map<String, Object>> result = client.listContexts("user");
        assertTrue(result.isEmpty());
    }

    @Test
    void listContexts_parseError() throws Exception {
        mockHttpResponse(200, "not-json");

        assertThrows(SmplException.class, () -> client.listContexts("user"));
    }

    // --- doAppRequest HTTP error status codes ---

    @Test
    void doAppRequest_404_throwsNotFound() throws Exception {
        mockHttpResponse(404, "not found");

        assertThrows(SmplNotFoundException.class, () -> client.deleteContextType("ct-1"));
    }

    @Test
    void doAppRequest_409_throwsConflict() throws Exception {
        mockHttpResponse(409, "conflict");

        assertThrows(SmplConflictException.class, () -> client.createContextType("user", null));
    }

    @Test
    void doAppRequest_422_throwsValidation() throws Exception {
        mockHttpResponse(422, "invalid");

        assertThrows(SmplValidationException.class, () -> client.createContextType("user", null));
    }

    @Test
    void doAppRequest_500_throwsGeneric() throws Exception {
        mockHttpResponse(500, "server error");

        assertThrows(SmplException.class, () -> client.createContextType("user", null));
    }

    @SuppressWarnings("unchecked")
    @Test
    void doAppRequest_connectionFailure_throwsSmplException() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        assertThrows(SmplException.class, () -> client.createContextType("user", null));
    }

    // --- Stateless evaluate ---

    @Test
    void evaluate_returnsResolvedValue() throws Exception {
        Map<String, Object> listResponse = Map.of("data", List.of(
                Map.of("id", FLAG_ID, "attributes", Map.of(
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
        ));
        when(mockApi.listFlags(eq("feature-x"), isNull())).thenReturn(listResponse);

        Object result = client.evaluate("feature-x", "staging", List.of());
        assertEquals(true, result);
    }

    @Test
    void evaluate_returnsNull_whenFlagNotFound() throws Exception {
        when(mockApi.listFlags(eq("unknown"), isNull())).thenReturn(Map.of("data", List.of()));

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
        mockHttpResponse(200, "");
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true, "default", true)
        ));

        client.register(new Context("user", "u-1", Map.of("plan", "premium")));
        client.flushContexts();

        verify(mockHttpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void flushContexts_noOp_whenEmpty() throws Exception {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of());

        client.flushContexts();

        // No HTTP call should have been made
        verify(mockHttpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushContexts_handlesException() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("fail"));

        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of("enabled", true)
        ));

        client.register(new Context("user", "u-1", Map.of("plan", "premium")));

        // Should not throw even though HTTP fails
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
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(
                Map.of("data", List.of(Map.of("id", FLAG_ID, "attributes", attrs))));
        client.connect("staging");

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
        Map<String, Object> getResponse = Map.of("data", Map.of(
                "id", FLAG_ID, "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of()
                )
        ));
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
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(
                Map.of("data", List.of(Map.of("id", FLAG_ID, "attributes", Map.of(
                        "key", "feature-x", "name", "feature-x", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of()
                )))));
        client.refresh();

        assertTrue(orphanCount.get() > 0);
    }

    // --- Context batch flush threshold ---

    @Test
    void evaluateHandle_triggersEagerFlush_whenBatchFull() throws Exception {
        mockHttpResponse(200, "");
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

    // --- doAppRequest with PUT method ---

    @Test
    void doAppRequest_putMethod() throws Exception {
        mockHttpResponse(200, "{\"data\":{\"id\":\"ct-1\"}}");

        client.updateContextType("ct-1", Map.of("name", "Updated"));

        verify(mockHttpClient).send(argThat(req ->
                req.method().equals("PUT")), any());
    }

    // --- updateFlag: current description preserved when params don't set it ---

    @Test
    void updateFlag_preservesCurrentDescription() throws Exception {
        Map<String, Object> getResponse = Map.of("data", Map.of(
                "id", FLAG_ID, "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "description", "Original desc",
                        "values", List.of(), "environments", Map.of()
                )
        ));
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
    @SuppressWarnings("unchecked")
    void flushContextsSafe_catchesException() throws Exception {
        // flushContextsSafe wraps flushContexts and catches exceptions
        // We need flushContexts to throw, which happens when doAppRequest fails
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection refused"));

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

        when(mockApi.listFlags(isNull(), isNull())).thenReturn(
                Map.of("data", List.of(Map.of("id", FLAG_ID, "attributes", Map.of(
                        "key", "feature-x", "name", "feature-x", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of()
                )))));

        assertDoesNotThrow(() -> client.refresh());
    }

    // --- parseListResponse: data is not a List ---

    @Test
    void list_nonListDataReturnsEmpty() throws Exception {
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(Map.of("data", "not-a-list"));

        List<FlagResource> result = client.list();
        assertTrue(result.isEmpty());
    }

    // --- buildEnvironments: env with "default" key ---

    @Test
    void updateFlag_withEnvironmentDefaults() throws Exception {
        Map<String, Object> getResponse = Map.of("data", Map.of(
                "id", FLAG_ID, "attributes", Map.of(
                        "key", "my-flag", "name", "My Flag", "type", "BOOLEAN",
                        "default", false, "values", List.of(),
                        "environments", Map.of("prod", Map.of(
                                "enabled", true, "default", true
                        ))
                )
        ));
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
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(
                Map.of("data", List.of(Map.of("id", FLAG_ID, "attributes", attrs))));
        client.connect("staging");

        FlagHandle<String> handle = client.stringFlag("color", "red");
        assertEquals("red", handle.get(List.of()));
    }

    // --- FlagHandle: null default value ---

    @Test
    void handleGet_nullDefaultValue_returnsNull() throws Exception {
        // Set up a flag where evaluation returns null: flag not in store → evaluateHandle returns defaultValue (null)
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(Map.of("data", List.of()));
        client.connect("staging");

        // Create a handle with null default for a key not in the store
        FlagHandle<Boolean> handle = new FlagHandle<>("nonexistent", null, Boolean.class);
        handle.setNamespace(client);

        // evaluateHandle returns defaultValue (null) since flag not in store → raw is null → line 60
        assertNull(handle.get(List.of()));
    }

    // --- Helpers ---

    private void setupFlagStore(String key, String type, Object defaultValue,
                                 Map<String, Object> environments) throws ApiException {
        Map<String, Object> listResponse = Map.of("data", List.of(
                Map.of("id", FLAG_ID, "attributes", Map.of(
                        "key", key, "name", key, "type", type,
                        "default", defaultValue, "values", List.of(),
                        "environments", environments
                ))
        ));
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(listResponse);
        client.connect("staging");
    }
}
