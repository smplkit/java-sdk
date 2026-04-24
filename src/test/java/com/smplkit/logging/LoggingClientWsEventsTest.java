package com.smplkit.logging;

import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;
import com.smplkit.logging.adapters.DiscoveredLogger;
import com.smplkit.logging.adapters.LoggingAdapter;
import com.smplkit.LogLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for LoggingClient WS event behaviors:
 * - logger_changed: scoped fetch, diff-based listener firing
 * - logger_deleted: remove from cache, fire listener with deleted=true, no fetch
 * - group_changed: scoped fetch, diff-based
 * - group_deleted: remove from cache, fire listener with deleted=true, no fetch
 * - loggers_changed: full refetch of loggers+groups, diff-based firing, global listener fires once
 */
class LoggingClientWsEventsTest {

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
    }

    // -----------------------------------------------------------------------
    // E. logger_changed — scoped fetch, diff-based
    // -----------------------------------------------------------------------

    @Test
    void loggerChanged_contentChanged_scopedFetch_keyedListenerFires() throws ApiException {
        // Start with managed logger at INFO
        stubManagedLoggerStart("com.acme.service", "INFO");
        client.registerAdapter(noopAdapter("com.acme.service", "INFO"));
        client.start();

        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        client.onChange("com.acme.service", received::set);
        received.set(null); // clear start event

        // getLogger returns WARN — different from INFO
        when(mockLoggersApi.getLogger("com.acme.service"))
                .thenReturn(buildLoggerResponse("com.acme.service", "WARN", true));

        client.simulateLoggerChanged(Map.of("id", "com.acme.service"));

        assertNotNull(received.get(), "Key-scoped listener should fire when level changes");
        assertEquals("com.acme.service", received.get().id());
        assertEquals("websocket", received.get().source());
        assertFalse(received.get().isDeleted());
        verify(mockLoggersApi, times(1)).getLogger("com.acme.service");
        verify(mockLoggersApi, times(1)).listLoggers((Boolean) null, null, null); // only start
    }

    @Test
    void loggerChanged_contentUnchanged_listenerDoesNotFire() throws ApiException {
        stubManagedLoggerStart("com.acme.service", "INFO");
        client.registerAdapter(noopAdapter("com.acme.service", "INFO"));
        client.start();

        AtomicInteger count = new AtomicInteger();
        client.onChange("com.acme.service", e -> count.incrementAndGet());
        count.set(0); // clear start event

        // getLogger returns same level
        when(mockLoggersApi.getLogger("com.acme.service"))
                .thenReturn(buildLoggerResponse("com.acme.service", "INFO", true));

        client.simulateLoggerChanged(Map.of("id", "com.acme.service"));

        assertEquals(0, count.get(), "Listener should not fire when level is unchanged");
    }

    @Test
    void loggerChanged_missingId_isNoOp() throws ApiException {
        stubEmptyStart();
        client.start();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateLoggerChanged(Map.of()); // no "id"

        assertEquals(0, count.get());
        verify(mockLoggersApi, never()).getLogger(any());
    }

    @Test
    void loggerChanged_globalListenerNotFiredByScopedEvent() throws ApiException {
        // Global listener should NOT fire for logger_changed; only loggers_changed fires global
        stubManagedLoggerStart("com.acme.service", "INFO");
        client.registerAdapter(noopAdapter("com.acme.service", "INFO"));
        client.start();

        AtomicInteger globalCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());
        globalCount.set(0); // clear start event

        when(mockLoggersApi.getLogger("com.acme.service"))
                .thenReturn(buildLoggerResponse("com.acme.service", "WARN", true));

        client.simulateLoggerChanged(Map.of("id", "com.acme.service"));

        assertEquals(0, globalCount.get(), "Global listener should not fire for scoped logger_changed");
    }

    // -----------------------------------------------------------------------
    // E. logger_deleted — remove from cache, fire listener, no fetch
    // -----------------------------------------------------------------------

    @Test
    void loggerDeleted_removesFromCache_firesListenerWithDeletedTrue() throws ApiException {
        stubManagedLoggerStart("com.acme.service", "INFO");
        client.registerAdapter(noopAdapter("com.acme.service", "INFO"));
        client.start();

        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        client.onChange("com.acme.service", received::set);
        received.set(null);

        client.simulateLoggerDeleted(Map.of("id", "com.acme.service"));

        assertNotNull(received.get(), "Listener should fire on delete");
        assertEquals("com.acme.service", received.get().id());
        assertTrue(received.get().isDeleted());
        assertNull(received.get().level(), "Level should be null for delete events");
        // No fetch
        verify(mockLoggersApi, never()).getLogger(any());
    }

    @Test
    void loggerDeleted_beforeStart_isNoOp() throws ApiException {
        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateLoggerDeleted(Map.of("id", "com.acme.service"));

        assertEquals(0, count.get());
        verify(mockLoggersApi, never()).getLogger(any());
    }

    @Test
    void loggerDeleted_missingId_isNoOp() throws ApiException {
        stubEmptyStart();
        client.start();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateLoggerDeleted(Map.of()); // no "id"

        assertEquals(0, count.get());
    }

    // -----------------------------------------------------------------------
    // E. group_changed — scoped fetch, diff-based
    // -----------------------------------------------------------------------

    @Test
    void groupChanged_scopedFetch_isCalledNotListLoggers() throws ApiException {
        stubEmptyStart();
        client.start();

        when(mockLogGroupsApi.getLogGroup("my-group"))
                .thenReturn(buildGroupResponse("my-group", "INFO"));

        client.simulateGroupChanged(Map.of("id", "my-group"));

        verify(mockLogGroupsApi, times(1)).getLogGroup("my-group");
        verify(mockLoggersApi, times(1)).listLoggers((Boolean) null, null, null); // only start
    }

    @Test
    void groupChanged_missingId_isNoOp() throws ApiException {
        stubEmptyStart();
        client.start();

        client.simulateGroupChanged(Map.of()); // no "id"

        verify(mockLogGroupsApi, never()).getLogGroup(any());
    }

    @Test
    void groupChanged_beforeStart_isNoOp() throws ApiException {
        client.simulateGroupChanged(Map.of("id", "some-group"));

        verify(mockLogGroupsApi, never()).getLogGroup(any());
    }

    // -----------------------------------------------------------------------
    // E. group_deleted — remove from cache, fire listener, no fetch
    // -----------------------------------------------------------------------

    @Test
    void groupDeleted_firesListenerWithDeletedTrue() throws ApiException {
        stubEmptyStart();
        client.start();

        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        client.onChange("my-group", received::set);

        client.simulateGroupDeleted(Map.of("id", "my-group"));

        assertNotNull(received.get());
        assertEquals("my-group", received.get().id());
        assertTrue(received.get().isDeleted());
        verify(mockLogGroupsApi, never()).getLogGroup(any());
    }

    @Test
    void groupDeleted_beforeStart_isNoOp() throws ApiException {
        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateGroupDeleted(Map.of("id", "my-group"));

        assertEquals(0, count.get());
    }

    @Test
    void groupDeleted_missingId_isNoOp() throws ApiException {
        stubEmptyStart();
        client.start();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateGroupDeleted(Map.of());

        assertEquals(0, count.get());
    }

    // -----------------------------------------------------------------------
    // E. loggers_changed — full refetch, diff-based, global listener fires once
    // -----------------------------------------------------------------------

    @Test
    void loggersChanged_fullFetch_bothLoggersAndGroups() throws ApiException {
        stubEmptyStart();
        client.start();

        // Second listLoggers call returns same empty list
        LoggerListResponse emptyLoggers = new LoggerListResponse();
        emptyLoggers.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers((Boolean) null, null, null)).thenReturn(emptyLoggers);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(emptyGroups);

        client.simulateLoggersChanged(Map.of());

        // listLoggers called once for start, once for loggers_changed
        verify(mockLoggersApi, times(2)).listLoggers((Boolean) null, null, null);
        // listLogGroups called once for start, once for loggers_changed
        verify(mockLogGroupsApi, times(2)).listLogGroups();
    }

    @Test
    void loggersChanged_beforeStart_isNoOp() throws ApiException {
        client.simulateLoggersChanged(Map.of());

        verify(mockLoggersApi, never()).listLoggers(any(), any(), any());
    }

    @Test
    void loggersChanged_globalListenerFiresOnce_notPerChangedKey() throws ApiException {
        // Start with managed logger at INFO
        stubManagedLoggerStart("com.acme.svc", "INFO");
        client.registerAdapter(noopAdapter("com.acme.svc", "INFO"));
        client.start();

        AtomicInteger globalCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());
        globalCount.set(0); // clear start events

        // loggers_changed returns WARN — different level triggers diff
        LoggerResource lr = buildLoggerResource("com.acme.svc", "WARN", true);
        LoggerListResponse updatedLoggers = new LoggerListResponse();
        updatedLoggers.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers((Boolean) null, null, null)).thenReturn(updatedLoggers);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(emptyGroups);

        client.simulateLoggersChanged(Map.of());

        assertEquals(1, globalCount.get(), "Global listener should fire exactly once per loggers_changed");
    }

    // -----------------------------------------------------------------------
    // LoggerChangeEvent.isDeleted()
    // -----------------------------------------------------------------------

    @Test
    void loggerChangeEvent_isDeletedFalseByDefault() {
        LoggerChangeEvent event = new LoggerChangeEvent("logger", LogLevel.INFO, "websocket");
        assertFalse(event.isDeleted());
    }

    @Test
    void loggerChangeEvent_isDeletedTrueWhenSet() {
        LoggerChangeEvent event = new LoggerChangeEvent("logger", null, "websocket", true);
        assertTrue(event.isDeleted());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubEmptyStart() throws ApiException {
        LoggerListResponse emptyLoggers = new LoggerListResponse();
        emptyLoggers.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers((Boolean) null, null, null)).thenReturn(emptyLoggers);

        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(emptyGroups);

        client.registerAdapter(noopAdapter(null, null));
    }

    private void stubManagedLoggerStart(String key, String level) throws ApiException {
        LoggerResource lr = buildLoggerResource(key, level, true);
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers((Boolean) null, null, null)).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(groupResp);
    }

    private LoggingAdapter noopAdapter(String loggerKey, String level) throws ApiException {
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        if (loggerKey != null) {
            when(adapter.discover()).thenReturn(List.of(new DiscoveredLogger(loggerKey, level)));
        } else {
            when(adapter.discover()).thenReturn(List.of());
        }
        return adapter;
    }

    private LoggerResource buildLoggerResource(String id, String level, boolean managed) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, null, now, now);
        attrs.setName(id);
        if (level != null) attrs.setLevel(level);
        attrs.setManaged(managed);

        LoggerResource resource = new LoggerResource();
        resource.setId(id);
        resource.setType(LoggerResource.TypeEnum.LOGGER);
        resource.setAttributes(attrs);
        return resource;
    }

    private LoggerResponse buildLoggerResponse(String id, String level, boolean managed) {
        LoggerResource resource = buildLoggerResource(id, level, managed);
        LoggerResponse resp = new LoggerResponse();
        resp.setData(resource);
        return resp;
    }

    private LogGroupResponse buildGroupResponse(String id, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(now, now);
        attrs.setName(id);
        if (level != null) attrs.setLevel(level);

        LogGroupResource data = new LogGroupResource();
        data.setId(id);
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);

        LogGroupResponse resp = new LogGroupResponse();
        resp.setData(data);
        return resp;
    }
}
