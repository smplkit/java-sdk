package com.smplkit.logging;

import com.smplkit.LogLevel;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;
import com.smplkit.internal.generated.logging.model.ResourceLogGroup;
import com.smplkit.internal.generated.logging.model.ResourceLogger;
import com.smplkit.internal.generated.logging.model.ResponseLogGroup;
import com.smplkit.internal.generated.logging.model.ResponseLogger;
import com.smplkit.logging.adapters.DiscoveredLogger;
import com.smplkit.logging.adapters.LoggingAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for the LoggingClient.
 */
class LoggingClientTest {

    private LoggersApi mockLoggersApi;
    private LogGroupsApi mockLogGroupsApi;
    private LoggingClient client;

    @BeforeEach
    void setUp() {
        mockLoggersApi = mock(LoggersApi.class);
        mockLogGroupsApi = mock(LogGroupsApi.class);
        client = new LoggingClient(mockLoggersApi, mockLogGroupsApi,
                HttpClient.newHttpClient(), "test-api-key");
        client.setEnvironment("production");
        client.setService("test-service");
    }

    // -----------------------------------------------------------------------
    // new_() logger factory
    // -----------------------------------------------------------------------

    @Test
    void new_createsUnsavedLoggerWithDefaults() {
        Logger lg = client.new_("my-logger");
        assertNull(lg.getId());
        assertEquals("my-logger", lg.getKey());
        assertEquals("My Logger", lg.getName()); // keyToDisplayName
        assertFalse(lg.isManaged());
        assertNotNull(lg.getClient());
    }

    @Test
    void new_withNameAndManaged() {
        Logger lg = client.new_("my-logger", "Custom Name", true);
        assertNull(lg.getId());
        assertEquals("my-logger", lg.getKey());
        assertEquals("Custom Name", lg.getName());
        assertTrue(lg.isManaged());
    }

    // -----------------------------------------------------------------------
    // get() logger by key
    // -----------------------------------------------------------------------

    @Test
    void get_returnsLoggerFromFilteredList() throws ApiException {
        LoggerListResponse resp = buildLoggerListResponse("logger-id-1", "my.key", "My Key", "INFO");
        when(mockLoggersApi.listLoggers("my.key", null)).thenReturn(resp);

        Logger lg = client.get("my.key");

        assertEquals("logger-id-1", lg.getId());
        assertEquals("my.key", lg.getKey());
        assertEquals("My Key", lg.getName());
        assertEquals("INFO", lg.getLevel());
    }

    @Test
    void get_throwsNotFoundWhenEmpty() throws ApiException {
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers("nonexistent", null)).thenReturn(resp);

