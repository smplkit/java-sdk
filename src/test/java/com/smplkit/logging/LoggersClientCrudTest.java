package com.smplkit.logging;

import com.smplkit.LogLevel;
import com.smplkit.errors.NotFoundError;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;
import com.smplkit.internal.generated.logging.model.LoggerBulkRequest;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRUD tests for the standalone {@link LoggersClient} / {@link LogGroupsClient}
 * facades — they wrap a {@link LoggingClient} and expose the management surface
 * directly (no {@code .management()} hop), so each path needs its own mock-API
 * coverage.
 */
class LoggersClientCrudTest {

    private LoggersApi mockLoggersApi;
    private LogGroupsApi mockLogGroupsApi;
    private LoggingClient inner;
    private LoggersClient loggers;
    private LogGroupsClient groups;

    @BeforeEach
    void setUp() {
        mockLoggersApi = mock(LoggersApi.class);
        mockLogGroupsApi = mock(LogGroupsApi.class);
        inner = new LoggingClient(mockLoggersApi, mockLogGroupsApi,
                HttpClient.newHttpClient(), "test-key");
        loggers = new LoggersClient(inner);
        groups = new LogGroupsClient(inner);
    }

    // -------------------------------------------------------------- LoggersClient

    @Test
    void loggers_get_returnsModel() throws ApiException {
        when(mockLoggersApi.getLogger("my.key")).thenReturn(buildLoggerResponse("my.key", "My Key", "INFO"));

        Logger lg = loggers.get("my.key");
        assertEquals("my.key", lg.getId());
        assertEquals("INFO", lg.getLevel());
    }

    @Test
    void loggers_get_mapsApiException() throws ApiException {
        when(mockLoggersApi.getLogger("missing")).thenThrow(new ApiException(404, "not found"));
        assertThrows(NotFoundError.class, () -> loggers.get("missing"));
    }

    @Test
    void loggers_list_returnsAll() throws ApiException {
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(new ArrayList<>(List.of(
                buildLoggerResource("a", "A", "INFO"),
                buildLoggerResource("b", "B", "DEBUG"))));
        when(mockLoggersApi.listLoggers((Boolean) null, null, null)).thenReturn(resp);

        List<Logger> out = loggers.list();
        assertEquals(2, out.size());
        assertEquals("a", out.get(0).getId());
        assertEquals("b", out.get(1).getId());
    }

    @Test
    void loggers_list_withNullData_returnsEmpty() throws ApiException {
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(null);
        when(mockLoggersApi.listLoggers((Boolean) null, null, null)).thenReturn(resp);

        assertTrue(loggers.list().isEmpty());
    }

    @Test
    void loggers_list_mapsApiException() throws ApiException {
        when(mockLoggersApi.listLoggers((Boolean) null, null, null))
                .thenThrow(new ApiException(500, "boom"));
        assertThrows(RuntimeException.class, () -> loggers.list());
    }

    @Test
    void loggers_delete_callsApi() throws ApiException {
        loggers.delete("doomed");
        verify(mockLoggersApi).deleteLogger("doomed");
    }

    @Test
    void loggers_delete_mapsApiException() throws ApiException {
        org.mockito.Mockito.doThrow(new ApiException(404, "missing"))
                .when(mockLoggersApi).deleteLogger("missing");
        assertThrows(NotFoundError.class, () -> loggers.delete("missing"));
    }

    @Test
    void loggers_registerSources_buildsBulkRequest() throws ApiException {
        List<LoggerSource> sources = List.of(
                new LoggerSource("svc.a", "svc-a", "prod", LogLevel.INFO, LogLevel.DEBUG),
                new LoggerSource("svc.b", "svc-b", "stg", LogLevel.WARN));
        ArgumentCaptor<LoggerBulkRequest> captor = ArgumentCaptor.forClass(LoggerBulkRequest.class);

        loggers.registerSources(sources);

        verify(mockLoggersApi).bulkRegisterLoggers(captor.capture());
        LoggerBulkRequest req = captor.getValue();
        assertEquals(2, req.getLoggers().size());
        assertEquals("svc.a", req.getLoggers().get(0).getId());
        assertEquals("DEBUG", req.getLoggers().get(0).getLevel());
        assertEquals("svc-a", req.getLoggers().get(0).getService());
        assertEquals("prod", req.getLoggers().get(0).getEnvironment());
        // Second source has level=null, so the JsonNullable level isn't written
        assertEquals("svc.b", req.getLoggers().get(1).getId());
    }

