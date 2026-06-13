package com.smplkit.logging;

import com.smplkit.LogLevel;
import com.smplkit.errors.NotFoundError;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupCreateRequest;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupRequest;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;
import com.smplkit.internal.generated.logging.model.LoggerBulkRequest;
import com.smplkit.internal.generated.logging.model.LoggerRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRUD tests for the fused logging client's {@code loggers} and {@code logGroups}
 * sub-clients — they expose the management surface directly off a wired
 * {@link LoggingClient}, so each path needs its own mock-API coverage. The wired
 * constructor borrows the generated {@code *Api} mocks; CRUD never triggers the
 * live install.
 */
class LoggersClientCrudTest {

    private LoggersApi mockLoggersApi;
    private LogGroupsApi mockLogGroupsApi;
    private LoggingClient client;
    private LoggersClient loggers;
    private LogGroupsClient groups;

    @BeforeEach
    void setUp() {
        mockLoggersApi = mock(LoggersApi.class);
        mockLogGroupsApi = mock(LogGroupsApi.class);
        client = new LoggingClient(mockLoggersApi, mockLogGroupsApi,
                HttpClient.newHttpClient(), "test-key");
        loggers = client.loggers;
        groups = client.logGroups;
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
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(resp);

        List<Logger> out = loggers.list();
        assertEquals(2, out.size());
        assertEquals("a", out.get(0).getId());
        assertEquals("b", out.get(1).getId());
    }

    @Test
    void loggers_list_withNullData_returnsEmpty() throws ApiException {
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(null);
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(resp);

        assertTrue(loggers.list().isEmpty());
    }

    @Test
    void loggers_list_mapsApiException() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
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
    void loggers_saveLogger_putsAndReturnsModel() throws ApiException {
        when(mockLoggersApi.updateLogger(eq("my.key"), any(LoggerRequest.class)))
                .thenReturn(buildLoggerResponse("my.key", "My Key", "WARN"));

        Logger lg = loggers.new_("my.key");
        lg.setLevel(LogLevel.WARN);
        lg.save();

        assertEquals("WARN", lg.getLevel());
        verify(mockLoggersApi).updateLogger(eq("my.key"), any());
    }

    @Test
    void loggers_saveLogger_mapsApiException() throws ApiException {
        when(mockLoggersApi.updateLogger(any(), any())).thenThrow(new ApiException(422, "validation"));
        Logger lg = loggers.new_("bad");
        assertThrows(RuntimeException.class, lg::save);
    }

    // -------------------------------------------------------------- register / flush

    @Test
    void loggers_register_flushTrue_buildsBulkRequest() throws ApiException {
        List<LoggerSource> sources = List.of(
                new LoggerSource("svc.a", LogLevel.DEBUG, LogLevel.INFO, "svc-a", "prod"),
                new LoggerSource("svc.b", LogLevel.WARN, "svc-b", "stg"));
        ArgumentCaptor<LoggerBulkRequest> captor = ArgumentCaptor.forClass(LoggerBulkRequest.class);

        loggers.register(sources, true);

        verify(mockLoggersApi).bulkRegisterLoggers(captor.capture());
        LoggerBulkRequest req = captor.getValue();
        assertEquals(2, req.getLoggers().size());
        assertEquals("svc.a", req.getLoggers().get(0).getId());
        // resolvedLevel always written; explicit level written only when set
        assertEquals("INFO", req.getLoggers().get(0).getLevel());
        assertEquals("DEBUG", req.getLoggers().get(0).getResolvedLevel());
        assertEquals("svc-a", req.getLoggers().get(0).getService());
        assertEquals("prod", req.getLoggers().get(0).getEnvironment());
        // Second source has no explicit level — JsonNullable level stays absent
        assertEquals("svc.b", req.getLoggers().get(1).getId());
        assertNull(req.getLoggers().get(1).getLevel());
        assertEquals("WARN", req.getLoggers().get(1).getResolvedLevel());
    }

    @Test
    void loggers_register_singleSourceWithFlush() throws ApiException {
        loggers.register(new LoggerSource("only", LogLevel.INFO, "svc", "env"), true);
        verify(mockLoggersApi).bulkRegisterLoggers(any());
    }

    @Test
    void loggers_register_null_isNoOp() throws ApiException {
        loggers.register((List<LoggerSource>) null, true);
        verify(mockLoggersApi, org.mockito.Mockito.never()).bulkRegisterLoggers(any());
    }

    @Test
    void loggers_register_buffersWithoutFlush() throws ApiException {
        loggers.register(new LoggerSource("buffered", LogLevel.INFO, "svc", "env"));
        assertEquals(1, loggers.pendingCount());
        verify(mockLoggersApi, org.mockito.Mockito.never()).bulkRegisterLoggers(any());
    }

    @Test
    void loggers_register_listWithoutFlush_buffers() throws ApiException {
        loggers.register(List.of(new LoggerSource("a", LogLevel.INFO, "svc", "env")));
        assertEquals(1, loggers.pendingCount());
    }

