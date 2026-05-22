package com.smplkit.config;

import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaginationTest {

    private ConfigsApi mockApi;
    private ConfigClient client;

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(ConfigsApi.class);
        client = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        client.setEnvironment("staging");
    }

    @Test
    void runtime_singlePageExit_stopsAfterShortPage() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), eq(1), eq(1000), isNull()))
                .thenReturn(listOf(1));

        client._connectInternal();

        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), eq(1), eq(1000), isNull());
        assertTrue(client.isConnected());
    }

    @Test
    void runtime_multiPageExit_loopsUntilShortPage() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), eq(1), eq(1000), isNull()))
                .thenReturn(listOf(1000));
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), eq(2), eq(1000), isNull()))
                .thenReturn(listOf(3));

        client._connectInternal();

        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), eq(1), eq(1000), isNull());
        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), eq(2), eq(1000), isNull());
        assertTrue(client.isConnected());
    }

    @Test
    void management_listWithPaginationArgs_passesThrough() throws ApiException {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), eq(2), eq(50), isNull()))
                .thenReturn(listOf(2));

        List<Config> result = client.management().list(2, 50);

        assertEquals(2, result.size());
        verify(mockApi).listConfigs(isNull(), isNull(), isNull(), isNull(), eq(2), eq(50), isNull());
    }

    @Test
    void asyncManagement_listWithPagination_delegatesToSync() throws Exception {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), eq(4), eq(10), isNull()))
                .thenReturn(listOf(1));

        AsyncConfigManagement async = new AsyncConfigManagement(
                client.management(), Executors.newSingleThreadExecutor());

        List<Config> result = async.list(4, 10).get();

        assertEquals(1, result.size());
        verify(mockApi).listConfigs(isNull(), isNull(), isNull(), isNull(), eq(4), eq(10), isNull());
    }

    private static ConfigListResponse listOf(int count) {
        List<ConfigResource> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ConfigResource r = new ConfigResource();
            r.setId("cfg-" + i);
            r.setType(ConfigResource.TypeEnum.CONFIG);
            var attrs = new com.smplkit.internal.generated.config.model.Config(null, null);
            attrs.setName("cfg-" + i);
            r.setAttributes(attrs);
            data.add(r);
        }
        ConfigListResponse resp = new ConfigListResponse();
        resp.setData(data);
        return resp;
    }
}