    @Test
    void loggers_registerSources_emptyOrNull_isNoOp() throws ApiException {
        loggers.registerSources(null);
        loggers.registerSources(List.of());
        verify(mockLoggersApi, org.mockito.Mockito.never()).bulkRegisterLoggers(any());
    }

    @Test
    void loggers_registerSources_mapsApiException() throws ApiException {
        org.mockito.Mockito.doThrow(new ApiException(409, "conflict"))
                .when(mockLoggersApi).bulkRegisterLoggers(any());
        assertThrows(RuntimeException.class, () -> loggers.registerSources(
                List.of(new LoggerSource("x", "svc", "env", LogLevel.INFO))));
    }

    // -------------------------------------------------------------- LogGroupsClient

    @Test
    void groups_get_returnsModel() throws ApiException {
        when(mockLogGroupsApi.getLogGroup("g")).thenReturn(buildGroupResponse("g", "G", "WARN"));

        LogGroup grp = groups.get("g");
        assertEquals("g", grp.getId());
        assertEquals("WARN", grp.getLevel());
    }

    @Test
    void groups_get_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.getLogGroup("missing")).thenThrow(new ApiException(404, "not found"));
        assertThrows(NotFoundError.class, () -> groups.get("missing"));
    }

    @Test
    void groups_list_returnsAll() throws ApiException {
        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(new ArrayList<>(List.of(
                buildGroupResource("g1", "G1", "INFO"),
                buildGroupResource("g2", "G2", null))));
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        List<LogGroup> out = groups.list();
        assertEquals(2, out.size());
        assertEquals("g1", out.get(0).getId());
    }

    @Test
    void groups_list_withNullData_returnsEmpty() throws ApiException {
        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(null);
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        assertTrue(groups.list().isEmpty());
    }

    @Test
    void groups_list_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.listLogGroups()).thenThrow(new ApiException(500, "boom"));
        assertThrows(RuntimeException.class, () -> groups.list());
    }

    @Test
    void groups_delete_callsApi() throws ApiException {
        groups.delete("doomed");
        verify(mockLogGroupsApi).deleteLogGroup("doomed");
    }

    @Test
    void groups_delete_mapsApiException() throws ApiException {
        org.mockito.Mockito.doThrow(new ApiException(404, "missing"))
                .when(mockLogGroupsApi).deleteLogGroup("missing");
        assertThrows(NotFoundError.class, () -> groups.delete("missing"));
    }

    // -------------------------------------------------------------- helpers

    private LoggerResource buildLoggerResource(String id, String name, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, null, now, now);
        attrs.setName(name);
        if (level != null) attrs.setLevel(level);
        attrs.setManaged(false);

        LoggerResource res = new LoggerResource();
        res.setId(id);
        res.setType(LoggerResource.TypeEnum.LOGGER);
        res.setAttributes(attrs);
        return res;
    }

    private LoggerResponse buildLoggerResponse(String id, String name, String level) {
        LoggerResponse resp = new LoggerResponse();
        resp.setData(buildLoggerResource(id, name, level));
        return resp;
    }

    private LogGroupResource buildGroupResource(String id, String name, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(now, now);
        attrs.setName(name);
        if (level != null) attrs.setLevel(level);

        LogGroupResource res = new LogGroupResource();
        res.setId(id);
        res.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        res.setAttributes(attrs);
        return res;
    }

    private LogGroupResponse buildGroupResponse(String id, String name, String level) {
        LogGroupResponse resp = new LogGroupResponse();
        resp.setData(buildGroupResource(id, name, level));
        return resp;
    }
}
