package com.smplkit.logging;

import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaginationTest {

    private LoggersApi mockLoggersApi;
    private LogGroupsApi mockLogGroupsApi;
    private LoggingClient client;

    @BeforeEach
    void setUp() {
        mockLoggersApi = mock(LoggersApi.class);
        mockLogGroupsApi = mock(LogGroupsApi.class);
        client = new LoggingClient(mockLoggersApi, mockLogGroupsApi,
                HttpClient.newHttpClient(), "test-key");
        client.setEnvironment("production");
        client.setService("test-service");
    }

    @Test
    void runtime_loggers_singlePageExit() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull()))
                .thenReturn(loggers(2));
        when(mockLogGroupsApi.listLogGroups(isNull(), eq(1), eq(1000), isNull()))
                .thenReturn(groups(0));

        client.install();

        verify(mockLoggersApi, times(1)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull());
    }

    @Test
    void runtime_loggers_multiPageExit_loopsUntilShortPage() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull()))
                .thenReturn(loggers(1000));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(1000), isNull()))
                .thenReturn(loggers(5));
        when(mockLogGroupsApi.listLogGroups(isNull(), eq(1), eq(1000), isNull()))
                .thenReturn(groups(0));

        client.install();

        verify(mockLoggersApi, times(1)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull());
        verify(mockLoggersApi, times(1)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(1000), isNull());
    }

    @Test
    void runtime_groups_multiPageExit_loopsUntilShortPage() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(1000), isNull()))
                .thenReturn(loggers(0));
        when(mockLogGroupsApi.listLogGroups(isNull(), eq(1), eq(1000), isNull()))
                .thenReturn(groups(1000));
        when(mockLogGroupsApi.listLogGroups(isNull(), eq(2), eq(1000), isNull()))
                .thenReturn(groups(7));

        client.install();

        verify(mockLogGroupsApi, times(1)).listLogGroups(isNull(), eq(1), eq(1000), isNull());
        verify(mockLogGroupsApi, times(1)).listLogGroups(isNull(), eq(2), eq(1000), isNull());
    }

    @Test
    void management_logger_listWithPagination_passesThrough() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(3), eq(20), isNull()))
                .thenReturn(loggers(1));

        List<Logger> result = client.management().list(3, 20);

        assertEquals(1, result.size());
        verify(mockLoggersApi).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(3), eq(20), isNull());
    }

    @Test
    void management_group_listWithPagination_passesThrough() throws ApiException {
        when(mockLogGroupsApi.listLogGroups(isNull(), eq(2), eq(10), isNull()))
                .thenReturn(groups(2));

        List<LogGroup> result = client.management().listGroups(2, 10);

        assertEquals(2, result.size());
        verify(mockLogGroupsApi).listLogGroups(isNull(), eq(2), eq(10), isNull());
    }

    @Test
    void mgmtLoggersClient_listWithPagination_passesThrough() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(5), eq(50), isNull()))
                .thenReturn(loggers(1));

        LoggersClient lc = new LoggersClient(client);
        List<Logger> result = lc.list(5, 50);

        assertEquals(1, result.size());
    }

    @Test
    void mgmtLogGroupsClient_listWithPagination_passesThrough() throws ApiException {
        when(mockLogGroupsApi.listLogGroups(isNull(), eq(2), eq(100), isNull()))
                .thenReturn(groups(3));

        LogGroupsClient lgc = new LogGroupsClient(client);
        List<LogGroup> result = lgc.list(2, 100);

        assertEquals(3, result.size());
    }

    @Test
    void asyncLoggers_listWithPagination_delegatesToSync() throws Exception {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(25), isNull()))
                .thenReturn(loggers(1));

        AsyncLoggersClient async = new AsyncLoggersClient(
                new LoggersClient(client), Executors.newSingleThreadExecutor());

        List<Logger> result = async.list(2, 25).get();

        assertEquals(1, result.size());
    }

    @Test
    void asyncLogGroups_listWithPagination_delegatesToSync() throws Exception {
        when(mockLogGroupsApi.listLogGroups(isNull(), eq(3), eq(15), isNull()))
                .thenReturn(groups(1));

        AsyncLogGroupsClient async = new AsyncLogGroupsClient(
                new LogGroupsClient(client), Executors.newSingleThreadExecutor());

        List<LogGroup> result = async.list(3, 15).get();

        assertEquals(1, result.size());
    }

    private static LoggerListResponse loggers(int count) {
        LoggerListResponse resp = new LoggerListResponse();
        List<LoggerResource> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LoggerResource r = new LoggerResource();
            r.setId("lg-" + i);
            r.setType(LoggerResource.TypeEnum.LOGGER);
            var attrs = new com.smplkit.internal.generated.logging.model.Logger();
            attrs.setName("lg-" + i);
            attrs.setLevel(com.smplkit.internal.generated.logging.model.LogLevel.INFO);
            attrs.setManaged(false);
            r.setAttributes(attrs);
            data.add(r);
        }
        resp.setData(data);
        return resp;
    }

    private static LogGroupListResponse groups(int count) {
        LogGroupListResponse resp = new LogGroupListResponse();
        List<LogGroupResource> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LogGroupResource r = new LogGroupResource();
            r.setId("grp-" + i);
            r.setType(LogGroupResource.TypeEnum.LOG_GROUP);
            var attrs = new com.smplkit.internal.generated.logging.model.LogGroup();
            attrs.setName("grp-" + i);
            r.setAttributes(attrs);
            data.add(r);
        }
        resp.setData(data);
        return resp;
    }
}