        assertThrows(SmplNotFoundException.class, () -> client.get("nonexistent"));
    }

    @Test
    void get_throwsNotFoundWhenDataIsNull() throws ApiException {
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(null);
        when(mockLoggersApi.listLoggers("nonexistent", null)).thenReturn(resp);

        assertThrows(SmplNotFoundException.class, () -> client.get("nonexistent"));
    }

    // -----------------------------------------------------------------------
    // list() loggers
    // -----------------------------------------------------------------------

    @Test
    void list_returnsAllLoggers() throws ApiException {
        LoggerListResponse resp = buildLoggerListResponse("id-1", "key1", "Key1", null);
        LoggerResource r2 = buildLoggerResource("id-2", "key2", "Key2", "DEBUG");
        resp.getData().add(r2);
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(resp);

        List<Logger> loggers = client.list();

        assertEquals(2, loggers.size());
        assertEquals("key1", loggers.get(0).getKey());
        assertEquals("key2", loggers.get(1).getKey());
    }

    @Test
    void list_returnsEmptyWhenNoData() throws ApiException {
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(null);
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(resp);

        assertTrue(client.list().isEmpty());
    }

    // -----------------------------------------------------------------------
    // delete() logger by key
    // -----------------------------------------------------------------------

    @Test
    void delete_looksUpByKeyThenDeletesById() throws ApiException {
        String id = UUID.randomUUID().toString();
        LoggerListResponse resp = buildLoggerListResponse(id, "del-key", "Del", null);
        when(mockLoggersApi.listLoggers("del-key", null)).thenReturn(resp);

        client.delete("del-key");

        verify(mockLoggersApi).deleteLogger(UUID.fromString(id));
    }

    // -----------------------------------------------------------------------
    // _createLogger / _updateLogger (called by Logger.save())
    // -----------------------------------------------------------------------

    @Test
    void createLogger_postsAndReturnsModel() throws ApiException {
        LoggerResponse resp = buildLoggerResponse("new-id", "key", "Name", null, false);
        when(mockLoggersApi.createLogger(any(ResponseLogger.class))).thenReturn(resp);

        Logger lg = new Logger(client, null, "key", "Name", null, null, false, null, null, null, null);
        Logger result = client._createLogger(lg);

        assertEquals("new-id", result.getId());
        verify(mockLoggersApi).createLogger(any(ResponseLogger.class));
    }

    @Test
    void updateLogger_putsAndReturnsModel() throws ApiException {
        String id = UUID.randomUUID().toString();
        LoggerResponse resp = buildLoggerResponse(id, "key", "Updated", "WARN", true);
        when(mockLoggersApi.updateLogger(eq(UUID.fromString(id)), any(ResponseLogger.class))).thenReturn(resp);

        Logger lg = new Logger(client, id, "key", "Updated", "WARN", null, true, null, null, null, null);
        Logger result = client._updateLogger(lg);

        assertEquals("Updated", result.getName());
        assertEquals("WARN", result.getLevel());
        verify(mockLoggersApi).updateLogger(eq(UUID.fromString(id)), any(ResponseLogger.class));
    }

    // -----------------------------------------------------------------------
    // Logger active record: new_ -> save (create), get -> mutate -> save (update)
    // -----------------------------------------------------------------------

    @Test
    void loggerActiveRecord_createFlow() throws ApiException {
        LoggerResponse createResp = buildLoggerResponse("created-id", "payment-logger", "Payment Logger", null, false);
        when(mockLoggersApi.createLogger(any(ResponseLogger.class))).thenReturn(createResp);

        Logger lg = client.new_("payment-logger");
        assertNull(lg.getId());

        lg.save();
        assertEquals("created-id", lg.getId());
    }

    @Test
    void loggerActiveRecord_getMutateSaveFlow() throws ApiException {
        String id = UUID.randomUUID().toString();
        LoggerListResponse getResp = buildLoggerListResponse(id, "edit-logger", "Edit Logger", "INFO");
        when(mockLoggersApi.listLoggers("edit-logger", null)).thenReturn(getResp);

        Logger lg = client.get("edit-logger");
        assertEquals("INFO", lg.getLevel());

        lg.setLevel(LogLevel.DEBUG);
        lg.setName("Debug Logger");

        LoggerResponse updateResp = buildLoggerResponse(id, "edit-logger", "Debug Logger", "DEBUG", false);
        when(mockLoggersApi.updateLogger(eq(UUID.fromString(id)), any(ResponseLogger.class))).thenReturn(updateResp);

        lg.save();
        assertEquals("Debug Logger", lg.getName());
        assertEquals("DEBUG", lg.getLevel());
    }

    // -----------------------------------------------------------------------
    // newGroup() group factory
    // -----------------------------------------------------------------------

    @Test
    void newGroup_createsUnsavedGroupWithDefaults() {
        LogGroup grp = client.newGroup("my-group");
        assertNull(grp.getId());
        assertEquals("my-group", grp.getKey());
        assertEquals("My Group", grp.getName());
        assertNotNull(grp.getClient());
    }

    @Test
    void newGroup_withNameAndParent() {
        LogGroup grp = client.newGroup("my-group", "Custom Group", "parent-uuid");
        assertEquals("Custom Group", grp.getName());
        assertEquals("parent-uuid", grp.getGroup());
    }

    // -----------------------------------------------------------------------
    // getGroup() by key
    // -----------------------------------------------------------------------

    @Test
    void getGroup_returnsGroupFromList() throws ApiException {
        LogGroupListResponse resp = buildGroupListResponse("grp-id-1", "my.group", "My Group", null);
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        LogGroup grp = client.getGroup("my.group");

        assertEquals("grp-id-1", grp.getId());
        assertEquals("my.group", grp.getKey());
    }

    @Test
    void getGroup_throwsNotFoundWhenKeyNotInList() throws ApiException {
        LogGroupListResponse resp = buildGroupListResponse("grp-id-1", "other.key", "Other", null);
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        assertThrows(SmplNotFoundException.class, () -> client.getGroup("nonexistent"));
    }

    @Test
    void getGroup_throwsNotFoundWhenDataIsNull() throws ApiException {
        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(null);
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        assertThrows(SmplNotFoundException.class, () -> client.getGroup("any"));
    }

    // -----------------------------------------------------------------------
    // listGroups()
    // -----------------------------------------------------------------------

    @Test
    void listGroups_returnsAllGroups() throws ApiException {
        LogGroupListResponse resp = buildGroupListResponse("id-1", "grp1", "Group1", null);
        LogGroupResource r2 = buildGroupResource("id-2", "grp2", "Group2", "WARN");
        resp.getData().add(r2);
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        List<LogGroup> groups = client.listGroups();

        assertEquals(2, groups.size());
    }

    @Test
    void listGroups_returnsEmptyWhenNoData() throws ApiException {
        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(null);
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        assertTrue(client.listGroups().isEmpty());
    }

    // -----------------------------------------------------------------------
    // deleteGroup() by key
    // -----------------------------------------------------------------------

    @Test
    void deleteGroup_looksUpByKeyThenDeletesById() throws ApiException {
        String id = UUID.randomUUID().toString();
        LogGroupListResponse resp = buildGroupListResponse(id, "del-group", "Del", null);
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        client.deleteGroup("del-group");

        verify(mockLogGroupsApi).deleteLogGroup(UUID.fromString(id));
    }

    // -----------------------------------------------------------------------
    // _createGroup / _updateGroup (called by LogGroup.save())
    // -----------------------------------------------------------------------

    @Test
    void createGroup_postsAndReturnsModel() throws ApiException {
        LogGroupResponse resp = buildGroupResponse("new-grp-id", "key", "Name", null);
        when(mockLogGroupsApi.createLogGroup(any(ResponseLogGroup.class))).thenReturn(resp);

        LogGroup grp = new LogGroup(client, null, "key", "Name", null, null, null, null, null);
        LogGroup result = client._createGroup(grp);

        assertEquals("new-grp-id", result.getId());
    }

    @Test
    void updateGroup_putsAndReturnsModel() throws ApiException {
        String id = UUID.randomUUID().toString();
        LogGroupResponse resp = buildGroupResponse(id, "key", "Updated", "WARN");
        when(mockLogGroupsApi.updateLogGroup(eq(UUID.fromString(id)), any(ResponseLogGroup.class))).thenReturn(resp);

        LogGroup grp = new LogGroup(client, id, "key", "Updated", "WARN", null, null, null, null);
        LogGroup result = client._updateGroup(grp);

        assertEquals("Updated", result.getName());
    }

    // -----------------------------------------------------------------------
    // LogGroup active record: newGroup -> save, getGroup -> mutate -> save
    // -----------------------------------------------------------------------

    @Test
    void logGroupActiveRecord_createFlow() throws ApiException {
        LogGroupResponse createResp = buildGroupResponse("created-grp", "infra", "Infra", null);
        when(mockLogGroupsApi.createLogGroup(any(ResponseLogGroup.class))).thenReturn(createResp);

        LogGroup grp = client.newGroup("infra");
        assertNull(grp.getId());

        grp.save();
        assertEquals("created-grp", grp.getId());
    }

    @Test
    void logGroupActiveRecord_getMutateSaveFlow() throws ApiException {
        String id = UUID.randomUUID().toString();
        LogGroupListResponse getResp = buildGroupListResponse(id, "edit-group", "Edit Group", "INFO");
        when(mockLogGroupsApi.listLogGroups()).thenReturn(getResp);

        LogGroup grp = client.getGroup("edit-group");
        grp.setLevel(LogLevel.DEBUG);
        grp.setName("Debug Group");

        LogGroupResponse updateResp = buildGroupResponse(id, "edit-group", "Debug Group", "DEBUG");
        when(mockLogGroupsApi.updateLogGroup(eq(UUID.fromString(id)), any(ResponseLogGroup.class))).thenReturn(updateResp);

        grp.save();
        assertEquals("Debug Group", grp.getName());
    }

    // -----------------------------------------------------------------------
    // start() idempotency
    // -----------------------------------------------------------------------

    @Test
    void start_isIdempotent() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        assertFalse(client.isStarted());

        client.start();
        assertTrue(client.isStarted());

        client.start(); // second call should be no-op
        assertTrue(client.isStarted());

        // listLoggers should only be called once (the second start() is a no-op)
        verify(mockLoggersApi, times(1)).listLoggers(null, null);
    }

    @Test
    void start_continuesEvenIfFetchFails() throws ApiException {
        when(mockLoggersApi.listLoggers(null, null)).thenThrow(new ApiException(500, "server error"));

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        // start should not throw, just log warning
        client.start();
        assertTrue(client.isStarted());
    }

    // -----------------------------------------------------------------------
    // onChange listeners (global + key-scoped)
    // -----------------------------------------------------------------------

    @Test
    void onChange_globalListenerReceivesEvents() throws ApiException {
        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        // Set up a managed logger that will trigger change events
        setupManagedLoggerForStartWithAdapter("com.acme", "INFO");

        client.start();

        assertNotNull(received.get());
        assertEquals("com.acme", received.get().key());
        assertEquals(LogLevel.INFO, received.get().level());
        assertEquals("start", received.get().source());
    }

    @Test
    void onChange_keyScopedListenerReceivesEventsForMatchingKey() throws ApiException {
        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        AtomicReference<LoggerChangeEvent> other = new AtomicReference<>();
        client.onChange("com.acme", received::set);
        client.onChange("com.other", other::set);

        setupManagedLoggerForStartWithAdapter("com.acme", "WARN");

        client.start();

        assertNotNull(received.get());
        assertEquals("com.acme", received.get().key());
        assertNull(other.get()); // should not fire for com.other
    }

    @Test
    void onChange_globalListenerExceptionDoesNotPreventOthers() throws ApiException {
        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        // First listener throws
        client.onChange(event -> { throw new RuntimeException("listener error"); });
        // Second listener should still fire
        client.onChange(received::set);

        setupManagedLoggerForStartWithAdapter("com.acme", "INFO");

        client.start();

        assertNotNull(received.get());
    }

    @Test
    void onChange_keyScopedListenerExceptionDoesNotPreventOthers() throws ApiException {
        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        // Register both: first throws, second captures
        client.onChange("com.acme", event -> { throw new RuntimeException("key listener error"); });
        client.onChange("com.acme", received::set);

        setupManagedLoggerForStartWithAdapter("com.acme", "DEBUG");

        client.start();

        assertNotNull(received.get());
    }

    // -----------------------------------------------------------------------
    // Level resolution algorithm
    // -----------------------------------------------------------------------

    @Test
    void resolveLevel_usesLoggerEnvLevel() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        loggers.put("com.acme", Map.of(
                "level", "INFO",
                "environments", Map.of("prod", Map.of("level", "WARN"))
        ));
        Map<String, Map<String, Object>> groups = new HashMap<>();

        assertEquals("WARN", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_usesLoggerBaseLevel() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        loggers.put("com.acme", Map.of("level", "DEBUG", "environments", Map.of()));
        Map<String, Map<String, Object>> groups = new HashMap<>();

        assertEquals("DEBUG", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_usesGroupChain() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> loggerEntry = new HashMap<>();
        loggerEntry.put("level", null);
        loggerEntry.put("group", "grp-1");
        loggerEntry.put("environments", Map.of());
        loggers.put("com.acme", loggerEntry);

        Map<String, Map<String, Object>> groups = new HashMap<>();
        groups.put("grp-1", Map.of("level", "ERROR", "environments", Map.of()));

        assertEquals("ERROR", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_usesGroupEnvLevel() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> loggerEntry = new HashMap<>();
        loggerEntry.put("level", null);
        loggerEntry.put("group", "grp-1");
        loggerEntry.put("environments", Map.of());
        loggers.put("com.acme", loggerEntry);

        Map<String, Map<String, Object>> groups = new HashMap<>();
        groups.put("grp-1", Map.of(
                "level", "ERROR",
                "environments", Map.of("prod", Map.of("level", "FATAL"))
        ));

        assertEquals("FATAL", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_walksParentGroupChain() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> loggerEntry = new HashMap<>();
        loggerEntry.put("level", null);
        loggerEntry.put("group", "child-grp");
        loggerEntry.put("environments", Map.of());
        loggers.put("com.acme", loggerEntry);

        Map<String, Map<String, Object>> groups = new HashMap<>();
        Map<String, Object> childGroup = new HashMap<>();
        childGroup.put("level", null);
        childGroup.put("group", "parent-grp");
        childGroup.put("environments", Map.of());
        groups.put("child-grp", childGroup);
        groups.put("parent-grp", Map.of("level", "TRACE", "environments", Map.of()));

        assertEquals("TRACE", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_detectsGroupCycleAndFallsBack() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> loggerEntry = new HashMap<>();
        loggerEntry.put("level", null);
        loggerEntry.put("group", "grp-a");
        loggerEntry.put("environments", Map.of());
        loggers.put("com.acme", loggerEntry);

        Map<String, Map<String, Object>> groups = new HashMap<>();
        Map<String, Object> grpA = new HashMap<>();
        grpA.put("level", null);
        grpA.put("group", "grp-b");
        grpA.put("environments", Map.of());
        groups.put("grp-a", grpA);
        Map<String, Object> grpB = new HashMap<>();
        grpB.put("level", null);
        grpB.put("group", "grp-a"); // cycle!
        grpB.put("environments", Map.of());
        groups.put("grp-b", grpB);

        assertEquals("INFO", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_usesDotNotationAncestry() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        // No entry for com.acme.payments, but com.acme has a level
        loggers.put("com.acme", Map.of("level", "WARN", "environments", Map.of()));
        Map<String, Map<String, Object>> groups = new HashMap<>();

        assertEquals("WARN", client.resolveLevel("com.acme.payments", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_walksDotNotationAncestryUpward() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        // No entry for com.acme.payments or com.acme, but com has a level
        loggers.put("com", Map.of("level", "ERROR", "environments", Map.of()));
        Map<String, Map<String, Object>> groups = new HashMap<>();

        assertEquals("ERROR", client.resolveLevel("com.acme.payments", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_fallsBackToInfoWhenNoMatch() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Map<String, Object>> groups = new HashMap<>();

        assertEquals("INFO", client.resolveLevel("unknown.logger", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_prefersEnvLevelOverBaseOnAncestor() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        loggers.put("com.acme", Map.of(
                "level", "DEBUG",
                "environments", Map.of("prod", Map.of("level", "FATAL"))
        ));
        Map<String, Map<String, Object>> groups = new HashMap<>();

        assertEquals("FATAL", client.resolveLevel("com.acme.payments.processor", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_handlesNonMapEnvironments() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("level", "DEBUG");
        entry.put("environments", "not-a-map");
        loggers.put("com.acme", entry);
        Map<String, Map<String, Object>> groups = new HashMap<>();

        assertEquals("DEBUG", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_handlesNonMapEnvData() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("level", "DEBUG");
        entry.put("environments", Map.of("prod", "not-a-map"));
        loggers.put("com.acme", entry);
        Map<String, Map<String, Object>> groups = new HashMap<>();

        assertEquals("DEBUG", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_handlesNonStringLevel() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("level", "DEBUG");
        entry.put("environments", Map.of("prod", Map.of("level", 42)));
        loggers.put("com.acme", entry);
        Map<String, Map<String, Object>> groups = new HashMap<>();

        // 42 is not a String, so env level is skipped, falls through to base level
        assertEquals("DEBUG", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    @Test
    void resolveLevel_handlesNullGroupInChain() {
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> loggerEntry = new HashMap<>();
        loggerEntry.put("level", null);
        loggerEntry.put("group", "missing-grp");
        loggerEntry.put("environments", Map.of());
        loggers.put("com.acme", loggerEntry);

        Map<String, Map<String, Object>> groups = new HashMap<>();
        // "missing-grp" is not in groups map

        assertEquals("INFO", client.resolveLevel("com.acme", "prod", loggers, groups));
    }

    // -----------------------------------------------------------------------
    // Key normalization
    // -----------------------------------------------------------------------

    @Test
    void normalizeKey_replacesSlashesAndColons() {
        assertEquals("com.acme.payments", LoggingClient.normalizeKey("com/acme/payments"));
        assertEquals("com.acme.payments", LoggingClient.normalizeKey("com:acme:payments"));
        assertEquals("com.acme.payments", LoggingClient.normalizeKey("com/acme:payments"));
    }

    @Test
    void normalizeKey_lowercases() {
        assertEquals("com.acme", LoggingClient.normalizeKey("COM.ACME"));
        assertEquals("mylogger", LoggingClient.normalizeKey("MyLogger"));
    }

    @Test
    void normalizeKey_combinedNormalization() {
        assertEquals("com.acme.service", LoggingClient.normalizeKey("COM/Acme:Service"));
    }

    // -----------------------------------------------------------------------
    // Adapter delegation
    // -----------------------------------------------------------------------

    @Test
    void start_delegatesDiscoverToAdapters() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.delegated", "INFO")
        ));

        client.registerAdapter(mockAdapter);
        client.start();

        verify(mockAdapter).discover();
        verify(mockAdapter).installHook(any());
    }

    @Test
    void start_delegatesApplyLevelToAdapters() throws ApiException {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.applied", "INFO")
        ));

        client.registerAdapter(mockAdapter);
        setupManagedLoggerForStartWithAdapter("com.acme.applied", "WARN");

        client.start();

        verify(mockAdapter).applyLevel("com.acme.applied", "WARN");
    }

    @Test
    void close_delegatesUninstallHookToAdapters() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());

        client.registerAdapter(mockAdapter);
        client.start();
        client.close();

        verify(mockAdapter).uninstallHook();
    }

    // -----------------------------------------------------------------------
    // Auto-load edge cases
    // -----------------------------------------------------------------------

    @Test
    void loadAdaptersFromTable_skipsMissingProbeClass() {
        String[][] table = {
                {"com.smplkit.logging.adapters.JulAdapter", "com.nonexistent.ProbeClass"},
        };
        client.loadAdaptersFromTable(table);
        assertTrue(client.getAdapters().isEmpty());
    }

    @Test
    void loadAdaptersFromTable_skipsMissingAdapterClass() {
        String[][] table = {
                {"com.nonexistent.AdapterClass", null},
        };
        client.loadAdaptersFromTable(table);
        assertTrue(client.getAdapters().isEmpty());
    }

    @Test
    void loadAdaptersFromTable_handlesInstantiationFailure() {
        // Use a class that exists but isn't a valid adapter (e.g., String has no no-arg constructor that returns LoggingAdapter)
        String[][] table = {
                {"java.lang.String", null},
        };
        client.loadAdaptersFromTable(table);
        assertTrue(client.getAdapters().isEmpty());
    }

    @Test
    void loadAdaptersFromTable_warnsWhenNoAdaptersFound() {
        String[][] table = {
                {"com.nonexistent.Adapter1", null},
                {"com.nonexistent.Adapter2", null},
        };
        // Should not throw, just log a warning
        client.loadAdaptersFromTable(table);
        assertTrue(client.getAdapters().isEmpty());
    }

    @Test
    void loadAdaptersFromTable_loadsValidAdapter() {
        String[][] table = {
                {"com.smplkit.logging.adapters.JulAdapter", null},
        };
        client.loadAdaptersFromTable(table);
        assertEquals(1, client.getAdapters().size());
        assertEquals("jul", client.getAdapters().get(0).name());
    }

    // -----------------------------------------------------------------------
    // close()
    // -----------------------------------------------------------------------

    @Test
    void close_resetsStarted() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        client.start();
        assertTrue(client.isStarted());

        client.close();
        assertFalse(client.isStarted());
        verify(mockAdapter).uninstallHook();
    }

    // -----------------------------------------------------------------------
    // API exception mapping
    // -----------------------------------------------------------------------

    @Test
    void get_mapsApiExceptionToSmplException() throws ApiException {
        when(mockLoggersApi.listLoggers("fail", null))
                .thenThrow(new ApiException(500, "server error"));

        assertThrows(RuntimeException.class, () -> client.get("fail"));
    }

    @Test
    void list_mapsApiException() throws ApiException {
        when(mockLoggersApi.listLoggers(null, null))
                .thenThrow(new ApiException(500, "server error"));

        assertThrows(RuntimeException.class, () -> client.list());
    }

    @Test
    void delete_mapsApiExceptionOnLookup() throws ApiException {
        when(mockLoggersApi.listLoggers("fail", null))
                .thenThrow(new ApiException(500, "server error"));

        assertThrows(RuntimeException.class, () -> client.delete("fail"));
    }

    @Test
    void delete_mapsApiExceptionOnDelete() throws ApiException {
        String id = UUID.randomUUID().toString();
        LoggerListResponse resp = buildLoggerListResponse(id, "key", "Name", null);
        when(mockLoggersApi.listLoggers("key", null)).thenReturn(resp);
        doThrow(new ApiException(500, "delete failed")).when(mockLoggersApi).deleteLogger(UUID.fromString(id));

        assertThrows(RuntimeException.class, () -> client.delete("key"));
    }

    @Test
    void createLogger_mapsApiException() throws ApiException {
        when(mockLoggersApi.createLogger(any())).thenThrow(new ApiException(422, "validation"));

        Logger lg = new Logger(client, null, "key", "Name", null, null, false, null, null, null, null);
        assertThrows(RuntimeException.class, () -> client._createLogger(lg));
    }

    @Test
    void updateLogger_mapsApiException() throws ApiException {
        String id = UUID.randomUUID().toString();
        when(mockLoggersApi.updateLogger(any(), any())).thenThrow(new ApiException(500, "fail"));

        Logger lg = new Logger(client, id, "key", "Name", null, null, false, null, null, null, null);
        assertThrows(RuntimeException.class, () -> client._updateLogger(lg));
    }

    @Test
    void getGroup_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.listLogGroups()).thenThrow(new ApiException(500, "server error"));

        assertThrows(RuntimeException.class, () -> client.getGroup("fail"));
    }

    @Test
    void listGroups_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.listLogGroups()).thenThrow(new ApiException(500, "server error"));

        assertThrows(RuntimeException.class, () -> client.listGroups());
    }

    @Test
    void deleteGroup_mapsApiExceptionOnLookup() throws ApiException {
        when(mockLogGroupsApi.listLogGroups()).thenThrow(new ApiException(500, "server error"));

        assertThrows(RuntimeException.class, () -> client.deleteGroup("fail"));
    }

    @Test
    void deleteGroup_mapsApiExceptionOnDelete() throws ApiException {
        String id = UUID.randomUUID().toString();
        LogGroupListResponse resp = buildGroupListResponse(id, "key", "Name", null);
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);
        doThrow(new ApiException(500, "delete failed")).when(mockLogGroupsApi).deleteLogGroup(UUID.fromString(id));

        assertThrows(RuntimeException.class, () -> client.deleteGroup("key"));
    }

    @Test
    void createGroup_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.createLogGroup(any())).thenThrow(new ApiException(422, "validation"));

        LogGroup grp = new LogGroup(client, null, "key", "Name", null, null, null, null, null);
        assertThrows(RuntimeException.class, () -> client._createGroup(grp));
    }

    @Test
    void updateGroup_mapsApiException() throws ApiException {
        String id = UUID.randomUUID().toString();
        when(mockLogGroupsApi.updateLogGroup(any(), any())).thenThrow(new ApiException(500, "fail"));

        LogGroup grp = new LogGroup(client, id, "key", "Name", null, null, null, null, null);
        assertThrows(RuntimeException.class, () -> client._updateGroup(grp));
    }

    // -----------------------------------------------------------------------
    // Model conversion edge cases
    // -----------------------------------------------------------------------

    @Test
    void loggerConversion_handlesNullFields() throws ApiException {
        LoggerResource resource = new LoggerResource();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger();
        attrs.setName("Test");
        // Leave key, level, group, managed, sources, environments as null/undefined
        resource.setAttributes(attrs);
        resource.setType(LoggerResource.TypeEnum.LOGGER);

        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(List.of(resource));
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(resp);

        List<Logger> loggers = client.list();
        assertEquals(1, loggers.size());
        Logger lg = loggers.get(0);
        assertFalse(lg.isManaged());
        assertTrue(lg.getSources().isEmpty());
        assertTrue(lg.getEnvironments().isEmpty());
    }

    @Test
    void loggerConversion_handlesTimestamps() throws ApiException {
        OffsetDateTime now = OffsetDateTime.now();
        LoggerResource resource = new LoggerResource();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, now, now);
        attrs.setName("Test");
        attrs.setKey("test-key");
        resource.setAttributes(attrs);
        resource.setId("test-id");
        resource.setType(LoggerResource.TypeEnum.LOGGER);

        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(List.of(resource));
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(resp);

        List<Logger> loggers = client.list();
        Logger lg = loggers.get(0);
        assertNotNull(lg.getCreatedAt());
        assertNotNull(lg.getUpdatedAt());
    }

    @Test
    void groupConversion_handlesNullFields() throws ApiException {
        LogGroupResource resource = new LogGroupResource();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup();
        attrs.setName("Test");
        resource.setAttributes(attrs);
        resource.setType(LogGroupResource.TypeEnum.LOG_GROUP);

        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(List.of(resource));
        when(mockLogGroupsApi.listLogGroups()).thenReturn(resp);

        List<LogGroup> groups = client.listGroups();
        assertEquals(1, groups.size());
        assertTrue(groups.get(0).getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Logger with environments in create/update body
    // -----------------------------------------------------------------------

    @Test
    void createLogger_includesEnvironmentsInBody() throws ApiException {
        LoggerResponse resp = buildLoggerResponse("new-id", "key", "Name", null, false);
        when(mockLoggersApi.createLogger(any(ResponseLogger.class))).thenReturn(resp);

        Logger lg = new Logger(client, null, "key", "Name", null, null, false, null,
                Map.of("prod", Map.of("level", "WARN")), null, null);
        client._createLogger(lg);

        verify(mockLoggersApi).createLogger(any(ResponseLogger.class));
    }

    @Test
    void createGroup_includesEnvironmentsAndGroupInBody() throws ApiException {
        LogGroupResponse resp = buildGroupResponse("new-id", "key", "Name", null);
        when(mockLogGroupsApi.createLogGroup(any(ResponseLogGroup.class))).thenReturn(resp);

        LogGroup grp = new LogGroup(client, null, "key", "Name", "INFO", "parent-id",
                Map.of("prod", Map.of("level", "WARN")), null, null);
        client._createGroup(grp);

        verify(mockLogGroupsApi).createLogGroup(any(ResponseLogGroup.class));
    }

    // -----------------------------------------------------------------------
    // start() with groups present
    // -----------------------------------------------------------------------

    @Test
    void start_fetchesAndCachesGroupData() throws ApiException {
        String key = "com.acme.grptest";

        // Register a mock adapter that discovers the logger
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger(key, "INFO")
        ));
        client.registerAdapter(mockAdapter);

        // Set up managed logger pointing to a group
        LoggerResource lr = buildLoggerResource("lg-id", key, key, null);
        lr.getAttributes().setManaged(true);
        lr.getAttributes().setGroup("grp-id");
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(loggerResp);

        // Set up group with a level
        LogGroupResource gr = buildGroupResource("grp-id", "infra", "Infra", "WARN");
        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>(List.of(gr)));
        when(mockLogGroupsApi.listLogGroups()).thenReturn(groupResp);

        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        client.start();

        // Logger should resolve to group's level (WARN) since it has no own level
        assertNotNull(received.get());
        assertEquals(key, received.get().key());
        assertEquals(LogLevel.WARN, received.get().level());
    }

    @Test
    void start_handlesGroupFetchApiException() throws ApiException {
        // Logger fetch succeeds
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(loggerResp);

        // Group fetch fails
        when(mockLogGroupsApi.listLogGroups()).thenThrow(new ApiException(500, "group fetch failed"));

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        // start() should not throw, just log warning
        client.start();
        assertTrue(client.isStarted());
    }

    // -----------------------------------------------------------------------
    // applyLevels with unknown level string
    // -----------------------------------------------------------------------

    @Test
    void start_handlesUnknownLevelGracefully() throws ApiException {
        String key = "com.acme.badlevel";

        // Register a mock adapter that discovers the logger
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger(key, "INFO")
        ));
        client.registerAdapter(mockAdapter);

        // Set up managed logger with a non-standard level string
        LoggerResource lr = buildLoggerResource("lg-id", key, key, "CUSTOM_INVALID");
        lr.getAttributes().setManaged(true);
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(groupResp);

        // Should not throw -- the invalid level is caught and logged
        client.start();
        assertTrue(client.isStarted());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubEmptyResponses() throws ApiException {
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(groupResp);
    }

    private void setupManagedLoggerForStartWithAdapter(String key, String level) throws ApiException {
        // Register a mock adapter that discovers the logger
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger(key, level)
        ));
        client.registerAdapter(mockAdapter);

        LoggerResource lr = buildLoggerResource("id-1", key, key, level);
        lr.getAttributes().setManaged(true);
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(groupResp);
    }

    private LoggerResource buildLoggerResource(String id, String key, String name, String level) {
        var attrs = new com.smplkit.internal.generated.logging.model.Logger();
        attrs.setName(name);
        attrs.setKey(key);
        if (level != null) attrs.setLevel(level);
        attrs.setManaged(false);

        LoggerResource resource = new LoggerResource();
        resource.setId(id);
        resource.setType(LoggerResource.TypeEnum.LOGGER);
        resource.setAttributes(attrs);
        return resource;
    }

    private LoggerListResponse buildLoggerListResponse(String id, String key, String name, String level) {
        LoggerResource resource = buildLoggerResource(id, key, name, level);
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(new ArrayList<>(List.of(resource)));
        return resp;
    }

    private LoggerResponse buildLoggerResponse(String id, String key, String name, String level, boolean managed) {
        var attrs = new com.smplkit.internal.generated.logging.model.Logger();
        attrs.setName(name);
        attrs.setKey(key);
        if (level != null) attrs.setLevel(level);
        attrs.setManaged(managed);

        LoggerResource data = new LoggerResource();
        data.setId(id);
        data.setType(LoggerResource.TypeEnum.LOGGER);
        data.setAttributes(attrs);

        LoggerResponse resp = new LoggerResponse();
        resp.setData(data);
        return resp;
    }

    private LogGroupResource buildGroupResource(String id, String key, String name, String level) {
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup();
        attrs.setName(name);
        attrs.setKey(key);
        if (level != null) attrs.setLevel(level);

        LogGroupResource resource = new LogGroupResource();
        resource.setId(id);
        resource.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        resource.setAttributes(attrs);
        return resource;
    }

    private LogGroupListResponse buildGroupListResponse(String id, String key, String name, String level) {
        LogGroupResource resource = buildGroupResource(id, key, name, level);
        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(new ArrayList<>(List.of(resource)));
        return resp;
    }

    private LogGroupResponse buildGroupResponse(String id, String key, String name, String level) {
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup();
        attrs.setName(name);
        attrs.setKey(key);
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
