package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaginationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    private FlagsApi mockApi;
    private FlagsClient client;

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        ContextsApi mockContextsApi = Mockito.mock(ContextsApi.class);
        client = new FlagsClient(mockApi, mockContextsApi, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
    }

    @Test
    void runtime_singlePageExit_stopsAfterShortPage() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull()))
                .thenReturn(makeListResponse("f1"));

        client._connectInternal();

        verify(mockApi, times(1)).listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull());
        assertTrue(client.isConnected());
    }

    @Test
    void runtime_multiPageExit_loopsUntilShortPage() throws ApiException {
        List<String> page1Ids = new ArrayList<>();
        for (int i = 0; i < 1000; i++) page1Ids.add("f" + i);

        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull()))
                .thenReturn(makeListResponse(page1Ids.toArray(new String[0])));
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(1000), isNull()))
                .thenReturn(makeListResponse("f-extra"));

        client._connectInternal();

        verify(mockApi, times(1)).listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull());
        verify(mockApi, times(1)).listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(1000), isNull());
        assertTrue(client.isConnected());
    }

    @Test
    void management_listWithPaginationArgs_passesThroughToApi() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(50), isNull()))
                .thenReturn(makeListResponse("f-page2"));

        List<Flag<?>> result = client.management().list(2, 50);

        assertEquals(1, result.size());
        verify(mockApi).listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(50), isNull());
    }

    @Test
    void management_listWithNullArgs_passesNullThroughForServerDefaults() throws ApiException {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull()))
                .thenReturn(makeListResponse("f1"));

        List<Flag<?>> result = client.management().list(null, null);

        assertEquals(1, result.size());
    }

    @Test
    void asyncManagement_listWithPagination_delegatesToSync() throws Exception {
        when(mockApi.listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(3), eq(25), isNull()))
                .thenReturn(makeListResponse("f-async"));

        AsyncFlagsManagement async = new AsyncFlagsManagement(
                client.management(), Executors.newSingleThreadExecutor());

        List<Flag<?>> result = async.list(3, 25).get();

        assertEquals(1, result.size());
        verify(mockApi).listFlags(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(3), eq(25), isNull());
    }

    private static FlagListResponse makeListResponse(String... ids) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("name", id);
            attrs.put("type", "BOOLEAN");
            attrs.put("default", false);
            attrs.put("values", List.of());
            attrs.put("environments", Map.of());
            items.add(Map.of("id", id, "type", "flag", "attributes", attrs));
        }
        return OBJECT_MAPPER.convertValue(Map.of("data", items), FlagListResponse.class);
    }
}
