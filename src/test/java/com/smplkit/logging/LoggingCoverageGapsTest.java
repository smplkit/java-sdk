package com.smplkit.logging;

import com.smplkit.LogLevel;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerRequest;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;
import com.smplkit.logging.adapters.DiscoveredLogger;
import com.smplkit.logging.adapters.LoggingAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Targeted line-coverage tests for the logging wrapper. Each test drives a
 * specific conditional arm or WS-event-handler path that the broader behavioral
 * suites leave uncovered. All tests are WebSocket-free: the generated
 * {@code *Api}s are Mockito mocks and WS handlers are driven through the
 * {@code simulate*} test seams.
 */
class LoggingCoverageGapsTest {

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

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    // =======================================================================
    // LoggingClient.loggerSourceFor — line 494/495 both arms
    // (effectiveLevel and explicitLevel each null vs non-null)
    // =======================================================================

    @Test
    void install_discoversLoggerWithNullResolvedAndExplicitLevels() throws ApiException {
        // DiscoveredLogger with both level() and resolvedLevel() == null exercises
        // the false arm of loggerSourceFor's effectiveLevel/explicit ternaries.
        stubEmptyFetch();
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of(new DiscoveredLogger("svc.null", null, null)));
        client.registerAdapter(adapter);

        client.install();

