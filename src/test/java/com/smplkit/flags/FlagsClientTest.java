package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.Context;
import com.smplkit.SharedWebSocket;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.internal.generated.app.api.ContextsApi;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlagsClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private ContextsApi mockContextsApi;
    private FlagsClient client;
    private static final String TEST_FLAG_ID = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextsApi, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
    }

    // --- Factory methods return unsaved flags ---

    @Test
    void newBooleanFlag_createsUnsavedFlagWithNullId() {
        Flag<Boolean> flag = client.newBooleanFlag("my-flag", false);
        assertNull(flag.getId());
        assertEquals("my-flag", flag.getKey());
        assertEquals("BOOLEAN", flag.getType());
        assertFalse(flag.getDefault());
    }

    @Test
    void newBooleanFlag_withNameAndDescription() {
        Flag<Boolean> flag = client.newBooleanFlag("my-flag", true, "My Flag", "A test flag");
        assertNull(flag.getId());
        assertEquals("My Flag", flag.getName());
        assertEquals("A test flag", flag.getDescription());
    }

    @Test
    void newBooleanFlag_withoutName_usesKeyToDisplayName() {
        Flag<Boolean> flag = client.newBooleanFlag("checkout-v2", false);
        assertEquals("Checkout V2", flag.getName());
    }

    // --- Runtime handles ---

    @Test
    void booleanFlag_returnsTypedHandle() {
        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertEquals("feature-x", handle.getKey());
        assertFalse(handle.getDefault());
        assertEquals(Boolean.class, handle.getValueType());
    }

    @Test
    void stringFlag_returnsTypedHandle() {
        Flag<String> handle = client.stringFlag("color", "red");
        assertEquals("color", handle.getKey());
        assertEquals("red", handle.getDefault());
        assertEquals(String.class, handle.getValueType());
    }

    @Test
    void numberFlag_returnsTypedHandle() {
        Flag<Number> handle = client.numberFlag("rate-limit", 100);
        assertEquals("rate-limit", handle.getKey());
        assertEquals(100, handle.getDefault());
        assertEquals(Number.class, handle.getValueType());
    }

    @Test
    void jsonFlag_returnsTypedHandle() {
        Flag<Object> handle = client.jsonFlag("config", Map.of("a", 1));
        assertEquals("config", handle.getKey());
        assertEquals(Object.class, handle.getValueType());
    }

    // --- get(key) fetches by key via listFlags filter ---

    @Test
    void get_fetchesByKeyUsingListFilter() throws ApiException {
        when(mockApi.listFlags(eq("my-flag"), isNull()))
                .thenReturn(makeFlagListResponse(TEST_FLAG_ID, "my-flag", "My Flag",
                        "BOOLEAN", false, Map.of()));

        Flag<?> result = client.get("my-flag");
        assertEquals(TEST_FLAG_ID, result.getId());
        assertEquals("my-flag", result.getKey());
        verify(mockApi).listFlags(eq("my-flag"), isNull());
    }

    @Test
    void get_notFound_throwsSmplNotFoundException() throws ApiException {
        when(mockApi.listFlags(eq("unknown"), isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));

        assertThrows(SmplNotFoundException.class, () -> client.get("unknown"));
    }

    @Test
    void get_apiError_throwsSmplException() throws ApiException {
        when(mockApi.listFlags(eq("my-flag"), isNull()))
                .thenThrow(new ApiException(404, "Not Found"));

        assertThrows(SmplNotFoundException.class, () -> client.get("my-flag"));
    }

    // --- list() returns all flags ---

    @Test
    void list_returnsAllFlags() throws ApiException {
        FlagListResponse listResponse = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                Map.of("id", TEST_FLAG_ID, "type", "flag", "attributes", Map.of(
                        "key", "flag-1", "name", "Flag 1", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of()
                )),
                Map.of("id", "22222222-2222-2222-2222-222222222222", "type", "flag", "attributes", Map.of(
                        "key", "flag-2", "name", "Flag 2", "type", "STRING",
                        "default", "red", "values", List.of(), "environments", Map.of()
                ))
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(listResponse);

        List<Flag<?>> result = client.list();
        assertEquals(2, result.size());
        assertEquals("flag-1", result.get(0).getKey());
        assertEquals("flag-2", result.get(1).getKey());
    }

    @Test
    void list_emptyResponse() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(new FlagListResponse().data(List.of()));

        List<Flag<?>> result = client.list();
        assertTrue(result.isEmpty());
    }

    // --- delete(key) resolves key then deletes ---

    @Test
    void delete_resolvesKeyThenDeletes() throws ApiException {
        when(mockApi.listFlags(eq("my-flag"), isNull()))
                .thenReturn(makeFlagListResponse(TEST_FLAG_ID, "my-flag", "My Flag",
                        "BOOLEAN", false, Map.of()));
        doNothing().when(mockApi).deleteFlag(UUID.fromString(TEST_FLAG_ID));

        client.delete("my-flag");

        verify(mockApi).listFlags(eq("my-flag"), isNull());
        verify(mockApi).deleteFlag(UUID.fromString(TEST_FLAG_ID));
    }

    // --- _createFlag POSTs and returns created flag ---

    @Test
    void createFlag_postsAndReturnsCreatedFlag() throws ApiException {
        FlagResponse response = makeFlagResponse(TEST_FLAG_ID, "my-flag", "My Flag",
                "BOOLEAN", false, List.of(
                        Map.of("name", "True", "value", true),
                        Map.of("name", "False", "value", false)
                ), Map.of());
        when(mockApi.createFlag(any(ResponseFlag.class))).thenReturn(response);

        Flag<Boolean> newFlag = client.newBooleanFlag("my-flag", false, "My Flag", null);
        newFlag.save();

        assertNotNull(newFlag.getId());
        assertEquals("my-flag", newFlag.getKey());
        verify(mockApi).createFlag(any(ResponseFlag.class));
    }

    // --- _updateFlag PUTs and returns updated flag ---

    @Test
    void updateFlag_putsAndReturnsUpdatedFlag() throws ApiException {
        FlagResponse response = makeFlagResponse(TEST_FLAG_ID, "my-flag", "Updated Flag",
                "BOOLEAN", false, List.of(), Map.of());
        when(mockApi.updateFlag(eq(UUID.fromString(TEST_FLAG_ID)), any(ResponseFlag.class)))
                .thenReturn(response);

        // Create a flag that already has an id (simulating a fetched flag)
        Flag<Boolean> existingFlag = client.newBooleanFlag("my-flag", false, "My Flag", null);
        existingFlag.setId(TEST_FLAG_ID);
        existingFlag.setName("Updated Flag");
        existingFlag.save();

        assertEquals("Updated Flag", existingFlag.getName());
        verify(mockApi).updateFlag(eq(UUID.fromString(TEST_FLAG_ID)), any(ResponseFlag.class));
    }

    // --- Error mapping ---

    @Test
    void apiException404_throwsSmplNotFoundException() throws ApiException {
        when(mockApi.listFlags(anyString(), isNull()))
                .thenThrow(new ApiException(404, "Not Found"));
        assertThrows(SmplNotFoundException.class, () -> client.get("my-flag"));
    }

    @Test
    void apiException409_throwsSmplConflictException() throws ApiException {
        when(mockApi.createFlag(any(ResponseFlag.class)))
                .thenThrow(new ApiException(409, "Conflict"));
        Flag<Boolean> flag = client.newBooleanFlag("dup", false);
        assertThrows(com.smplkit.errors.SmplConflictException.class, flag::save);
    }

    @Test
    void apiException422_throwsSmplValidationException() throws ApiException {
        when(mockApi.createFlag(any(ResponseFlag.class)))
                .thenThrow(new ApiException(422, "Validation Error"));
        Flag<Boolean> flag = client.newBooleanFlag("bad-flag", false);
        assertThrows(SmplValidationException.class, flag::save);
    }

    // --- handleGet with lazy init and JSON logic ---

    @Test
    void handleGet_evaluatesWithLocalJsonLogic() throws ApiException {
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

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);

        // Lazy init happens on first get()
        Context userCtx = new Context("user", "u-1", Map.of("plan", "enterprise"));
        assertTrue(handle.get(List.of(userCtx)));
    }

    @Test
    void handleGet_lazyInit_callsListOnFirstEval() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));

        Flag<Boolean> handle = client.booleanFlag("unknown-flag", false);
        assertFalse(handle.get());

        // _connectInternal called listFlags
        verify(mockApi, atLeastOnce()).listFlags(isNull(), isNull());
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

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        Context userCtx = new Context("user", "u-1", Map.of("plan", "free"));
        assertFalse(handle.get(List.of(userCtx)));
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

        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertTrue(handle.get());
    }

    // --- Service context injection ---

    @Test
    void parentService_injectedIntoEvalData() throws ApiException {
        client.setParentService("my-service");
        setupFlagStore("svc-flag", "BOOLEAN", false, Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Service match",
                                "logic", Map.of("==", List.of(Map.of("var", "service.key"), "my-service")),
                                "value", true
                        ))
                )
        ));

        Flag<Boolean> handle = client.booleanFlag("svc-flag", false);
        assertTrue(handle.get(List.of()));
    }

    // --- onChange global listener ---

    @Test
    void onChange_globalListenerFires() throws ApiException {
        setupFlagStore("my-flag", "BOOLEAN", false, Map.of());

        AtomicReference<FlagChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        // Refresh fires listeners
        when(mockApi.listFlags(isNull(), isNull()))
                .thenReturn(makeFlagListResponse(TEST_FLAG_ID, "my-flag", "My Flag",
                        "BOOLEAN", false, Map.of()));
        client.refresh();

        assertNotNull(received.get());
        assertEquals("my-flag", received.get().key());
        assertEquals("manual", received.get().source());
    }

    // --- Helpers ---

    private void setupFlagStore(String key, String type, Object defaultValue,
                                 Map<String, Object> environments) throws ApiException {
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(
                makeFlagListResponse(TEST_FLAG_ID, key, key, type, defaultValue, environments));
        client._connectInternal();
    }

    private static FlagResponse makeFlagResponse(String id, String key, String name,
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
        Map<String, Object> map = Map.of("data", Map.of(
                "id", id, "type", "flag", "attributes", attrs));
        return OBJECT_MAPPER.convertValue(map, FlagResponse.class);
    }

    private static FlagListResponse makeFlagListResponse(String id, String key, String name,
                                                          String type, Object defaultValue,
                                                          Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", key);
        attrs.put("name", name);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", List.of());
        attrs.put("environments", environments);
        Map<String, Object> map = Map.of("data", List.of(Map.of(
                "id", id, "type", "flag", "attributes", attrs)));
        return OBJECT_MAPPER.convertValue(map, FlagListResponse.class);
    }
}