    @Test
    void loggers_flush_isNoOpWhenBufferEmpty() throws ApiException {
        loggers.flush();
        verify(mockLoggersApi, org.mockito.Mockito.never()).bulkRegisterLoggers(any());
    }

    @Test
    void loggers_flush_drainsBuffer() throws ApiException {
        loggers.register(new LoggerSource("com.acme.payments", LogLevel.INFO, "svc", "env"));
        loggers.flush();

        ArgumentCaptor<LoggerBulkRequest> captor = ArgumentCaptor.forClass(LoggerBulkRequest.class);
        verify(mockLoggersApi).bulkRegisterLoggers(captor.capture());
        assertEquals(1, captor.getValue().getLoggers().size());
        assertEquals("com.acme.payments", captor.getValue().getLoggers().get(0).getId());
        assertEquals(0, loggers.pendingCount());
    }

    @Test
    void loggers_flush_mapsApiException() throws ApiException {
        loggers.register(new LoggerSource("x", LogLevel.INFO, "svc", "env"));
        org.mockito.Mockito.doThrow(new ApiException(409, "conflict"))
                .when(mockLoggersApi).bulkRegisterLoggers(any());
        assertThrows(RuntimeException.class, () -> loggers.flush());
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
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(resp);

        List<LogGroup> out = groups.list();
        assertEquals(2, out.size());
        assertEquals("g1", out.get(0).getId());
    }

    @Test
    void groups_list_withNullData_returnsEmpty() throws ApiException {
        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(null);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(resp);

        assertTrue(groups.list().isEmpty());
    }

    @Test
    void groups_list_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenThrow(new ApiException(500, "boom"));
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

    @Test
    void groups_saveGroup_createsWhenUnsaved() throws ApiException {
        when(mockLogGroupsApi.createLogGroup(any(LogGroupCreateRequest.class)))
                .thenReturn(buildGroupResponse("g", "G", "INFO"));

        LogGroup grp = groups.new_("g");
        grp.save();

        verify(mockLogGroupsApi).createLogGroup(any(LogGroupCreateRequest.class));
    }

    @Test
    void groups_saveGroup_updatesWhenExisting() throws ApiException {
        when(mockLogGroupsApi.getLogGroup("g")).thenReturn(buildGroupResponse("g", "G", "INFO"));
        when(mockLogGroupsApi.updateLogGroup(eq("g"), any(LogGroupRequest.class)))
                .thenReturn(buildGroupResponse("g", "G2", "WARN"));

        LogGroup grp = groups.get("g"); // has createdAt set -> save() PUTs
        grp.setName("G2");
        grp.save();

        verify(mockLogGroupsApi).updateLogGroup(eq("g"), any(LogGroupRequest.class));
        assertEquals("G2", grp.getName());
    }

    @Test
    void groups_saveGroup_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.createLogGroup(any())).thenThrow(new ApiException(422, "validation"));
        LogGroup grp = groups.new_("bad");
        assertThrows(RuntimeException.class, grp::save);
    }

    // -------------------------------------------------------------- Logger.delete() / LogGroup.delete()

    @Test
    void logger_activeRecord_delete_callsApi() throws ApiException {
        Logger lg = loggers.new_("doomed");
        lg.delete();
        verify(mockLoggersApi).deleteLogger("doomed");
    }

    @Test
    void logger_activeRecord_delete_bubblesNotFound() throws ApiException {
        org.mockito.Mockito.doThrow(new ApiException(404, "missing"))
                .when(mockLoggersApi).deleteLogger("ghost");
        Logger lg = loggers.new_("ghost");
        assertThrows(NotFoundError.class, lg::delete);
    }

    @Test
    void logGroup_activeRecord_delete_callsApi() throws ApiException {
        LogGroup grp = groups.new_("doomed-group");
        grp.delete();
        verify(mockLogGroupsApi).deleteLogGroup("doomed-group");
    }

    @Test
    void logGroup_activeRecord_delete_bubblesNotFound() throws ApiException {
        org.mockito.Mockito.doThrow(new ApiException(404, "missing"))
                .when(mockLogGroupsApi).deleteLogGroup("ghost-group");
        LogGroup grp = groups.new_("ghost-group");
        assertThrows(NotFoundError.class, grp::delete);
    }

    // -------------------------------------------------------------- helpers

    private static com.smplkit.internal.generated.logging.model.LogLevel tolerantLogLevel(String value) {
        if (value == null) return null;
        try { return com.smplkit.internal.generated.logging.model.LogLevel.fromValue(value); }
        catch (IllegalArgumentException e) { return null; }
    }

    private LoggerResource buildLoggerResource(String id, String name, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, null, now, now);
        attrs.setName(name);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));
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
        if (level != null) attrs.setLevel(tolerantLogLevel(level));

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