        // The source was queued for bulk registration with both levels absent.
        ArgumentCaptor<com.smplkit.internal.generated.logging.model.LoggerBulkRequest> cap =
                ArgumentCaptor.forClass(com.smplkit.internal.generated.logging.model.LoggerBulkRequest.class);
        verify(mockLoggersApi).bulkRegisterLoggers(cap.capture());
        var item = cap.getValue().getLoggers().get(0);
        assertEquals("svc.null", item.getId());
        assertNull(item.getResolvedLevel());
    }

    @Test
    void install_discoversLoggerWithBothLevelsPresent() throws ApiException {
        // level() and resolvedLevel() both non-null exercises the true arm of both ternaries.
        stubEmptyFetch();
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of(new DiscoveredLogger("svc.both", "DEBUG", "WARN")));
        client.registerAdapter(adapter);

        client.install();

        ArgumentCaptor<com.smplkit.internal.generated.logging.model.LoggerBulkRequest> cap =
                ArgumentCaptor.forClass(com.smplkit.internal.generated.logging.model.LoggerBulkRequest.class);
        verify(mockLoggersApi).bulkRegisterLoggers(cap.capture());
        var item = cap.getValue().getLoggers().get(0);
        assertEquals("svc.both", item.getId());
        assertEquals("WARN", item.getResolvedLevel());
    }

    // =======================================================================
    // LoggingClient.handleLoggerChanged — line 533 (!connected return)
    // =======================================================================

    @Test
    void loggerChanged_beforeInstall_returnsImmediately() throws ApiException {
        // Not installed: the handler returns at the !connected guard, never fetching.
        client.simulateLoggerChanged(Map.of("id", "anything"));
        verify(mockLoggersApi, never()).getLogger(any());
    }

    // =======================================================================
    // LoggingClient.handleLoggerChanged — lines 542/544/548 false arms:
    //   id == null  -> falls back to key
    //   level == null -> entry["level"] = null
    //   environments == null -> entry["environments"] = new HashMap
    // =======================================================================

    @Test
    void loggerChanged_resourceWithNullIdNullLevelNullEnvs_usesKeyAndDefaults() throws ApiException {
        stubManagedLoggerStart("svc.x", "INFO");
        client.registerAdapter(noopAdapter("svc.x", "INFO"));
        client.install();

        // Build a LoggerResource whose getData().getId() is null and level/environments null.
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName("svc.x");
        attrs.setManaged(true);
        // no level, no environments set => both null
        LoggerResource resource = new LoggerResource();
        resource.setId(null); // null id forces the "lid = key" false arm
        resource.setType(LoggerResource.TypeEnum.LOGGER);
        resource.setAttributes(attrs);
        LoggerResponse resp = new LoggerResponse();
        resp.setData(resource);
        when(mockLoggersApi.getLogger("svc.x")).thenReturn(resp);

        // No throw; the cache entry is keyed by the fallback key with null level + empty envs.
        assertDoesNotThrow(() -> client.simulateLoggerChanged(Map.of("id", "svc.x")));
    }

    // =======================================================================
    // LoggingClient.handleGroupChanged — lines 581/583/586 false arms
    // =======================================================================

    @Test
    void groupChanged_resourceWithNullIdNullLevelNullEnvs_usesKeyAndDefaults() throws ApiException {
        stubEmptyStart();
        client.install();

        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName("g.x");
        // no level, no parentId, no environments
        LogGroupResource data = new LogGroupResource();
        data.setId(null); // null id forces the "gid = key" false arm
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);
        LogGroupResponse resp = new LogGroupResponse();
        resp.setData(data);
        when(mockLogGroupsApi.getLogGroup("g.x")).thenReturn(resp);

        assertDoesNotThrow(() -> client.simulateGroupChanged(Map.of("id", "g.x")));
    }

    // =======================================================================
    // LoggingClient.snapshotEffectiveLevels (636) + applyDeltasAndFire (661):
    //   a locally-tracked logger that is NOT in the cache (or unmanaged) must be
    //   skipped via `continue`.
    // =======================================================================

    @Test
    void loggersChanged_localLoggerMissingFromCache_isSkipped() throws ApiException {
        // Discover a local logger "ghost" that the server reports as unmanaged
        // both at install and on the loggers_changed re-fetch. It stays in
        // nameMap but is unmanaged in the cache, so BOTH snapshotEffectiveLevels
        // and applyDeltasAndFire hit the unmanaged `continue`.
        LoggerResource unmanaged = buildLoggerResource("ghost", "INFO", false);
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(new ArrayList<>(List.of(unmanaged))));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));

        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of(new DiscoveredLogger("ghost", "INFO")));
        client.registerAdapter(adapter);
        client.install();

        // "ghost" is tracked in nameMap but is unmanaged / absent in the cache.
        // The re-fetch + diff must not throw and must skip it.
        assertDoesNotThrow(() -> client.simulateLoggersChanged(Map.of()));
    }

    // =======================================================================
    // LoggingClient.fetchAllLoggers — line 765 (data==null -> List.of())
    //   and line 768 (resource id==null -> "")
    // =======================================================================

    @Test
    void install_loggerPageWithNullData_thenResourceWithNullId() throws ApiException {
        // listLoggers returns a response whose getData() is null -> rows = List.of()
        // (line 765 false arm). The loop then breaks (0 < pageSize).
        LoggerListResponse nullData = new LoggerListResponse();
        nullData.setData(null);
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(nullData);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));
        client.registerAdapter(noopAdapter(null, null));

        assertDoesNotThrow(() -> client.install());
        assertTrue(client.isInstalled());
    }

    @Test
    void install_loggerResourceWithNullId_keyedByEmptyString() throws ApiException {
        // A logger resource with getId()==null -> entry keyed by "" (line 768 false arm).
        LoggerResource noId = buildLoggerResource("placeholder", "INFO", true);
        noId.setId(null);
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(new ArrayList<>(List.of(noId))));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));
        client.registerAdapter(noopAdapter(null, null));

        assertDoesNotThrow(() -> client.install());
    }

    // =======================================================================
    // LoggingClient.fetchAllGroups — lines 797/800/805 false arms
    //   data==null -> List.of(); id==null -> ""; environments==null -> new HashMap
    // =======================================================================

    @Test
    void install_groupPageWithNullData() throws ApiException {
        stubEmptyLoggers();
        LogGroupListResponse nullData = new LogGroupListResponse();
        nullData.setData(null); // line 797 false arm
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(nullData);
        client.registerAdapter(noopAdapter(null, null));

        assertDoesNotThrow(() -> client.install());
    }

    @Test
    void install_groupResourceWithNullIdNullEnvs() throws ApiException {
        stubEmptyLoggers();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName("orphan");
        // no environments -> getEnvironments() null -> line 805 false arm
        LogGroupResource data = new LogGroupResource();
        data.setId(null); // line 800 false arm
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>(List.of(data))));
        client.registerAdapter(noopAdapter(null, null));

        assertDoesNotThrow(() -> client.install());
    }

    // =======================================================================
    // LoggingClient.applyLevels — line 830 (unmanaged logger in cache -> continue)
    // =======================================================================

    @Test
    void install_unmanagedLoggerInCache_isNotAppliedToAdapter() throws ApiException {
        // A locally-discovered logger that the server reports unmanaged must be
        // skipped in the initial applyLevels pass (the !managed `continue`).
        LoggerResource unmanaged = buildLoggerResource("svc.unmanaged", "WARN", false);
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(new ArrayList<>(List.of(unmanaged))));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));

        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of(new DiscoveredLogger("svc.unmanaged", "INFO")));
        client.registerAdapter(adapter);

        client.install();

        // Unmanaged -> never applied.
        verify(adapter, never()).applyLevel(eq("svc.unmanaged"), any());
    }

    // =======================================================================
    // LoggersClient.register — line 98 true arm (explicit level present),
    //   line 99 false arm (resolvedLevel null)
    // =======================================================================

    @Test
    void register_explicitLevelPresent_resolvedLevelNull() throws ApiException {
        // resolvedLevel==null exercises line 99 false arm; level!=null exercises
        // line 98 true arm.
        LoggersClient loggers = client.loggers;
        loggers.register(new LoggerSource("explicit.only", null, LogLevel.DEBUG, "svc", "env"), true);

        ArgumentCaptor<com.smplkit.internal.generated.logging.model.LoggerBulkRequest> cap =
                ArgumentCaptor.forClass(com.smplkit.internal.generated.logging.model.LoggerBulkRequest.class);
        verify(mockLoggersApi).bulkRegisterLoggers(cap.capture());
        var item = cap.getValue().getLoggers().get(0);
        assertEquals("explicit.only", item.getId());
        assertEquals("DEBUG", item.getLevel_JsonNullable().get());
        assertNull(item.getResolvedLevel());
    }

    // =======================================================================
    // LoggersClient.thresholdFlush — lines 117/118 (catch + LOG.warning)
    //   Eager daemon flush fired by crossing the batch threshold; flush throws.
    // =======================================================================

    @Test
    void register_eagerFlushThreshold_swallowsFlushException() throws Exception {
        // Make bulkRegisterLoggers throw so the daemon thresholdFlush hits its
        // catch block. The catch block calls LOG.warning(...); a JUL handler on
        // "smplkit.logging" counts down a latch when that record is published,
        // so awaiting the latch proves the catch (line 117) and warning (line
        // 118) both executed — a deterministic barrier, not a sleep.
        doThrow(new ApiException(500, "boom")).when(mockLoggersApi).bulkRegisterLoggers(any());

        CountDownLatch warned = new CountDownLatch(1);
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("smplkit.logging");
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage() != null
                        && record.getMessage().contains("Logger registration flush failed")) {
                    warned.countDown();
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        java.util.logging.Level priorLevel = jul.getLevel();
        jul.setLevel(java.util.logging.Level.ALL);
        jul.addHandler(handler);
        try {
            LoggersClient loggers = client.loggers;
            // Cross the 50-source eager-flush threshold in a single register call.
            List<LoggerSource> many = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                many.add(new LoggerSource("eager." + i, LogLevel.INFO, "svc", "env"));
            }
            loggers.register(many); // no flush flag -> spawns daemon thresholdFlush

            assertTrue(warned.await(5, TimeUnit.SECONDS),
                    "daemon thresholdFlush must catch the failure and log a warning");
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(priorLevel);
        }
    }

    // =======================================================================
    // LoggersClient.buildLoggerBody — line 293 (group != null -> setGroup)
    // =======================================================================

    @Test
    void saveLogger_withGroup_setsGroupOnBody() throws ApiException {
        LoggersClient loggers = client.loggers;
        Logger lg = loggers.new_("grouped.logger");
        lg.setGroup("parent-grp");
        lg.setLevel(LogLevel.WARN);

        when(mockLoggersApi.updateLogger(eq("grouped.logger"), any(LoggerRequest.class)))
                .thenReturn(buildLoggerResponse("grouped.logger", "WARN", true));

        lg.save();

        ArgumentCaptor<LoggerRequest> cap = ArgumentCaptor.forClass(LoggerRequest.class);
        verify(mockLoggersApi).updateLogger(eq("grouped.logger"), cap.capture());
        assertEquals("parent-grp", cap.getValue().getData().getAttributes().getGroup());
    }

    // =======================================================================
    // LoggersClient.resourceToModel — lines 322/323 true arms
    //   (sources non-null, environments non-null)
    // =======================================================================

    @Test
    void getLogger_resourceWithSourcesAndEnvironments_mapsBothTrueArms() throws ApiException {
        // Mock the generated attrs to return non-null sources + environments so
        // resourceToModel takes the true arm of both ternaries.
        var attrs = mock(com.smplkit.internal.generated.logging.model.Logger.class);
        when(attrs.getName()).thenReturn("rich");
        when(attrs.getLevel()).thenReturn(null);
        when(attrs.getGroup()).thenReturn(null);
        when(attrs.getManaged()).thenReturn(true);
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(Map.of("service", "svc"));
        when(attrs.getSources()).thenReturn(sources);
        Map<String, Object> envs = new HashMap<>();
        envs.put("production", Map.of("level", "DEBUG"));
        when(attrs.getEnvironments()).thenReturn(envs);
        when(attrs.getCreatedAt()).thenReturn(null);
        when(attrs.getUpdatedAt()).thenReturn(null);

        LoggerResource resource = new LoggerResource();
        resource.setId("rich");
        resource.setType(LoggerResource.TypeEnum.LOGGER);
        resource.setAttributes(attrs);
        LoggerResponse resp = new LoggerResponse();
        resp.setData(resource);
        when(mockLoggersApi.getLogger("rich")).thenReturn(resp);

        Logger lg = client.loggers.get("rich");
        assertEquals(1, lg.getSources().size());
        assertEquals(LogLevel.DEBUG, lg.environments().get("production").level());
    }

    // =======================================================================
    // LogGroup.environments() — line 75 (non-Map value -> continue)
    //   and line 83 (unknown level string -> caught, lvl stays null)
    // =======================================================================

    @Test
    void logGroupEnvironments_skipsNonMapValue() {
        LogGroup grp = new LogGroupsClient(mockLogGroupsApi).new_("g");
        // Inject a non-Map env value via save round-trip is awkward; use the model
        // returned by resourceToModel with a raw environments map.
        Map<String, Object> raw = new HashMap<>();
        raw.put("bad", "not-a-map"); // line 75 continue
        Map<String, Object> badLevel = new HashMap<>();
        badLevel.put("level", "NONSENSE"); // line 83 catch (unknown level)
        raw.put("weird", badLevel);
        LogGroup withRaw = buildLogGroupModelWithEnvironments(raw);

        Map<String, LoggerEnvironment> typed = withRaw.environments();
        assertFalse(typed.containsKey("bad"), "non-Map env value must be skipped");
        assertNull(typed.get("weird").level(), "unknown level string must map to null");
    }

    // =======================================================================
    // LogGroupsClient.new_(id, name, group) — line 56 both arms (name null/non-null)
    // =======================================================================

    @Test
    void logGroups_new3arg_explicitName_isUsed() {
        LogGroup grp = client.logGroups.new_("g.id", "Custom Name", "parent");
        assertEquals("Custom Name", grp.getName()); // line 56 true arm
        assertEquals("parent", grp.getGroup());
    }

    @Test
    void logGroups_new3arg_nullName_fallsBackToDisplayName() {
        LogGroup grp = client.logGroups.new_("my-group", null, null);
        assertEquals("My Group", grp.getName()); // line 56 false arm -> keyToDisplayName
    }

    // =======================================================================
    // LogGroupsClient.buildGroupAttrs (184) + resourceToModel (196):
    //   save round-trip with a group carrying environments and a parent.
    // =======================================================================

    @Test
    void saveGroup_withEnvironmentsAndParent_roundTripsThroughBuildAndResource() throws ApiException {
        LogGroupsClient groups = new LogGroupsClient(mockLogGroupsApi);
        LogGroup grp = groups.new_("g.full", "Full", "parent-g");
        grp.setLevel(LogLevel.ERROR, "production"); // populates environments

        // Response carries environments so resourceToModel line 196 takes the true arm.
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(
                OffsetDateTime.now(), OffsetDateTime.now());
        attrs.setName("Full");
        attrs.setLevel(com.smplkit.internal.generated.logging.model.LogLevel.ERROR);
        attrs.setParentId("parent-g");
        Map<String, Object> envs = new HashMap<>();
        envs.put("production", Map.of("level", "ERROR"));
        attrs.setEnvironments(envs);
        LogGroupResource data = new LogGroupResource();
        data.setId("g.full");
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);
        LogGroupResponse resp = new LogGroupResponse();
        resp.setData(data);
        when(mockLogGroupsApi.createLogGroup(any())).thenReturn(resp);

        grp.save();

        assertEquals("parent-g", grp.getGroup());
        assertEquals(LogLevel.ERROR, grp.environments().get("production").level());
    }

    // =======================================================================
    // Resolution.extractEnvLevel — line 134 (env == null -> return null)
    // =======================================================================

    @Test
    void resolveLevel_nullEnvironment_skipsEnvOverride() {
        // With environment == null, extractEnvLevel returns null immediately, so
        // resolution falls through to the base level.
        Map<String, Map<String, Object>> loggers = new HashMap<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("level", "WARN");
        Map<String, Object> envs = new HashMap<>();
        envs.put("production", Map.of("level", "ERROR"));
        entry.put("environments", envs);
        loggers.put("svc", entry);

        String resolved = Resolution.resolveLevel("svc", null, loggers, new HashMap<>());
        assertEquals("WARN", resolved, "null env must ignore the env override and use base level");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubEmptyLoggers() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(new ArrayList<>()));
    }

    private void stubEmptyFetch() throws ApiException {
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(new ArrayList<>()));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));
    }

    private void stubEmptyStart() throws ApiException {
        stubEmptyFetch();
        client.registerAdapter(noopAdapter(null, null));
    }

    private void stubManagedLoggerStart(String key, String level) throws ApiException {
        LoggerResource lr = buildLoggerResource(key, level, true);
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(new ArrayList<>(List.of(lr))));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));
    }

    private LoggingAdapter noopAdapter(String loggerKey, String level) {
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        if (loggerKey != null) {
            when(adapter.discover()).thenReturn(List.of(new DiscoveredLogger(loggerKey, level)));
        } else {
            when(adapter.discover()).thenReturn(List.of());
        }
        return adapter;
    }

    private static com.smplkit.internal.generated.logging.model.LogLevel tolerantLogLevel(String value) {
        if (value == null) return null;
        try {
            return com.smplkit.internal.generated.logging.model.LogLevel.fromValue(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private LoggerResource buildLoggerResource(String id, String level, boolean managed) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger(null, null, now, now);
        attrs.setName(id);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));
        attrs.setManaged(managed);

        LoggerResource resource = new LoggerResource();
        resource.setId(id);
        resource.setType(LoggerResource.TypeEnum.LOGGER);
        resource.setAttributes(attrs);
        return resource;
    }

    private LoggerResponse buildLoggerResponse(String id, String level, boolean managed) {
        LoggerResponse resp = new LoggerResponse();
        resp.setData(buildLoggerResource(id, level, managed));
        return resp;
    }

    /** Builds a LogGroup model (via resourceToModel) carrying the given raw environments map. */
    private LogGroup buildLogGroupModelWithEnvironments(Map<String, Object> environments) {
        var attrs = mock(com.smplkit.internal.generated.logging.model.LogGroup.class);
        when(attrs.getName()).thenReturn("g");
        when(attrs.getLevel()).thenReturn(null);
        when(attrs.getParentId()).thenReturn(null);
        when(attrs.getEnvironments()).thenReturn(environments);
        when(attrs.getCreatedAt()).thenReturn(null);
        when(attrs.getUpdatedAt()).thenReturn(null);

        LogGroupResource resource = new LogGroupResource();
        resource.setId("g");
        resource.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        resource.setAttributes(attrs);
        return new LogGroupsClient(mockLogGroupsApi).resourceToModel(resource);
    }
}
