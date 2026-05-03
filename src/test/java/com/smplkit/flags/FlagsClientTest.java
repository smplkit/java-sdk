package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.Context;
import com.smplkit.SharedWebSocket;
import com.smplkit.errors.NotFoundError;
import com.smplkit.errors.ValidationError;
import com.smplkit.management.ContextRegistrationBuffer;
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
    private static final String TEST_FLAG_ID = "my-flag";

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
    void newBooleanFlag_createsUnsavedFlag() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", false);
        assertEquals("my-flag", flag.getId());
        assertEquals("BOOLEAN", flag.getType());
        assertFalse(flag.getDefault());
    }

    @Test
    void newBooleanFlag_withNameAndDescription() {
        Flag<Boolean> flag = client.management().newBooleanFlag("my-flag", true, "My Flag", "A test flag");
        assertEquals("my-flag", flag.getId());
        assertEquals("My Flag", flag.getName());
        assertEquals("A test flag", flag.getDescription());
    }

    @Test
    void newBooleanFlag_withoutName_usesKeyToDisplayName() {
        Flag<Boolean> flag = client.management().newBooleanFlag("checkout-v2", false);
        assertEquals("Checkout V2", flag.getName());
    }

    // --- Runtime handles ---

    @Test
    void booleanFlag_returnsTypedHandle() {
        Flag<Boolean> handle = client.booleanFlag("feature-x", false);
        assertEquals("feature-x", handle.getId());
        assertFalse(handle.getDefault());
        assertEquals(Boolean.class, handle.getValueType());
    }

    @Test
    void stringFlag_returnsTypedHandle() {
        Flag<String> handle = client.stringFlag("color", "red");
        assertEquals("color", handle.getId());
        assertEquals("red", handle.getDefault());
        assertEquals(String.class, handle.getValueType());
    }

    @Test
    void numberFlag_returnsTypedHandle() {
        Flag<Number> handle = client.numberFlag("rate-limit", 100);
        assertEquals("rate-limit", handle.getId());
        assertEquals(100, handle.getDefault());
        assertEquals(Number.class, handle.getValueType());
    }

    @Test
    void jsonFlag_returnsTypedHandle() {
        Flag<Object> handle = client.jsonFlag("config", Map.of("a", 1));
        assertEquals("config", handle.getId());
        assertEquals(Object.class, handle.getValueType());
    }

    // --- get(id) fetches by id via getFlag ---

    @Test
    void get_fetchesById() throws ApiException {
        when(mockApi.getFlag(eq("my-flag")))
                .thenReturn(makeFlagResponse(TEST_FLAG_ID, "My Flag",
                        "BOOLEAN", false, List.of(), Map.of()));

        Flag<?> result = client.management().get("my-flag");
        assertEquals(TEST_FLAG_ID, result.getId());
        verify(mockApi).getFlag(eq("my-flag"));
    }

    @Test
    void get_notFound_throwsNotFoundError() throws ApiException {
        when(mockApi.getFlag(eq("unknown")))
                .thenThrow(new ApiException(404, "Not Found"));

        assertThrows(NotFoundError.class, () -> client.management().get("unknown"));
    }

    @Test
    void get_apiError_throwsSmplError() throws ApiException {
        when(mockApi.getFlag(eq("my-flag")))
                .thenThrow(new ApiException(404, "Not Found"));

        assertThrows(NotFoundError.class, () -> client.management().get("my-flag"));
    }

    // --- list() returns all flags ---

    @Test
    void list_returnsAllFlags() throws ApiException {
        FlagListResponse listResponse = OBJECT_MAPPER.convertValue(Map.of("data", List.of(
                Map.of("id", "flag-1", "type", "flag", "attributes", Map.of(
                        "name", "Flag 1", "type", "BOOLEAN",
                        "default", false, "values", List.of(), "environments", Map.of()
                )),
                Map.of("id", "flag-2", "type", "flag", "attributes", Map.of(
                        "name", "Flag 2", "type", "STRING",
                        "default", "red", "values", List.of(), "environments", Map.of()
                ))
        )), FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(listResponse);

        List<Flag<?>> result = client.management().list();
        assertEquals(2, result.size());
        assertEquals("flag-1", result.get(0).getId());
        assertEquals("flag-2", result.get(1).getId());
    }

    @Test
    void list_emptyResponse() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(new FlagListResponse().data(List.of()));

        List<Flag<?>> result = client.management().list();
        assertTrue(result.isEmpty());
    }

    // --- delete(id) deletes directly ---

    @Test
    void delete_deletesDirectly() throws ApiException {
        doNothing().when(mockApi).deleteFlag("my-flag");

        client.management().delete("my-flag");

        verify(mockApi).deleteFlag("my-flag");
    }

    // --- _createFlag POSTs and returns created flag ---

    @Test
    void createFlag_postsAndReturnsCreatedFlag() throws ApiException {
        FlagResponse response = makeFlagResponse(TEST_FLAG_ID, "My Flag",
                "BOOLEAN", false, List.of(
                        Map.of("name", "True", "value", true),
                        Map.of("name", "False", "value", false)
                ), Map.of());
        when(mockApi.createFlag(any(FlagResponse.class))).thenReturn(response);

        Flag<Boolean> newFlag = client.management().newBooleanFlag("my-flag", false, "My Flag", null);
        newFlag.save();

        assertNotNull(newFlag.getId());
        assertEquals("my-flag", newFlag.getId());
        verify(mockApi).createFlag(any(FlagResponse.class));
    }

    // --- _updateFlag PUTs and returns updated flag ---

    @Test
    void updateFlag_putsAndReturnsUpdatedFlag() throws ApiException {
        FlagResponse response = makeFlagResponse(TEST_FLAG_ID, "Updated Flag",
                "BOOLEAN", false, List.of(), Map.of());
        when(mockApi.updateFlag(eq(TEST_FLAG_ID), any(FlagResponse.class)))
                .thenReturn(response);

        // Create a flag that already has createdAt (simulating a fetched flag)
        Flag<Boolean> existingFlag = client.management().newBooleanFlag("my-flag", false, "My Flag", null);
        existingFlag.setCreatedAt(java.time.Instant.parse("2024-06-01T12:00:00Z"));
        existingFlag.setName("Updated Flag");
        existingFlag.save();

        assertEquals("Updated Flag", existingFlag.getName());
        verify(mockApi).updateFlag(eq(TEST_FLAG_ID), any(FlagResponse.class));
    }

    // --- Unconstrained flag (null values) ---

    @Test
    void createFlag_unconstrained_sendsNullValues() throws ApiException {
        FlagResponse response = makeFlagResponse("max-retries", "Max Retries",
                "NUMERIC", 3, null, Map.of());
        when(mockApi.createFlag(any(FlagResponse.class))).thenReturn(response);

        Flag<Number> newFlag = client.management().newNumberFlag("max-retries", 3, "Max Retries", null);
        assertNull(newFlag.getValues(), "unconstrained flag should have null values before save");
        newFlag.save();

        assertNotNull(newFlag.getId());
        assertNull(newFlag.getValues(), "unconstrained flag should have null values after save");
        verify(mockApi).createFlag(any(FlagResponse.class));
    }

    @Test
    void updateFlag_unconstrained_sendsNullValues() throws ApiException {
        FlagResponse response = makeFlagResponse("max-retries", "Max Retries",
                "NUMERIC", 5, null, Map.of());
        when(mockApi.updateFlag(eq("max-retries"), any(FlagResponse.class)))
                .thenReturn(response);

        Flag<Number> existingFlag = client.management().newNumberFlag("max-retries", 3, "Max Retries", null);
        existingFlag.setCreatedAt(java.time.Instant.parse("2024-06-01T12:00:00Z"));
        existingFlag.setDefault(5);
        existingFlag.save();

        assertNull(existingFlag.getValues(), "unconstrained flag should remain null after update");
        verify(mockApi).updateFlag(eq("max-retries"), any(FlagResponse.class));
    }

    // --- Error mapping ---

    @Test
    void apiException404_throwsNotFoundError() throws ApiException {
        when(mockApi.getFlag(anyString()))
                .thenThrow(new ApiException(404, "Not Found"));
        assertThrows(NotFoundError.class, () -> client.management().get("my-flag"));
    }

    @Test
    void apiException409_throwsConflictError() throws ApiException {
        when(mockApi.createFlag(any(FlagResponse.class)))
                .thenThrow(new ApiException(409, "Conflict"));
        Flag<Boolean> flag = client.management().newBooleanFlag("dup", false);
        assertThrows(com.smplkit.errors.ConflictError.class, flag::save);
    }

    @Test
    void apiException422_throwsValidationError() throws ApiException {
        when(mockApi.createFlag(any(FlagResponse.class)))
                .thenThrow(new ApiException(422, "Validation Error"));
        Flag<Boolean> flag = client.management().newBooleanFlag("bad-flag", false);
        assertThrows(ValidationError.class, flag::save);
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
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));

        Flag<Boolean> handle = client.booleanFlag("unknown-flag", false);
        assertFalse(handle.get());

        // _connectInternal called listFlags
        verify(mockApi, atLeastOnce()).listFlags(isNull(), isNull(), isNull(), isNull());
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
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull()))
                .thenReturn(makeFlagListResponse(TEST_FLAG_ID, "My Flag",
                        "BOOLEAN", false, Map.of()));
        client.refresh();

        assertNotNull(received.get());
        assertEquals("my-flag", received.get().id());
        assertEquals("manual", received.get().source());
    }

    // --- Helpers ---

    private void setupFlagStore(String id, String type, Object defaultValue,
                                 Map<String, Object> environments) throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull())).thenReturn(
                makeFlagListResponse(id, id, type, defaultValue, environments));
        client._connectInternal();
    }

    private static FlagResponse makeFlagResponse(String id, String name,
                                                   String type, Object defaultValue,
                                                   List<Map<String, Object>> values,
                                                   Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", name);
        attrs.put("type", type);
        attrs.put("default", defaultValue);
        attrs.put("values", values);
        attrs.put("environments", environments);
        Map<String, Object> map = Map.of("data", Map.of(
                "id", id, "type", "flag", "attributes", attrs));
        return OBJECT_MAPPER.convertValue(map, FlagResponse.class);
    }

    // -----------------------------------------------------------------------
    // Shared ContextRegistrationBuffer wiring
    // -----------------------------------------------------------------------

    @Test
    void sharedBuffer_observeDeduplicates() {
        ContextRegistrationBuffer buffer = new ContextRegistrationBuffer();
        client.setContextBuffer(buffer);

        client.register(new Context("user", "u1", Map.of("plan", "free")));
        assertEquals(1, buffer.pendingCount());

        // same key → deduped
        client.register(new Context("user", "u1", null));
        assertEquals(1, buffer.pendingCount());
    }

    @Test
    void sharedBuffer_listOverload_addsAll() {
        ContextRegistrationBuffer buffer = new ContextRegistrationBuffer();
        client.setContextBuffer(buffer);

        client.register(List.of(
                new Context("user", "u1", null),
                new Context("user", "u2", null)
        ));
        assertEquals(2, buffer.pendingCount());
    }

    @Test
    void sharedBuffer_flushContexts_drainsBuffer() throws Exception {
        ContextRegistrationBuffer buffer = new ContextRegistrationBuffer();
        client.setContextBuffer(buffer);

        client.register(new Context("user", "u1", null));
        when(mockContextsApi.bulkRegisterContexts(any())).thenReturn(null);
        client.flushContexts();

        assertEquals(0, buffer.pendingCount());
        verify(mockContextsApi).bulkRegisterContexts(any());
    }

    private static FlagListResponse makeFlagListResponse(String id, String name,
                                                          String type, Object defaultValue,
                                                          Map<String, Object> environments) {
        Map<String, Object> attrs = new HashMap<>();
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
