package com.smplkit.flags;

import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.Flag;
import com.smplkit.internal.generated.flags.model.FlagEnvironment;
import com.smplkit.internal.generated.flags.model.FlagRule;
import com.smplkit.internal.generated.flags.model.FlagValue;
import com.smplkit.internal.generated.flags.model.ResourceFlag;
import com.smplkit.internal.generated.flags.model.ResponseFlag;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlagsClientTest {

    private FlagsApi mockApi;
    private FlagsClient client;
    private static final String TEST_FLAG_ID = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        client = new FlagsClient(mockApi, HttpClient.newHttpClient(), "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));
    }

    // --- Management CRUD ---

    @Test
    void create_sendsCorrectRequest() throws ApiException {
        Map<String, Object> response = makeApiResponse(TEST_FLAG_ID, "my-flag", "My Flag",
                "BOOLEAN", false, List.of(
                        Map.of("name", "True", "value", true),
                        Map.of("name", "False", "value", false)
                ), Map.of());
        when(mockApi.createFlag(any(ResponseFlag.class))).thenReturn(response);

        FlagResource result = client.create(CreateFlagParams.builder(
                "my-flag", "My Flag", FlagType.BOOLEAN).defaultValue(false).build());

        assertEquals("my-flag", result.key());
        assertEquals("My Flag", result.name());
        verify(mockApi).createFlag(any(ResponseFlag.class));
    }

    @Test
    void get_fetchesByUuid() throws ApiException {
        Map<String, Object> response = makeApiResponse(TEST_FLAG_ID, "my-flag", "My Flag",
                "BOOLEAN", false, List.of(), Map.of());
        when(mockApi.getFlag(UUID.fromString(TEST_FLAG_ID))).thenReturn(response);

        FlagResource result = client.get(TEST_FLAG_ID);
        assertEquals(TEST_FLAG_ID, result.id());
        assertEquals("my-flag", result.key());
    }

    @Test
    void get_notFound_throwsSmplNotFoundException() throws ApiException {
        when(mockApi.getFlag(any(UUID.class)))
                .thenThrow(new ApiException(404, "Not Found"));

        assertThrows(SmplNotFoundException.class, () -> client.get(TEST_FLAG_ID));
    }

    @Test
    void list_returnsAllFlags() throws ApiException {
        Map<String, Object> listResponse = Map.of("data", List.of(
                Map.of("id", TEST_FLAG_ID, "attributes", Map.of(
                        "key", "flag-1", "name", "Flag 1", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of()
                )),
                Map.of("id", "22222222-2222-2222-2222-222222222222", "attributes", Map.of(
                        "key", "flag-2", "name", "Flag 2", "type", "STRING",
                        "default", "red", "values", List.of(), "environments", Map.of()
                ))
        ));
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(listResponse);

        List<FlagResource> result = client.list();
        assertEquals(2, result.size());
        assertEquals("flag-1", result.get(0).key());
        assertEquals("flag-2", result.get(1).key());
    }

    @Test
    void delete_callsApi() throws ApiException {
        client.delete(TEST_FLAG_ID);
        verify(mockApi).deleteFlag(UUID.fromString(TEST_FLAG_ID));
    }

    // --- Prescriptive tier: Handles and Evaluation ---

    @Test
    void boolFlag_returnsHandle() {
        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertEquals("feature-x", handle.key());
        assertFalse(handle.defaultValue());
    }

    @Test
    void stringFlag_returnsHandle() {
        FlagHandle<String> handle = client.stringFlag("color", "red");
        assertEquals("color", handle.key());
        assertEquals("red", handle.defaultValue());
    }

    @Test
    void numberFlag_returnsHandle() {
        FlagHandle<Number> handle = client.numberFlag("rate-limit", 100);
        assertEquals("rate-limit", handle.key());
        assertEquals(100, handle.defaultValue());
    }

    @Test
    void jsonFlag_returnsHandle() {
        FlagHandle<Object> handle = client.jsonFlag("config", Map.of("a", 1));
        assertEquals("config", handle.key());
    }

    @Test
    void handleGet_returnsDefault_whenNotConnected() {
        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertFalse(handle.get());
    }

    @Test
    void handleGet_evaluatesWithLocalJsonLogic() throws ApiException {
        // Set up mock to return a flag with rules
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Enterprise only",
                                "logic", Map.of("==", List.of(Map.of("var", "user.plan"), "enterprise")),
                                "value", true
                        ))
                )
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);

        // Evaluate with matching context
        Context userCtx = new Context("user", "u-1", Map.of("plan", "enterprise"));
        assertTrue(handle.get(List.of(userCtx)));
    }

    @Test
    void handleGet_returnsDefault_whenNoRuleMatch() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Enterprise only",
                                "logic", Map.of("==", List.of(Map.of("var", "user.plan"), "enterprise")),
                                "value", true
                        ))
                )
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);

        // Evaluate with non-matching context
        Context userCtx = new Context("user", "u-1", Map.of("plan", "free"));
        assertFalse(handle.get(List.of(userCtx)));
    }

    @Test
    void handleGet_returnsEnvDefault_whenDisabled() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", false,
                        "default", true
                )
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void handleGet_returnsFlagDefault_whenEnvNotFound() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of());

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertFalse(handle.get(List.of()));
    }

    @Test
    void handleGet_returnsCodeDefault_whenFlagNotInStore() throws ApiException {
        // Connect with empty store
        setupEmptyFlagStore();

        FlagHandle<String> handle = client.stringFlag("unknown-flag", "fallback");
        assertEquals("fallback", handle.get(List.of()));
    }

    @Test
    void stringHandle_rejectsNonStringValue() throws ApiException {
        setupFlagStore("color", "STRING", "red", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Always 42",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", 42
                        ))
                )
        ));

        FlagHandle<String> handle = client.stringFlag("color", "red");
        // Rule returns 42 (int), but handle expects String → returns default
        assertEquals("red", handle.get(List.of()));
    }

    @Test
    void numberHandle_rejectsBoolean() throws ApiException {
        setupFlagStore("limit", "NUMERIC", 100, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Always true",
                                "logic", Map.of("==", List.of(1, 1)),
                                "value", true
                        ))
                )
        ));

        FlagHandle<Number> handle = client.numberFlag("limit", 100);
        assertEquals(100, handle.get(List.of()));
    }

    // --- Cache ---

    @Test
    void stats_tracksCacheHitsAndMisses() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "default", true
                )
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        List<Context> ctx = List.of();

        // First call = miss
        handle.get(ctx);
        // Second call = hit (same context)
        handle.get(ctx);
        // Third call = hit
        handle.get(ctx);

        FlagStats stats = client.stats();
        assertEquals(2, stats.cacheHits());
        assertEquals(1, stats.cacheMisses());
    }

    // --- Context provider ---

    @Test
    void contextProvider_calledDuringEvaluation() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
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
                new Context("user", "u-1", Map.of("plan", "premium"))
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        assertTrue(handle.get());
    }

    @Test
    void explicitContext_overridesProvider() throws ApiException {
        setupFlagStore("feature-x", "BOOLEAN", false, Map.of(
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
                new Context("user", "u-1", Map.of("plan", "premium"))
        ));

        FlagHandle<Boolean> handle = client.boolFlag("feature-x", false);
        // Explicit context with "free" plan should NOT match
        assertFalse(handle.get(List.of(
                new Context("user", "u-2", Map.of("plan", "free"))
        )));
    }

    // --- Change listeners ---

    @Test
    void onChange_globalListenerFires() throws ApiException {
        setupEmptyFlagStore();

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        // Simulate refresh which fires listeners
        setupFlagStoreForRefresh("my-flag", "BOOLEAN", false, Map.of());
        client.refresh();

        assertNotNull(received.get());
        assertEquals("my-flag", received.get().key());
        assertEquals("manual", received.get().source());
    }

    @Test
    void connectionStatus_reflectsState() {
        assertEquals("disconnected", client.connectionStatus());
    }

    // --- Exception mapping ---

    @Test
    void apiException404_throwsSmplNotFoundException() throws ApiException {
        when(mockApi.getFlag(any(UUID.class)))
                .thenThrow(new ApiException(404, "Not Found"));
        assertThrows(SmplNotFoundException.class, () -> client.get(TEST_FLAG_ID));
    }

    @Test
    void apiException422_throwsSmplValidationException() throws ApiException {
        when(mockApi.createFlag(any(ResponseFlag.class)))
                .thenThrow(new ApiException(422, "Validation Error"));
        assertThrows(SmplValidationException.class,
                () -> client.create(CreateFlagParams.builder("k", "n", FlagType.BOOLEAN).build()));
    }

    // --- Helper methods ---

    /**
     * Sets up the flag store by mocking the list call and calling connect internally.
     */
    private void setupFlagStore(String key, String type, Object defaultValue,
                                 Map<String, Object> environments) throws ApiException {
        Map<String, Object> listResponse = Map.of("data", List.of(
                Map.of("id", TEST_FLAG_ID, "attributes", Map.of(
                        "key", key, "name", key, "type", type,
                        "default", defaultValue, "values", List.of(),
                        "environments", environments
                ))
        ));
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(listResponse);

        // Connect to populate the store (no WS since sharedWs is null)
        client.connect("staging");
    }

    private void setupFlagStoreForRefresh(String key, String type, Object defaultValue,
                                           Map<String, Object> environments) throws ApiException {
        Map<String, Object> listResponse = Map.of("data", List.of(
                Map.of("id", TEST_FLAG_ID, "attributes", Map.of(
                        "key", key, "name", key, "type", type,
                        "default", defaultValue, "values", List.of(),
                        "environments", environments
                ))
        ));
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(listResponse);
    }

    private void setupEmptyFlagStore() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(Map.of("data", List.of()));
        client.connect("staging");
    }

    private static Map<String, Object> makeApiResponse(String id, String key, String name,
                                                         String type, Object defaultValue,
                                                         List<Map<String, Object>> values,
                                                         Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", key);
        attrs.put("name", name);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", values);
        attrs.put("environments", environments);
        return Map.of("data", Map.of(
                "id", id,
                "attributes", attrs
        ));
    }
}
