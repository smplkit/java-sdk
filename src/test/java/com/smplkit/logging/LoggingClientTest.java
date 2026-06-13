package com.smplkit.logging;

import com.smplkit.LogLevel;
import com.smplkit.MetricsReporter;
import com.smplkit.SharedWebSocket;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupCreateRequest;
import com.smplkit.internal.generated.logging.model.LogGroupCreateResource;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupRequest;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;
import com.smplkit.internal.generated.logging.model.LoggerBulkItem;
import com.smplkit.internal.generated.logging.model.LoggerBulkRequest;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerRequest;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;
import com.smplkit.logging.adapters.DiscoveredLogger;
import com.smplkit.logging.adapters.LoggingAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for the fused {@link LoggingClient} — the CRUD sub-clients
 * ({@code client.loggers} / {@code client.logGroups}), the live install/discovery
 * surface, change listeners, the discovery buffer, and adapter auto-loading.
 *
 * <p>Constructed via the wired constructor with mocked generated {@code *Api}s; no
 * real network or WebSocket. WS event handlers are driven via the
 * {@code simulate*} test hooks, and the WebSocket manager is a Mockito mock
 * injected through {@link LoggingClient#setSharedWs}.</p>
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
    // loggers.new_() factory
    // -----------------------------------------------------------------------

    @Test
    void new_createsUnsavedLoggerWithManagedDefault() {
        Logger lg = client.loggers.new_("my-logger");
        assertEquals("my-logger", lg.getId());
        assertEquals("my-logger", lg.getName());
        assertTrue(lg.isManaged()); // new_(id) defaults managed=true
    }

    @Test
    void new_withExplicitManaged() {
        Logger lg = client.loggers.new_("my-logger", false);
        assertEquals("my-logger", lg.getId());
        assertFalse(lg.isManaged());
    }

    // -----------------------------------------------------------------------
    // loggers.get()
    // -----------------------------------------------------------------------

    @Test
    void get_returnsLoggerById() throws ApiException {
        LoggerResponse resp = buildLoggerResponse("my.key", "My Key", "INFO", false);
        when(mockLoggersApi.getLogger("my.key")).thenReturn(resp);

        Logger lg = client.loggers.get("my.key");

        assertEquals("my.key", lg.getId());
        assertEquals("My Key", lg.getName());
        assertEquals("INFO", lg.getLevel());
    }

    @Test
    void get_throwsNotFoundOnApiException() throws ApiException {
        when(mockLoggersApi.getLogger("nonexistent"))
                .thenThrow(new ApiException(404, "not found"));

        assertThrows(RuntimeException.class, () -> client.loggers.get("nonexistent"));
    }

    // -----------------------------------------------------------------------
    // loggers.list()
    // -----------------------------------------------------------------------

    @Test
    void list_returnsAllLoggers() throws ApiException {
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(new ArrayList<>(List.of(
                buildLoggerResource("id-1", "Key1", null),
                buildLoggerResource("id-2", "Key2", "DEBUG"))));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(resp);

        List<Logger> loggers = client.loggers.list();

        assertEquals(2, loggers.size());
        assertEquals("id-1", loggers.get(0).getId());
        assertEquals("id-2", loggers.get(1).getId());
    }

    @Test
    void list_returnsEmptyWhenNoData() throws ApiException {
        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(null);
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(resp);

        assertTrue(client.loggers.list().isEmpty());
    }

    // -----------------------------------------------------------------------
    // loggers.delete()
    // -----------------------------------------------------------------------

    @Test
    void delete_deletesById() throws ApiException {
        client.loggers.delete("del-key");
        verify(mockLoggersApi).deleteLogger("del-key");
    }

    // -----------------------------------------------------------------------
    // loggers.saveLogger (PUT upsert; called by Logger.save())
    // -----------------------------------------------------------------------

    @Test
    void saveLogger_putsAndReturnsModel() throws ApiException {
        LoggerResponse resp = buildLoggerResponse("new-id", "Name", null, false);
        when(mockLoggersApi.updateLogger(any(), any(LoggerRequest.class))).thenReturn(resp);

        Logger lg = new Logger(client.loggers, "new-id", "Name", null, null, false, null, null, null, null);
        Logger result = client.loggers.saveLogger(lg);

        assertEquals("new-id", result.getId());
        verify(mockLoggersApi, never()).bulkRegisterLoggers(any(LoggerBulkRequest.class));
        verify(mockLoggersApi).updateLogger(any(), any(LoggerRequest.class));
    }

    @Test
    void saveLogger_putsNullLevelWhenNotSet() throws ApiException {
        LoggerResponse resp = buildLoggerResponse("my-logger", "My Logger", null, false);
        when(mockLoggersApi.updateLogger(any(), any(LoggerRequest.class))).thenReturn(resp);
        ArgumentCaptor<LoggerRequest> bodyCaptor = ArgumentCaptor.forClass(LoggerRequest.class);

        Logger lg = new Logger(client.loggers, "my-logger", "My Logger", null, null, false, null, null, null, null);
        client.loggers.saveLogger(lg);

        verify(mockLoggersApi).updateLogger(eq("my-logger"), bodyCaptor.capture());
        assertNull(bodyCaptor.getValue().getData().getAttributes().getLevel());
    }

    @Test
    void saveLogger_putsLeveledManagedLogger() throws ApiException {
        String id = UUID.randomUUID().toString();
        LoggerResponse resp = buildLoggerResponse(id, "Updated", "WARN", true);
        when(mockLoggersApi.updateLogger(eq(id), any(LoggerRequest.class))).thenReturn(resp);

        Logger lg = new Logger(client.loggers, id, "Updated", "WARN", null, true, null, null, null, null);
        Logger result = client.loggers.saveLogger(lg);

        assertEquals("Updated", result.getName());
        assertEquals("WARN", result.getLevel());
        verify(mockLoggersApi).updateLogger(eq(id), any(LoggerRequest.class));
    }

    @Test
    void saveLogger_includesEnvironmentsInBody() throws ApiException {
        LoggerResponse resp = buildLoggerResponse("new-id", "Name", null, false);
        ArgumentCaptor<LoggerRequest> captor = ArgumentCaptor.forClass(LoggerRequest.class);
        when(mockLoggersApi.updateLogger(any(), captor.capture())).thenReturn(resp);

        Logger lg = new Logger(client.loggers, "new-id", "Name", null, null, false, null,
                Map.of("prod", Map.of("level", "WARN")), null, null);
        client.loggers.saveLogger(lg);

        var attrs = captor.getValue().getData().getAttributes();
        assertEquals(Map.of("level", "WARN"), attrs.getEnvironments().get("prod"));
    }

    // -----------------------------------------------------------------------
    // Logger active record: new_ -> save (PUT), get -> mutate -> save (PUT)
    // -----------------------------------------------------------------------

    @Test
    void loggerActiveRecord_createFlow() throws ApiException {
        LoggerResponse createResp = buildLoggerResponse("created-id", "Payment Logger", null, false);
        when(mockLoggersApi.updateLogger(any(), any(LoggerRequest.class))).thenReturn(createResp);

        Logger lg = client.loggers.new_("payment-logger");
        assertEquals("payment-logger", lg.getId());

        lg.save();
        assertEquals("created-id", lg.getId());
        verify(mockLoggersApi, never()).bulkRegisterLoggers(any(LoggerBulkRequest.class));
        verify(mockLoggersApi).updateLogger(any(), any(LoggerRequest.class));
    }

    @Test
    void loggerActiveRecord_getMutateSaveFlow() throws ApiException {
        String id = "edit-logger";
        LoggerResponse getResp = buildLoggerResponse(id, "Edit Logger", "INFO", false);
        when(mockLoggersApi.getLogger("edit-logger")).thenReturn(getResp);

        Logger lg = client.loggers.get("edit-logger");
        assertEquals("INFO", lg.getLevel());

        lg.setLevel(LogLevel.DEBUG);
        lg.setName("Debug Logger");

        LoggerResponse updateResp = buildLoggerResponse(id, "Debug Logger", "DEBUG", false);
        when(mockLoggersApi.updateLogger(eq(id), any(LoggerRequest.class))).thenReturn(updateResp);

        lg.save();
        assertEquals("Debug Logger", lg.getName());
        assertEquals("DEBUG", lg.getLevel());
    }

    // -----------------------------------------------------------------------
    // logGroups.new_() factory
    // -----------------------------------------------------------------------

    @Test
    void newGroup_createsUnsavedGroupWithDefaults() {
        LogGroup grp = client.logGroups.new_("my-group");
        assertEquals("my-group", grp.getId());
        assertEquals("My Group", grp.getName()); // keyToDisplayName
    }

    @Test
    void newGroup_withNameAndParent() {
        LogGroup grp = client.logGroups.new_("my-group", "Custom Group", "parent-uuid");
        assertEquals("Custom Group", grp.getName());
        assertEquals("parent-uuid", grp.getGroup());
    }

    // -----------------------------------------------------------------------
    // logGroups.get()
    // -----------------------------------------------------------------------

    @Test
    void getGroup_returnsGroupById() throws ApiException {
        LogGroupResponse resp = buildGroupResponse("my.group", "My Group", null);
        when(mockLogGroupsApi.getLogGroup("my.group")).thenReturn(resp);

        LogGroup grp = client.logGroups.get("my.group");

        assertEquals("my.group", grp.getId());
        assertEquals("My Group", grp.getName());
    }

    @Test
    void getGroup_throwsOnApiException() throws ApiException {
        when(mockLogGroupsApi.getLogGroup("nonexistent"))
                .thenThrow(new ApiException(404, "not found"));

        assertThrows(RuntimeException.class, () -> client.logGroups.get("nonexistent"));
    }

    // -----------------------------------------------------------------------
    // logGroups.list()
    // -----------------------------------------------------------------------

    @Test
    void listGroups_returnsAllGroups() throws ApiException {
        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(new ArrayList<>(List.of(
                buildGroupResource("id-1", "Group1", null),
                buildGroupResource("id-2", "Group2", "WARN"))));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(resp);

        List<LogGroup> groups = client.logGroups.list();

        assertEquals(2, groups.size());
    }

    @Test
    void listGroups_returnsEmptyWhenNoData() throws ApiException {
        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(null);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(resp);

        assertTrue(client.logGroups.list().isEmpty());
    }

    // -----------------------------------------------------------------------
    // logGroups.delete()
    // -----------------------------------------------------------------------

    @Test
    void deleteGroup_deletesById() throws ApiException {
        client.logGroups.delete("del-group");
        verify(mockLogGroupsApi).deleteLogGroup("del-group");
    }

    // -----------------------------------------------------------------------
    // logGroups.saveGroup (POST when new, PUT when existing)
    // -----------------------------------------------------------------------

    @Test
    void createGroup_postsAndReturnsModel() throws ApiException {
        LogGroupResponse resp = buildGroupResponse("new-grp-id", "Name", null);
        when(mockLogGroupsApi.createLogGroup(any(LogGroupCreateRequest.class))).thenReturn(resp);

        LogGroup grp = new LogGroup(client.logGroups, "new-grp-id", "Name", null, null, null, null, null);
        LogGroup result = client.logGroups.saveGroup(grp);

        assertEquals("new-grp-id", result.getId());
        verify(mockLogGroupsApi).createLogGroup(any(LogGroupCreateRequest.class));
    }

    @Test
    void updateGroup_putsAndReturnsModel() throws ApiException {
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        LogGroupResponse resp = buildGroupResponse(id, "Updated", "WARN");
        when(mockLogGroupsApi.updateLogGroup(eq(id), any(LogGroupRequest.class))).thenReturn(resp);

        // createdAt set -> saveGroup() PUTs
        LogGroup grp = new LogGroup(client.logGroups, id, "Updated", "WARN", null, null,
                now.toInstant(), now.toInstant());
        LogGroup result = client.logGroups.saveGroup(grp);

        assertEquals("Updated", result.getName());
        verify(mockLogGroupsApi).updateLogGroup(eq(id), any(LogGroupRequest.class));
    }

    @Test
    void createGroup_includesEnvironmentsAndParentInBody() throws ApiException {
        LogGroupResponse resp = buildGroupResponse("new-id", "Name", null);
        ArgumentCaptor<LogGroupCreateRequest> captor = ArgumentCaptor.forClass(LogGroupCreateRequest.class);
        when(mockLogGroupsApi.createLogGroup(captor.capture())).thenReturn(resp);

        LogGroup grp = new LogGroup(client.logGroups, "new-id", "Name", "INFO", "parent-id",
                Map.of("prod", Map.of("level", "WARN")), null, null);
        client.logGroups.saveGroup(grp);

        LogGroupCreateResource data = captor.getValue().getData();
        assertEquals("new-id", data.getId());
        assertEquals(LogGroupCreateResource.TypeEnum.LOG_GROUP, data.getType());
        var attrs = data.getAttributes();
        assertEquals("Name", attrs.getName());
        assertEquals("parent-id", attrs.getParentId());
        assertEquals(Map.of("level", "WARN"), attrs.getEnvironments().get("prod"));
    }

    // -----------------------------------------------------------------------
    // LogGroup active record: new_ -> save (POST), get -> mutate -> save (PUT)
    // -----------------------------------------------------------------------

    @Test
    void logGroupActiveRecord_createFlow() throws ApiException {
        LogGroupResponse createResp = buildGroupResponse("created-grp", "Infra", null);
        when(mockLogGroupsApi.createLogGroup(any(LogGroupCreateRequest.class))).thenReturn(createResp);

        LogGroup grp = client.logGroups.new_("infra");
        assertEquals("infra", grp.getId());

        grp.save();
        assertEquals("created-grp", grp.getId());
    }

    @Test
    void logGroupActiveRecord_getMutateSaveFlow() throws ApiException {
        String id = "edit-group";
        LogGroupResponse getResp = buildGroupResponse(id, "Edit Group", "INFO");
        when(mockLogGroupsApi.getLogGroup("edit-group")).thenReturn(getResp);

        LogGroup grp = client.logGroups.get("edit-group");
        grp.setLevel(LogLevel.DEBUG);
        grp.setName("Debug Group");

        LogGroupResponse updateResp = buildGroupResponse(id, "Debug Group", "DEBUG");
        when(mockLogGroupsApi.updateLogGroup(eq(id), any(LogGroupRequest.class))).thenReturn(updateResp);

        grp.save();
        assertEquals("Debug Group", grp.getName());
    }

    // -----------------------------------------------------------------------
    // install() idempotency
    // -----------------------------------------------------------------------

    @Test
    void install_isIdempotent() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        assertFalse(client.isInstalled());

        client.install();
        assertTrue(client.isInstalled());

        client.install(); // second call should be no-op
        assertTrue(client.isInstalled());

        verify(mockLoggersApi, times(1)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void install_continuesEvenIfFetchFails() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenThrow(new ApiException(500, "server error"));

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        client.install();
        assertTrue(client.isInstalled());
    }

    // -----------------------------------------------------------------------
    // onChange listeners (global + key-scoped) — gated by install()
    // -----------------------------------------------------------------------

    @Test
    void onChange_beforeInstall_throwsNotInstalled() {
        assertThrows(com.smplkit.errors.NotInstalledError.class,
                () -> client.onChange(e -> {}));
        assertThrows(com.smplkit.errors.NotInstalledError.class,
                () -> client.onChange("k", e -> {}));
    }

    @Test
    void refresh_beforeInstall_throwsNotInstalled() {
        assertThrows(com.smplkit.errors.NotInstalledError.class, () -> client.refresh());
    }

    @Test
    void onChange_globalListenerReceivesEvents() throws ApiException {
        setupManagedLoggerForInstallWithAdapter("com.acme", "INFO");
        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();

        client.install();
        // install() applies levels silently (no fire); listeners registered post-install.
        client.onChange(received::set);

        // Trigger a delta via a WS event that flips the level.
        when(mockLoggersApi.getLogger("com.acme"))
                .thenReturn(buildLoggerResponse("com.acme", "com.acme", "WARN", true));
        client.simulateLoggerChanged(Map.of("id", "com.acme"));

        assertNotNull(received.get());
        assertEquals("com.acme", received.get().id());
        assertEquals(LogLevel.WARN, received.get().level());
        assertEquals("websocket", received.get().source());
    }

    @Test
    void onChange_keyScopedListenerOnlyForMatchingKey() throws ApiException {
        setupManagedLoggerForInstallWithAdapter("com.acme", "INFO");
        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        AtomicReference<LoggerChangeEvent> other = new AtomicReference<>();

        client.install();
        client.onChange("com.acme", received::set);
        client.onChange("com.other", other::set);

        when(mockLoggersApi.getLogger("com.acme"))
                .thenReturn(buildLoggerResponse("com.acme", "com.acme", "WARN", true));
        client.simulateLoggerChanged(Map.of("id", "com.acme"));

        assertNotNull(received.get());
        assertNull(other.get());
    }

    // -----------------------------------------------------------------------
    // metrics reporting on apply
    // -----------------------------------------------------------------------

    @Test
    void install_recordsMetricsWhenReporterSet() throws ApiException {
        MetricsReporter metrics = mock(MetricsReporter.class);
        client.setMetrics(metrics);
        setupManagedLoggerForInstallWithAdapter("com.acme.metered", "WARN");

        client.install();

        verify(metrics, atLeastOnce()).record(eq("logging.level_changes"), eq("changes"), any());
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
    void install_delegatesDiscoverToAdapters() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.delegated", "INFO")
        ));

        client.registerAdapter(mockAdapter);
        client.install();

        verify(mockAdapter).discover();
        verify(mockAdapter).installHook(any());
    }

    @Test
    void install_delegatesApplyLevelToAdapters() throws ApiException {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.applied", "INFO")
        ));

        client.registerAdapter(mockAdapter);
        setupManagedLoggerResponses("com.acme.applied", "WARN");

        client.install();

        verify(mockAdapter).applyLevel("com.acme.applied", "WARN");
    }

    @Test
    void close_delegatesUninstallHookToAdapters() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());

        client.registerAdapter(mockAdapter);
        client.install();
        client.close();

        verify(mockAdapter).uninstallHook();
        assertFalse(client.isInstalled());
    }

    @Test
    void registerAdapter_afterInstall_throws() throws ApiException {
        stubEmptyResponses();
        client.install();
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        assertThrows(IllegalStateException.class, () -> client.registerAdapter(mockAdapter));
    }

    // -----------------------------------------------------------------------
    // Bulk registration payload (discovery flush at install)
    // -----------------------------------------------------------------------

    @Test
    void install_sendsBulkPayloadWithBothLevelFields() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.Service", "WARN", "WARN")
        ));
        client.registerAdapter(mockAdapter);
        client.install();

        ArgumentCaptor<LoggerBulkRequest> captor = ArgumentCaptor.forClass(LoggerBulkRequest.class);
        verify(mockLoggersApi).bulkRegisterLoggers(captor.capture());
        LoggerBulkItem item = captor.getValue().getLoggers().get(0);

        assertEquals("com.acme.service", item.getId()); // normalized
        assertEquals("WARN", item.getLevel());
        assertEquals("WARN", item.getResolvedLevel());
        assertEquals("test-service", item.getService());
        assertEquals("production", item.getEnvironment());
    }

    @Test
    void install_bulkPayload_levelAbsentWhenInherited() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.inherited", null, "INFO")
        ));
        client.registerAdapter(mockAdapter);
        client.install();

        ArgumentCaptor<LoggerBulkRequest> captor = ArgumentCaptor.forClass(LoggerBulkRequest.class);
        verify(mockLoggersApi).bulkRegisterLoggers(captor.capture());
        LoggerBulkItem item = captor.getValue().getLoggers().get(0);

        assertNull(item.getLevel());
        assertEquals("INFO", item.getResolvedLevel());
    }

    @Test
    void install_doesNotCallBulkWhenNothingDiscovered() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);
        client.install();

        verify(mockLoggersApi, never()).bulkRegisterLoggers(any());
    }

    @Test
    void install_continuesWhenBulkRegisterFails() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.bulkfail", "INFO", "INFO")
        ));
        client.registerAdapter(mockAdapter);

        when(mockLoggersApi.bulkRegisterLoggers(any())).thenThrow(new ApiException(500, "bulk error"));

        client.install();
        assertTrue(client.isInstalled());
    }

    // -----------------------------------------------------------------------
    // Auto-load via ServiceLoader (loadAdaptersFromProviders)
    // -----------------------------------------------------------------------

    @Test
    void serviceLoader_loadsRealAdapters() {
        List<ServiceLoader.Provider<LoggingAdapter>> providers =
                ServiceLoader.load(LoggingAdapter.class).stream().collect(Collectors.toList());
        client.loadAdaptersFromProviders(providers);
        assertTrue(client.getAdapters().stream().anyMatch(a -> a.name().equals("jul")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceLoader_skipsAdapterWithMissingDependency() {
        ServiceLoader.Provider<LoggingAdapter> provider = mock(ServiceLoader.Provider.class);
        when(provider.get()).thenThrow(new NoClassDefFoundError("missing/FrameworkClass"));
        client.loadAdaptersFromProviders(List.of(provider));
        assertTrue(client.getAdapters().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceLoader_handlesServiceConfigError() {
        ServiceLoader.Provider<LoggingAdapter> provider = mock(ServiceLoader.Provider.class);
        when(provider.get()).thenThrow(new ServiceConfigurationError("broken service"));
        client.loadAdaptersFromProviders(List.of(provider));
        assertTrue(client.getAdapters().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceLoader_handlesInstantiationFailure() {
        ServiceLoader.Provider<LoggingAdapter> provider = mock(ServiceLoader.Provider.class);
        when(provider.get()).thenThrow(new RuntimeException("instantiation failed"));
        client.loadAdaptersFromProviders(List.of(provider));
        assertTrue(client.getAdapters().isEmpty());
    }

    @Test
    void serviceLoader_warnsWhenNoAdaptersFound() {
        client.loadAdaptersFromProviders(List.of());
        assertTrue(client.getAdapters().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceLoader_loadsValidAdapter() {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        ServiceLoader.Provider<LoggingAdapter> provider = mock(ServiceLoader.Provider.class);
        when(provider.get()).thenReturn(mockAdapter);
        client.loadAdaptersFromProviders(List.of(provider));
        assertEquals(1, client.getAdapters().size());
        assertEquals("test", client.getAdapters().get(0).name());
    }

    // -----------------------------------------------------------------------
    // install() — NoClassDefFoundError from adapter methods
    // -----------------------------------------------------------------------

    @Test
    void install_discover_noClassDefFoundError_isSkippedGracefully() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("broken");
        when(mockAdapter.discover()).thenThrow(new NoClassDefFoundError("missing/FrameworkClass"));
        client.registerAdapter(mockAdapter);

        assertDoesNotThrow(() -> client.install());
        assertTrue(client.isInstalled());
    }

    @Test
    void install_discover_runtimeException_isSkippedGracefully() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("broken");
        when(mockAdapter.discover()).thenThrow(new RuntimeException("discover blew up"));
        client.registerAdapter(mockAdapter);

        assertDoesNotThrow(() -> client.install());
        assertTrue(client.isInstalled());
    }

    @Test
    void install_installHook_noClassDefFoundError_isSkippedGracefully() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("broken");
        when(mockAdapter.discover()).thenReturn(List.of());
        doThrow(new NoClassDefFoundError("missing/FrameworkClass")).when(mockAdapter).installHook(any());
        client.registerAdapter(mockAdapter);

        assertDoesNotThrow(() -> client.install());
        assertTrue(client.isInstalled());
    }

    @Test
    void install_installHook_runtimeException_isSkippedGracefully() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("broken");
        when(mockAdapter.discover()).thenReturn(List.of());
        doThrow(new RuntimeException("hook blew up")).when(mockAdapter).installHook(any());
        client.registerAdapter(mockAdapter);

        assertDoesNotThrow(() -> client.install());
        assertTrue(client.isInstalled());
    }

    // -----------------------------------------------------------------------
    // close()
    // -----------------------------------------------------------------------

    @Test
    void close_resetsInstalled() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        client.install();
        assertTrue(client.isInstalled());

        client.close();
        assertFalse(client.isInstalled());
        verify(mockAdapter).uninstallHook();
    }

    @Test
    void close_handlesUninstallHookFailure() throws ApiException {
        stubEmptyResponses();
        LoggingAdapter failAdapter = mock(LoggingAdapter.class);
        when(failAdapter.name()).thenReturn("fail");
        when(failAdapter.discover()).thenReturn(List.of());
        doThrow(new RuntimeException("uninstall failed")).when(failAdapter).uninstallHook();
        client.registerAdapter(failAdapter);
        client.install();

        assertDoesNotThrow(() -> client.close());
        assertFalse(client.isInstalled());
    }

    @Test
    void close_withoutInstall_doesNotThrow() {
        assertDoesNotThrow(() -> client.close());
    }

    // -----------------------------------------------------------------------
    // API exception mapping (CRUD)
    // -----------------------------------------------------------------------

    @Test
    void get_mapsApiExceptionToSmplError() throws ApiException {
        when(mockLoggersApi.getLogger("fail")).thenThrow(new ApiException(500, "server error"));
        assertThrows(RuntimeException.class, () -> client.loggers.get("fail"));
    }

    @Test
    void list_mapsApiException() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenThrow(new ApiException(500, "server error"));
        assertThrows(RuntimeException.class, () -> client.loggers.list());
    }

    @Test
    void list_apiException_code0_mapsToConnectionError() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenThrow(new ApiException("network failure"));
        assertThrows(RuntimeException.class, () -> client.loggers.list());
    }

    @Test
    void delete_mapsApiExceptionOnDelete() throws ApiException {
        doThrow(new ApiException(500, "delete failed")).when(mockLoggersApi).deleteLogger("fail");
        assertThrows(RuntimeException.class, () -> client.loggers.delete("fail"));
    }

    @Test
    void saveLogger_mapsApiException() throws ApiException {
        when(mockLoggersApi.updateLogger(any(), any())).thenThrow(new ApiException(422, "validation"));
        Logger lg = new Logger(client.loggers, "id", "Name", null, null, false, null, null, null, null);
        assertThrows(RuntimeException.class, () -> client.loggers.saveLogger(lg));
    }

    @Test
    void getGroup_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.getLogGroup("fail")).thenThrow(new ApiException(500, "server error"));
        assertThrows(RuntimeException.class, () -> client.logGroups.get("fail"));
    }

    @Test
    void listGroups_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenThrow(new ApiException(500, "server error"));
        assertThrows(RuntimeException.class, () -> client.logGroups.list());
    }

    @Test
    void deleteGroup_mapsApiExceptionOnDelete() throws ApiException {
        doThrow(new ApiException(500, "delete failed")).when(mockLogGroupsApi).deleteLogGroup("fail");
        assertThrows(RuntimeException.class, () -> client.logGroups.delete("fail"));
    }

    @Test
    void createGroup_mapsApiException() throws ApiException {
        when(mockLogGroupsApi.createLogGroup(any())).thenThrow(new ApiException(422, "validation"));
        LogGroup grp = new LogGroup(client.logGroups, "id", "Name", null, null, null, null, null);
        assertThrows(RuntimeException.class, () -> client.logGroups.saveGroup(grp));
    }

    @Test
    void updateGroup_mapsApiException() throws ApiException {
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        when(mockLogGroupsApi.updateLogGroup(any(), any())).thenThrow(new ApiException(500, "fail"));
        LogGroup grp = new LogGroup(client.logGroups, id, "Name", null, null, null,
                now.toInstant(), now.toInstant());
        assertThrows(RuntimeException.class, () -> client.logGroups.saveGroup(grp));
    }

    // -----------------------------------------------------------------------
    // Model conversion edge cases
    // -----------------------------------------------------------------------

    @Test
    void loggerConversion_handlesNullFields() throws ApiException {
        LoggerResource resource = new LoggerResource();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger();
        attrs.setName("Test");
        resource.setAttributes(attrs);
        resource.setType(LoggerResource.TypeEnum.LOGGER);

        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(List.of(resource));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(resp);

        List<Logger> loggers = client.loggers.list();
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
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, null, now, now);
        attrs.setName("Test");
        resource.setAttributes(attrs);
        resource.setId("test-id");
        resource.setType(LoggerResource.TypeEnum.LOGGER);

        LoggerListResponse resp = new LoggerListResponse();
        resp.setData(List.of(resource));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(resp);

        Logger lg = client.loggers.list().get(0);
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
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(resp);

        List<LogGroup> groups = client.logGroups.list();
        assertEquals(1, groups.size());
        assertTrue(groups.get(0).getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // install() with groups present (level resolution through group chain)
    // -----------------------------------------------------------------------

    @Test
    void install_resolvesLevelThroughGroup() throws ApiException {
        String key = "com.acme.grptest";

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(new DiscoveredLogger(key, "INFO")));
        client.registerAdapter(mockAdapter);

        LoggerResource lr = buildLoggerResource(key, key, null);
        lr.getAttributes().setManaged(true);
        lr.getAttributes().setGroup("grp-id");
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(loggerResp);

        LogGroupResource gr = buildGroupResource("grp-id", "Infra", "WARN");
        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>(List.of(gr)));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupResp);

        client.install();

        // Logger resolves to its group's WARN level
        verify(mockAdapter).applyLevel(key, "WARN");
    }

    @Test
    void install_handlesGroupFetchApiException() throws ApiException {
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(loggerResp);

        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenThrow(new ApiException(500, "group fetch failed"));

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        client.install();
        assertTrue(client.isInstalled());
    }

    // -----------------------------------------------------------------------
    // applyLevels with unknown level string
    // -----------------------------------------------------------------------

    @Test
    void install_managedLoggerWithNoOwnLevel_resolvesToFallback() throws ApiException {
        String key = "com.acme.nolevel";

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(new DiscoveredLogger(key, "INFO")));
        client.registerAdapter(mockAdapter);

        // Managed logger with no own level, no group -> resolves to the INFO fallback.
        LoggerResource lr = buildLoggerResource(key, key, null);
        lr.getAttributes().setManaged(true);
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupResp);

        client.install();
        assertTrue(client.isInstalled());
        verify(mockAdapter).applyLevel(key, "INFO");
    }

    @Test
    void install_skipsLoggerWhoseResolvedLevelIsUnparseable() throws ApiException {
        // A server-supplied env override can carry an arbitrary string in the
        // JSONB environments map. resolveLevel returns it verbatim; the apply
        // loop must skip it (tryParseLogLevel -> null) rather than throwing.
        String key = "com.acme.weird";
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(new DiscoveredLogger(key, "INFO")));
        client.registerAdapter(mockAdapter);

        LoggerResource lr = buildLoggerResource(key, key, null);
        lr.getAttributes().setManaged(true);
        lr.getAttributes().setEnvironments(Map.of("production", Map.of("level", "NOT_A_LEVEL")));
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(loggerResp);
        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupResp);

        assertDoesNotThrow(() -> client.install());
        // The unparseable level is skipped — adapter is never asked to apply it.
        verify(mockAdapter, never()).applyLevel(eq(key), any());
    }

    @Test
    void wsDelta_recordsMetricsWhenReporterSet() throws ApiException {
        MetricsReporter metrics = mock(MetricsReporter.class);
        client.setMetrics(metrics);
        setupManagedLoggerForInstallWithAdapter("com.acme.metered", "INFO");
        client.install();

        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        client.onChange(received::set);

        when(mockLoggersApi.getLogger("com.acme.metered"))
                .thenReturn(buildLoggerResponse("com.acme.metered", "com.acme.metered", "WARN", true));
        client.simulateLoggerChanged(Map.of("id", "com.acme.metered"));

        assertNotNull(received.get());
        // applyDeltasAndFire records the level change once the level actually moved.
        verify(metrics, atLeastOnce()).record(eq("logging.level_changes"), eq("changes"), any());
    }

    @Test
    void tryParseLogLevel_returnsEnumForKnownValue() {
        assertEquals(LogLevel.INFO, client.tryParseLogLevel("INFO", "any-key"));
        assertEquals(LogLevel.WARN, client.tryParseLogLevel("WARN", "any-key"));
    }

    @Test
    void tryParseLogLevel_returnsNullForUnknownValue() {
        assertNull(client.tryParseLogLevel("CUSTOM_INVALID", "any-key"));
        assertNull(client.tryParseLogLevel("", "any-key"));
    }

    // -----------------------------------------------------------------------
    // WebSocket handler registration
    // -----------------------------------------------------------------------

    @Test
    void install_registersWsHandlersWhenManagerSet() throws ApiException {
        stubEmptyResponses();
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        SharedWebSocket mockWs = mock(SharedWebSocket.class);
        client.setSharedWs(mockWs);

        client.install();

        verify(mockWs).on(eq("logger_changed"), any());
        verify(mockWs).on(eq("logger_deleted"), any());
        verify(mockWs).on(eq("group_changed"), any());
        verify(mockWs).on(eq("group_deleted"), any());
        verify(mockWs).on(eq("loggers_changed"), any());
    }

    @Test
    void close_unsubscribesWsHandlers() throws ApiException {
        stubEmptyResponses();
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        SharedWebSocket mockWs = mock(SharedWebSocket.class);
        client.setSharedWs(mockWs);
        client.install();
        client.close();

        verify(mockWs).off(eq("logger_changed"), any());
        verify(mockWs).off(eq("loggers_changed"), any());
        // A wired (borrowed) WS is not closed by the client.
        verify(mockWs, never()).close();
    }

    @Test
    void ensureStartedHook_runsOnInstall() throws ApiException {
        stubEmptyResponses();
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        client.setEnsureStarted(() -> ran.set(true));

        client.install();
        assertTrue(ran.get());
    }

    // -----------------------------------------------------------------------
    // Discovery buffer (via simulateNewLogger test hooks)
    // -----------------------------------------------------------------------

    @Test
    void onNewLogger_addsToBuffer() {
        assertEquals(0, client.getLoggerBufferPendingCount());
        client.simulateNewLogger("com.acme.Foo", "INFO");
        assertEquals(1, client.getLoggerBufferPendingCount());
    }

    @Test
    void onNewLogger_deduplicatesByNormalizedKey() {
        client.simulateNewLogger("com.acme.Foo", "INFO");
        client.simulateNewLogger("com.acme.foo", "DEBUG");
        client.simulateNewLogger("COM.ACME.FOO", "WARN");
        assertEquals(1, client.getLoggerBufferPendingCount());
    }

    @Test
    void onNewLogger_drainedByInstallFlush() throws ApiException {
        stubEmptyResponses();
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.startup", "INFO")
        ));
        client.registerAdapter(mockAdapter);

        assertEquals(0, client.getLoggerBufferPendingCount());
        client.install();
        assertEquals(0, client.getLoggerBufferPendingCount());
    }

    @Test
    void onNewLogger_appliesCachedLevelWhenInstalledAndManaged() throws ApiException {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        String key = "com.acme.managed";
        setupManagedLoggerResponses(key, "WARN");
        client.install();

        client.simulateNewLogger(key, "INFO");

        verify(mockAdapter).applyLevel(key, "WARN");
    }

    @Test
    void onNewLogger_doesNotApplyLevelWhenNotManaged() throws ApiException {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);

        String key = "com.acme.unmanaged";
        LoggerResource lr = buildLoggerResource(key, key, "DEBUG");
        lr.getAttributes().setManaged(false);
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(loggerResp);
        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupResp);

        client.install();
        client.simulateNewLogger(key, "DEBUG");

        verify(mockAdapter, never()).applyLevel(any(), any());
    }

    @Test
    void onNewLogger_doesNotApplyLevelBeforeInstall() {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        client.registerAdapter(mockAdapter);

        client.simulateNewLogger("com.acme.early", "INFO");

        verify(mockAdapter, never()).applyLevel(any(), any());
    }

    @Test
    void onNewLogger_silencesAdapterApplyLevelException() throws ApiException {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        doThrow(new RuntimeException("adapter blow-up")).when(mockAdapter).applyLevel(any(), any());
        client.registerAdapter(mockAdapter);

        String key = "com.acme.throwing";
        setupManagedLoggerResponses(key, "WARN");
        client.install();

        assertDoesNotThrow(() -> client.simulateNewLogger(key, "INFO"));
    }

    @Test
    void onNewLogger_triggersEagerFlushAtThreshold() throws ApiException {
        stubEmptyResponses();
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);
        client.install();

        for (int i = 0; i < 50; i++) {
            client.simulateNewLogger("com.acme.dynamic" + i, "INFO");
        }

        // Eager flush spawns a daemon thread — verify bulk registration fires.
        verify(mockLoggersApi, timeout(2000).atLeastOnce()).bulkRegisterLoggers(any());
    }

    @Test
    void flushLoggerBuffer_sendsCorrectPayloadForPostInstallLogger() throws ApiException {
        stubEmptyResponses();
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of());
        client.registerAdapter(mockAdapter);
        client.install();

        client.simulateNewLogger("com.acme.Dynamic", "WARN");
        for (int i = 0; i < 49; i++) {
            client.simulateNewLogger("com.acme.extra" + i, "INFO");
        }

        ArgumentCaptor<LoggerBulkRequest> captor = ArgumentCaptor.forClass(LoggerBulkRequest.class);
        verify(mockLoggersApi, timeout(2000).atLeastOnce()).bulkRegisterLoggers(captor.capture());

        LoggerBulkItem dynamicItem = captor.getAllValues().stream()
                .flatMap(r -> r.getLoggers().stream())
                .filter(i -> "com.acme.dynamic".equals(i.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(dynamicItem);
        assertEquals("WARN", dynamicItem.getLevel());
        assertEquals("WARN", dynamicItem.getResolvedLevel());
        assertEquals("test-service", dynamicItem.getService());
        assertEquals("production", dynamicItem.getEnvironment());
    }

    @Test
    void getAdapters_returnsRegisteredAdapters() {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("custom");
        client.registerAdapter(mockAdapter);
        assertEquals(1, client.getAdapters().size());
        assertEquals("custom", client.getAdapters().get(0).name());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubEmptyResponses() throws ApiException {
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupResp);
    }

    /** Stub list responses for a single managed logger; no adapter registered. */
    private void setupManagedLoggerResponses(String key, String level) throws ApiException {
        LoggerResource lr = buildLoggerResource(key, key, level);
        lr.getAttributes().setManaged(true);
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupResp);
    }

    /** Register a discovering adapter plus a managed logger row for install-driven apply. */
    private void setupManagedLoggerForInstallWithAdapter(String key, String level) throws ApiException {
        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("test");
        when(mockAdapter.discover()).thenReturn(List.of(new DiscoveredLogger(key, level)));
        client.registerAdapter(mockAdapter);
        setupManagedLoggerResponses(key, level);
    }

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

        LoggerResource resource = new LoggerResource();
        resource.setId(id);
        resource.setType(LoggerResource.TypeEnum.LOGGER);
        resource.setAttributes(attrs);
        return resource;
    }

    private LoggerResponse buildLoggerResponse(String id, String name, String level, boolean managed) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, null, now, now);
        attrs.setName(name);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));
        attrs.setManaged(managed);

        LoggerResource data = new LoggerResource();
        data.setId(id);
        data.setType(LoggerResource.TypeEnum.LOGGER);
        data.setAttributes(attrs);

        LoggerResponse resp = new LoggerResponse();
        resp.setData(data);
        return resp;
    }

    private LogGroupResource buildGroupResource(String id, String name, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(now, now);
        attrs.setName(name);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));

        LogGroupResource resource = new LogGroupResource();
        resource.setId(id);
        resource.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        resource.setAttributes(attrs);
        return resource;
    }

    private LogGroupResponse buildGroupResponse(String id, String name, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(now, now);
        attrs.setName(name);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));

        LogGroupResource data = new LogGroupResource();
        data.setId(id);
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);

        LogGroupResponse resp = new LogGroupResponse();
        resp.setData(data);
        return resp;
    }
}
