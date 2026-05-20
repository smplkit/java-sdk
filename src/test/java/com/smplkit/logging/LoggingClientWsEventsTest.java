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
 * Tests for LoggingClient WS event behaviors. Listener semantics:
 * <ul>
 *   <li>Every {@code adapter.applyLevel(...)} call fires the matching
 *       key-scoped listener AND every global listener with the per-key event.
 *   <li>Logger / group deletion is not a level change — no listener fires
 *       for the deleted key itself. Dependent loggers whose resolved level
 *       moves go through the normal apply path.
 *   <li>An edit that leaves the resolved level unchanged fires no listeners.
 * </ul>
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
        client.install();

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
        verify(mockLoggersApi, times(1)).getLogger("com.acme.service");
        verify(mockLoggersApi, times(1)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()); // only start
    }

    @Test
    void loggerChanged_contentUnchanged_listenerDoesNotFire() throws ApiException {
        stubManagedLoggerStart("com.acme.service", "INFO");
        client.registerAdapter(noopAdapter("com.acme.service", "INFO"));
        client.install();

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
        client.install();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateLoggerChanged(Map.of()); // no "id"

        assertEquals(0, count.get());
        verify(mockLoggersApi, never()).getLogger(any());
    }

    @Test
    void loggerChanged_levelUnchanged_doesNotFireGlobalOrScoped() throws ApiException {
        // Diff-based firing: when the resolved level does not move, no listener
        // fires (key-scoped or global). Mirrors the negative check that protects
        // against a raw-diff implementation firing on cosmetic field edits.
        stubManagedLoggerStart("com.acme.service", "INFO");
        client.registerAdapter(noopAdapter("com.acme.service", "INFO"));
        client.install();

        AtomicInteger globalCount = new AtomicInteger();
        AtomicInteger keyedCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());
        client.onChange("com.acme.service", e -> keyedCount.incrementAndGet());
        globalCount.set(0);
        keyedCount.set(0);

        when(mockLoggersApi.getLogger("com.acme.service"))
                .thenReturn(buildLoggerResponse("com.acme.service", "INFO", true));

        client.simulateLoggerChanged(Map.of("id", "com.acme.service"));

        assertEquals(0, globalCount.get(), "Global listener must not fire when resolved level unchanged");
        assertEquals(0, keyedCount.get(), "Scoped listener must not fire when resolved level unchanged");
    }

    @Test
    void loggerChanged_dotAncestor_firesDescendantListener() throws ApiException {
        // Two managed loggers:
        //   com.acme         — own level WARN (the dot-ancestor)
        //   com.acme.payments — no own level, no group; inherits via dot-ancestry from com.acme
        // Server flips com.acme to ERROR. com.acme.payments now resolves to ERROR
        // and its key-scoped listener must fire.
        LoggerResource ancestor = buildLoggerResource("com.acme", "WARN", true);
        LoggerResource child = buildLoggerResource("com.acme.payments", null, true);
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>(List.of(ancestor, child)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));

        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of(
                new com.smplkit.logging.adapters.DiscoveredLogger("com.acme", "INFO"),
                new com.smplkit.logging.adapters.DiscoveredLogger("com.acme.payments", "INFO")
        ));
        client.registerAdapter(adapter);
        client.install();

        // Sanity: install pushed WARN to both adapters (descendant inherits ancestor's WARN)
        verify(adapter, times(1)).applyLevel("com.acme", "WARN");
        verify(adapter, times(1)).applyLevel("com.acme.payments", "WARN");

        AtomicReference<LoggerChangeEvent> ancestorEvent = new AtomicReference<>();
        AtomicReference<LoggerChangeEvent> childEvent = new AtomicReference<>();
        client.onChange("com.acme", ancestorEvent::set);
        client.onChange("com.acme.payments", childEvent::set);

        // Server flips com.acme to ERROR
        when(mockLoggersApi.getLogger("com.acme"))
                .thenReturn(buildLoggerResponse("com.acme", "ERROR", true));
        client.simulateLoggerChanged(Map.of("id", "com.acme"));

        verify(adapter, times(1)).applyLevel("com.acme", "ERROR");
        verify(adapter, times(1)).applyLevel("com.acme.payments", "ERROR");
        assertNotNull(ancestorEvent.get(), "ancestor listener fires");
        assertEquals(LogLevel.ERROR, ancestorEvent.get().level());
        assertNotNull(childEvent.get(), "dot-descendant listener must fire on ancestor change");
        assertEquals(LogLevel.ERROR, childEvent.get().level());
    }

    // -----------------------------------------------------------------------
    // E. logger_deleted — remove from cache, no listener for the deleted key,
    // no fetch; dependent loggers (dot-descendants) re-resolve via the normal
    // apply path.
    // -----------------------------------------------------------------------

    @Test
    void loggerDeleted_deletedKeyItself_firesNoListener() throws ApiException {
        // Deletion is not a level change — a listener registered on the
        // deleted key gets nothing.
        stubManagedLoggerStart("com.acme.service", "INFO");
        client.registerAdapter(noopAdapter("com.acme.service", "INFO"));
        client.install();

        AtomicInteger keyedCount = new AtomicInteger();
        AtomicInteger globalCount = new AtomicInteger();
        client.onChange("com.acme.service", e -> keyedCount.incrementAndGet());
        client.onChange(e -> globalCount.incrementAndGet());
        keyedCount.set(0);
        globalCount.set(0);

        client.simulateLoggerDeleted(Map.of("id", "com.acme.service"));

        // The deleted key was tracked and had own level INFO. After cache
        // removal it resolves to INFO again (system fallback) — same value,
        // so no apply, no listener invocation.
        assertEquals(0, keyedCount.get(), "no listener fires for the deleted key itself");
        assertEquals(0, globalCount.get(), "no global event for deletion");
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
        client.install();

        AtomicInteger count = new AtomicInteger();
        client.onChange(e -> count.incrementAndGet());

        client.simulateLoggerDeleted(Map.of()); // no "id"

        assertEquals(0, count.get());
    }

    @Test
    void loggerDeleted_dotAncestor_reappliesFallbackForDescendant() throws ApiException {
        // Same shape as the dot-ancestry change test, but the ancestor is
        // deleted instead of mutated. The descendant must re-resolve (to INFO
        // fallback here) and its listener must fire.
        LoggerResource ancestor = buildLoggerResource("com.acme", "WARN", true);
        LoggerResource child = buildLoggerResource("com.acme.payments", null, true);
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>(List.of(ancestor, child)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));

        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of(
                new com.smplkit.logging.adapters.DiscoveredLogger("com.acme", "INFO"),
                new com.smplkit.logging.adapters.DiscoveredLogger("com.acme.payments", "INFO")
        ));
        client.registerAdapter(adapter);
        client.install();

        verify(adapter, times(1)).applyLevel("com.acme", "WARN");
        verify(adapter, times(1)).applyLevel("com.acme.payments", "WARN");

        AtomicReference<LoggerChangeEvent> ancestorEvent = new AtomicReference<>();
        AtomicReference<LoggerChangeEvent> childEvent = new AtomicReference<>();
        client.onChange("com.acme", ancestorEvent::set);
        client.onChange("com.acme.payments", childEvent::set);

        client.simulateLoggerDeleted(Map.of("id", "com.acme"));

        // Deletion is not a level change. The deleted key fires nothing,
        // and the SDK stops pushing levels to its adapter slot.
        assertNull(ancestorEvent.get(),
                "deleted logger's own listener does not fire");
        verify(adapter, never()).applyLevel("com.acme", "INFO");
        // Descendant re-resolves to INFO fallback; adapter and listener notified
        verify(adapter, times(1)).applyLevel("com.acme.payments", "INFO");
        assertNotNull(childEvent.get(), "dot-descendant listener must fire on ancestor deletion");
        assertEquals(LogLevel.INFO, childEvent.get().level());
    }

    // -----------------------------------------------------------------------
    // E. group_changed — scoped fetch, diff-based
    // -----------------------------------------------------------------------

    @Test
    void groupChanged_scopedFetch_isCalledNotListLoggers() throws ApiException {
        stubEmptyStart();
        client.install();

        when(mockLogGroupsApi.getLogGroup("my-group"))
                .thenReturn(buildGroupResponse("my-group", "INFO"));

        client.simulateGroupChanged(Map.of("id", "my-group"));

        verify(mockLogGroupsApi, times(1)).getLogGroup("my-group");
        verify(mockLoggersApi, times(1)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()); // only start
    }

    @Test
    void groupChanged_missingId_isNoOp() throws ApiException {
        stubEmptyStart();
        client.install();

        client.simulateGroupChanged(Map.of()); // no "id"

        verify(mockLogGroupsApi, never()).getLogGroup(any());
    }

    @Test
    void groupChanged_beforeStart_isNoOp() throws ApiException {
        client.simulateGroupChanged(Map.of("id", "some-group"));

        verify(mockLogGroupsApi, never()).getLogGroup(any());
    }

    @Test
    void groupChanged_reappliesInheritedLevelOnDependentLogger() throws ApiException {
        // Logger with no own level inherits from group "g1" (WARN)
        String key = "com.acme.inherits";
        LoggerResource lr = buildLoggerResource(key, null, true);
        lr.getAttributes().setGroup("g1");
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(buildGroupListResponse("g1", "WARN"));

        LoggingAdapter adapter = noopAdapter(key, "INFO");
        client.registerAdapter(adapter);
        client.install();

        // After install, adapter should have been told WARN (group inherited)
        verify(adapter, times(1)).applyLevel(key, "WARN");

        // Now group's level changes to ERROR
        when(mockLogGroupsApi.getLogGroup("g1")).thenReturn(buildGroupResponse("g1", "ERROR"));

        AtomicReference<LoggerChangeEvent> received = new AtomicReference<>();
        client.onChange(key, received::set);

        client.simulateGroupChanged(Map.of("id", "g1"));

        // Dependent logger should be re-applied at the new resolved level
        verify(adapter, times(1)).applyLevel(key, "ERROR");
        assertNotNull(received.get(), "key-scoped listener should fire on group-driven change");
        assertEquals(LogLevel.ERROR, received.get().level());
    }

    @Test
    void groupChanged_levelUnchanged_doesNotReapply() throws ApiException {
        String key = "com.acme.stable";
        LoggerResource lr = buildLoggerResource(key, null, true);
        lr.getAttributes().setGroup("g1");
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(buildGroupListResponse("g1", "WARN"));

        LoggingAdapter adapter = noopAdapter(key, "INFO");
        client.registerAdapter(adapter);
        client.install();
        verify(adapter, times(1)).applyLevel(key, "WARN");

        // Group's level stays WARN — no diff
        when(mockLogGroupsApi.getLogGroup("g1")).thenReturn(buildGroupResponse("g1", "WARN"));

        AtomicInteger keyedCount = new AtomicInteger();
        client.onChange(key, e -> keyedCount.incrementAndGet());

        client.simulateGroupChanged(Map.of("id", "g1"));

        // Adapter was already at WARN; no extra applyLevel call
        verify(adapter, times(1)).applyLevel(key, "WARN");
        assertEquals(0, keyedCount.get(),
                "Key-scoped listener should not fire when resolved level unchanged");
    }

    @Test
    void groupDeleted_reappliesFallbackForDependentLogger() throws ApiException {
        // Logger inherits from group "g1" (WARN) — install pushes WARN
        String key = "com.acme.orphan";
        LoggerResource lr = buildLoggerResource(key, null, true);
        lr.getAttributes().setGroup("g1");
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(buildGroupListResponse("g1", "WARN"));

        LoggingAdapter adapter = noopAdapter(key, "INFO");
        client.registerAdapter(adapter);
        client.install();
        verify(adapter, times(1)).applyLevel(key, "WARN");

        AtomicReference<LoggerChangeEvent> dependentEvent = new AtomicReference<>();
        AtomicInteger groupKeyListenerCount = new AtomicInteger();
        client.onChange(key, dependentEvent::set);
        client.onChange("g1", e -> groupKeyListenerCount.incrementAndGet());

        // group_deleted removes g1; dependent logger now resolves to INFO fallback.
        client.simulateGroupDeleted(Map.of("id", "g1"));

        // Adapter re-applied at fallback level
        verify(adapter, times(1)).applyLevel(key, "INFO");
        // Key-scoped listener for the dependent logger fired with new level
        assertNotNull(dependentEvent.get(), "dependent logger should re-fire on group_deleted");
        assertEquals(LogLevel.INFO, dependentEvent.get().level());
        // Deletion is not a level change — the deleted group's listener fires nothing.
        assertEquals(0, groupKeyListenerCount.get(),
                "deleted group's listener does not fire — deletion is not a level change");
    }

    // -----------------------------------------------------------------------
    // E. group_deleted — remove from cache, no listener for the deleted key,
    // no fetch; dependent loggers re-resolve via the normal apply path.
    // -----------------------------------------------------------------------

    @Test
    void groupDeleted_deletedKeyItself_firesNoListener() throws ApiException {
        // A listener registered on the deleted group id receives nothing —
        // deletion is not a level change.
        LoggerListResponse emptyLoggers = new LoggerListResponse();
        emptyLoggers.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(emptyLoggers);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(buildGroupListResponse("my-group", "WARN"));
        client.registerAdapter(noopAdapter(null, null));
        client.install();

        AtomicInteger groupListenerCount = new AtomicInteger();
        AtomicInteger globalCount = new AtomicInteger();
        client.onChange("my-group", e -> groupListenerCount.incrementAndGet());
        client.onChange(e -> globalCount.incrementAndGet());

        client.simulateGroupDeleted(Map.of("id", "my-group"));

        assertEquals(0, groupListenerCount.get(), "deleted group fires no key-scoped listener");
        assertEquals(0, globalCount.get(), "deleted group fires no global event");
        verify(mockLogGroupsApi, never()).getLogGroup(any());
    }

    @Test
    void groupDeleted_unknownGroup_doesNotFireListener() throws ApiException {
        stubEmptyStart();
        client.install();

        AtomicInteger count = new AtomicInteger();
        client.onChange("ghost-group", e -> count.incrementAndGet());

        client.simulateGroupDeleted(Map.of("id", "ghost-group"));

        assertEquals(0, count.get(),
                "Deletion of an unknown group is a no-op for the synthetic deleted listener");
    }

    @Test
    void groupDeleted_listenerException_doesNotStopProcessing() throws ApiException {
        // Dependent logger inherits group "g" at WARN; deleting "g" makes
        // the dependent re-resolve to INFO and fires its listeners. One of
        // those listeners throws — the other must still fire.
        String key = "app.db";
        LoggerResource lr = buildLoggerResource(key, null, true);
        lr.getAttributes().setGroup("g");
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(buildGroupListResponse("g", "WARN"));
        client.registerAdapter(noopAdapter(key, "INFO"));
        client.install();

        AtomicInteger okCount = new AtomicInteger();
        client.onChange(key, e -> { throw new RuntimeException("boom"); });
        client.onChange(key, e -> okCount.incrementAndGet());

        assertDoesNotThrow(() -> client.simulateGroupDeleted(Map.of("id", "g")));
        assertEquals(1, okCount.get(), "Second listener still fires after first throws");
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
        client.install();

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
        client.install();

        // Second listLoggers call returns same empty list
        LoggerListResponse emptyLoggers = new LoggerListResponse();
        emptyLoggers.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(emptyLoggers);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        client.simulateLoggersChanged(Map.of());

        // listLoggers called once for start, once for loggers_changed
        verify(mockLoggersApi, times(2)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
        // listLogGroups called once for start, once for loggers_changed
        verify(mockLogGroupsApi, times(2)).listLogGroups(isNull(), any(), any(), isNull());
    }

    @Test
    void loggersChanged_beforeStart_isNoOp() throws ApiException {
        client.simulateLoggersChanged(Map.of());

        verify(mockLoggersApi, never()).listLoggers(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void loggersChanged_globalListenerFiresOnce_notPerChangedKey() throws ApiException {
        // Start with managed logger at INFO
        stubManagedLoggerStart("com.acme.svc", "INFO");
        client.registerAdapter(noopAdapter("com.acme.svc", "INFO"));
        client.install();

        AtomicInteger globalCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());
        globalCount.set(0); // clear start events

        // loggers_changed returns WARN — different level triggers diff
        LoggerResource lr = buildLoggerResource("com.acme.svc", "WARN", true);
        LoggerListResponse updatedLoggers = new LoggerListResponse();
        updatedLoggers.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(updatedLoggers);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        client.simulateLoggersChanged(Map.of());

        assertEquals(1, globalCount.get(), "Global listener should fire exactly once per loggers_changed");
    }

    // -----------------------------------------------------------------------
    // Exception paths and edge cases
    // -----------------------------------------------------------------------

    @Test
    void loggerChanged_apiFetchThrows_isNoOp() throws ApiException {
        stubManagedLoggerStart("com.acme.service", "INFO");
        client.registerAdapter(noopAdapter("com.acme.service", "INFO"));
        client.install();

        AtomicInteger count = new AtomicInteger();
        client.onChange("com.acme.service", e -> count.incrementAndGet());
        count.set(0);

        when(mockLoggersApi.getLogger("com.acme.service"))
                .thenThrow(new ApiException("API failure"));

        client.simulateLoggerChanged(Map.of("id", "com.acme.service"));

        assertEquals(0, count.get(), "Listener should not fire when API fetch throws");
    }

    @Test
    void groupChanged_apiFetchThrows_isNoOp() throws ApiException {
        stubEmptyStart();
        client.install();

        when(mockLogGroupsApi.getLogGroup("my-group"))
                .thenThrow(new ApiException("API failure"));

        assertDoesNotThrow(() -> client.simulateGroupChanged(Map.of("id", "my-group")));
        verify(mockLogGroupsApi, times(1)).getLogGroup("my-group");
    }

    @Test
    void loggersChanged_apiFetchThrows_isNoOp() throws ApiException {
        stubEmptyStart();
        client.install();

        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenThrow(new ApiException("API failure"));

        assertDoesNotThrow(() -> client.simulateLoggersChanged(Map.of()));
    }

    @Test
    void loggersChanged_withGroupData_fetchOnlyGroupsLoop() throws ApiException {
        // Start with managed logger at INFO
        stubManagedLoggerStart("com.acme.svc", "INFO");
        client.registerAdapter(noopAdapter("com.acme.svc", "INFO"));
        client.install();

        // loggers_changed: same loggers, but return an actual group in listLogGroups
        LoggerResource lr = buildLoggerResource("com.acme.svc", "INFO", true);
        LoggerListResponse sameLoggers = new LoggerListResponse();
        sameLoggers.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(sameLoggers);

        // Return a group with data to exercise the group loop in fetchOnly
        LogGroupListResponse groupsWithData = buildGroupListResponse("my-group", "DEBUG");
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupsWithData);

        client.simulateLoggersChanged(Map.of());

        // Both APIs called (for start + for loggersChanged)
        verify(mockLogGroupsApi, times(2)).listLogGroups(isNull(), any(), any(), isNull());
    }

    @Test
    void loggersChanged_groupFetchThrows_isNoOp() throws ApiException {
        stubEmptyStart();
        client.install();

        // listLoggers succeeds but listLogGroups throws
        LoggerListResponse emptyLoggers = new LoggerListResponse();
        emptyLoggers.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(emptyLoggers);
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenThrow(new ApiException("group fetch failed"));

        assertDoesNotThrow(() -> client.simulateLoggersChanged(Map.of()));
    }

    @Test
    void diffAndFireLevels_globalListenerThrows_doesNotPropagate() throws ApiException {
        stubManagedLoggerStart("com.acme.svc", "INFO");
        client.registerAdapter(noopAdapter("com.acme.svc", "INFO"));
        client.install();

        client.onChange(e -> { throw new RuntimeException("global crash"); });

        LoggerResource lr = buildLoggerResource("com.acme.svc", "WARN", true);
        LoggerListResponse updated = new LoggerListResponse();
        updated.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(updated);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        assertDoesNotThrow(() -> client.simulateLoggersChanged(Map.of()),
                "Exception in global listener should not propagate");
    }

    @Test
    void applyLevelForKey_adapterThrows_doesNotPropagate() throws ApiException {
        stubManagedLoggerStart("com.acme.svc", "INFO");
        LoggingAdapter throwingAdapter = mock(LoggingAdapter.class);
        when(throwingAdapter.name()).thenReturn("throwing");
        when(throwingAdapter.discover()).thenReturn(List.of(new DiscoveredLogger("com.acme.svc", "INFO")));
        doThrow(new RuntimeException("adapter crash")).when(throwingAdapter).applyLevel(any(), any());
        client.registerAdapter(throwingAdapter);
        client.install();

        LoggerResource lr = buildLoggerResource("com.acme.svc", "WARN", true);
        LoggerListResponse updated = new LoggerListResponse();
        updated.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(updated);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        assertDoesNotThrow(() -> client.simulateLoggersChanged(Map.of()),
                "Exception in adapter.applyLevel should not propagate");
    }

    @Test
    void applyLevelForKey_keyedListenerThrows_doesNotPropagate() throws ApiException {
        stubManagedLoggerStart("com.acme.svc", "INFO");
        client.registerAdapter(noopAdapter("com.acme.svc", "INFO"));
        client.install();

        client.onChange("com.acme.svc", e -> { throw new RuntimeException("key listener crash"); });

        LoggerResource lr = buildLoggerResource("com.acme.svc", "WARN", true);
        LoggerListResponse updated = new LoggerListResponse();
        updated.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(updated);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        assertDoesNotThrow(() -> client.simulateLoggersChanged(Map.of()),
                "Exception in key-scoped listener should not propagate");
    }

    @Test
    void applyLevelForKey_invalidLevel_isNoOp() throws ApiException {
        stubManagedLoggerStart("com.acme.svc", "INFO");
        client.registerAdapter(noopAdapter("com.acme.svc", "INFO"));
        client.install();

        AtomicInteger count = new AtomicInteger();
        client.onChange("com.acme.svc", e -> count.incrementAndGet());
        count.set(0);

        // Return a logger with invalid level string
        LoggerResource lr = buildLoggerResource("com.acme.svc", "INVALID_LEVEL", true);
        LoggerListResponse updated = new LoggerListResponse();
        updated.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(updated);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        assertDoesNotThrow(() -> client.simulateLoggersChanged(Map.of()),
                "Invalid level string should be handled gracefully");
        assertEquals(0, count.get(), "Listener should not fire for invalid level");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // F. refresh() — manual re-fetch + re-apply
    // -----------------------------------------------------------------------

    @Test
    void refresh_beforeInstall_isNoOp() throws ApiException {
        client.refresh();
        verify(mockLoggersApi, never()).listLoggers(any(), any(), any(), any(), any(), any(), any(), any());
        verify(mockLogGroupsApi, never()).listLogGroups(isNull(), any(), any(), isNull());
    }

    @Test
    void refresh_afterInstall_refetchesBothLoggersAndGroups() throws ApiException {
        stubEmptyStart();
        client.install();

        // Reset stubs: refresh issues another listLoggers + listLogGroups.
        LoggerListResponse emptyLoggers = new LoggerListResponse();
        emptyLoggers.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(emptyLoggers);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        client.refresh();

        verify(mockLoggersApi, times(2)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
        verify(mockLogGroupsApi, times(2)).listLogGroups(isNull(), any(), any(), isNull());
    }

    @Test
    void refresh_levelChanged_firesPerKeyAndGlobalListeners() throws ApiException {
        stubManagedLoggerStart("com.acme.svc", "INFO");
        client.registerAdapter(noopAdapter("com.acme.svc", "INFO"));
        client.install();

        AtomicReference<LoggerChangeEvent> keyed = new AtomicReference<>();
        AtomicInteger globalCount = new AtomicInteger();
        client.onChange("com.acme.svc", keyed::set);
        client.onChange(e -> globalCount.incrementAndGet());
        keyed.set(null);
        globalCount.set(0); // clear start events

        // Server now reports WARN — different from cached INFO.
        LoggerResource lr = buildLoggerResource("com.acme.svc", "WARN", true);
        LoggerListResponse changed = new LoggerListResponse();
        changed.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(changed);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        client.refresh();

        assertNotNull(keyed.get(), "Key-scoped listener should fire when refresh sees a level change");
        assertEquals("com.acme.svc", keyed.get().id());
        assertEquals(LogLevel.WARN, keyed.get().level());
        assertEquals("manual", keyed.get().source());
        assertEquals(1, globalCount.get(), "Global listener should fire exactly once per refresh");
    }

    @Test
    void refresh_levelUnchanged_doesNotFireListeners() throws ApiException {
        stubManagedLoggerStart("com.acme.svc", "INFO");
        client.registerAdapter(noopAdapter("com.acme.svc", "INFO"));
        client.install();

        AtomicInteger keyedCount = new AtomicInteger();
        AtomicInteger globalCount = new AtomicInteger();
        client.onChange("com.acme.svc", e -> keyedCount.incrementAndGet());
        client.onChange(e -> globalCount.incrementAndGet());
        keyedCount.set(0);
        globalCount.set(0);

        // Server returns the same level as cached — no diff.
        LoggerResource lr = buildLoggerResource("com.acme.svc", "INFO", true);
        LoggerListResponse same = new LoggerListResponse();
        same.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(same);
        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        client.refresh();

        assertEquals(0, keyedCount.get(), "Key-scoped listener should not fire when level unchanged");
        assertEquals(0, globalCount.get(), "Global listener should not fire when nothing changed");
    }

    @Test
    void refresh_apiFetchThrows_isNoOp() throws ApiException {
        stubEmptyStart();
        client.install();

        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenThrow(new ApiException("API failure"));

        assertDoesNotThrow(() -> client.refresh());
    }

    // -----------------------------------------------------------------------

    private void stubEmptyStart() throws ApiException {
        LoggerListResponse emptyLoggers = new LoggerListResponse();
        emptyLoggers.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(emptyLoggers);

        LogGroupListResponse emptyGroups = new LogGroupListResponse();
        emptyGroups.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(emptyGroups);

        client.registerAdapter(noopAdapter(null, null));
    }

    private void stubManagedLoggerStart(String key, String level) throws ApiException {
        LoggerResource lr = buildLoggerResource(key, level, true);
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull())).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groupResp);
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

    private static com.smplkit.internal.generated.logging.model.LogLevel tolerantLogLevel(String value) {
        if (value == null) return null;
        try { return com.smplkit.internal.generated.logging.model.LogLevel.fromValue(value); }
        catch (IllegalArgumentException e) { return null; }
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
        LoggerResource resource = buildLoggerResource(id, level, managed);
        LoggerResponse resp = new LoggerResponse();
        resp.setData(resource);
        return resp;
    }

    private LogGroupListResponse buildGroupListResponse(String id, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(now, now);
        attrs.setName(id);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));

        LogGroupResource data = new LogGroupResource();
        data.setId(id);
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);

        LogGroupListResponse resp = new LogGroupListResponse();
        resp.setData(new ArrayList<>(List.of(data)));
        return resp;
    }

    private LogGroupResponse buildGroupResponse(String id, String level) {
        OffsetDateTime now = OffsetDateTime.now();
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup(now, now);
        attrs.setName(id);
        if (level != null) attrs.setLevel(tolerantLogLevel(level));

        LogGroupResource data = new LogGroupResource();
        data.setId(id);
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);

        LogGroupResponse resp = new LogGroupResponse();
        resp.setData(data);
        return resp;
    }

    // -----------------------------------------------------------------------
    // Per-logger global fanout — the four diagnostic scenarios from the
    // change-listener semantics prompt. Each scenario asserts that ONE
    // global listener gets ONE invocation per affected logger (never one
    // summary event), and that deletion fires nothing for the deleted key.
    // -----------------------------------------------------------------------

    @Test
    void diag1_logger_changed_dotAncestor_globalFiresOncePerDescendant() throws ApiException {
        // Managed `com.acme` at WARN; 5 managed descendants inherit via dot-ancestry.
        List<LoggerResource> data = new ArrayList<>();
        data.add(buildLoggerResource("com.acme", "WARN", true));
        String[] descendants = {
                "com.acme.db", "com.acme.queue", "com.acme.api",
                "com.acme.cache", "com.acme.auth"};
        for (String d : descendants) data.add(buildLoggerResource(d, null, true));
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(data));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(new LogGroupListResponse().data(new ArrayList<>()));
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        List<com.smplkit.logging.adapters.DiscoveredLogger> discovered = new ArrayList<>();
        discovered.add(new com.smplkit.logging.adapters.DiscoveredLogger("com.acme", "INFO"));
        for (String d : descendants) {
            discovered.add(new com.smplkit.logging.adapters.DiscoveredLogger(d, "INFO"));
        }
        when(adapter.discover()).thenReturn(discovered);
        client.registerAdapter(adapter);
        client.install();

        AtomicInteger globalCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());

        when(mockLoggersApi.getLogger("com.acme"))
                .thenReturn(buildLoggerResponse("com.acme", "ERROR", true));
        client.simulateLoggerChanged(Map.of("id", "com.acme"));

        // 6 fires: the ancestor + 5 descendants
        assertEquals(6, globalCount.get(),
                "Global listener fires once per affected logger, never as a summary event");
    }

    @Test
    void diag2_group_changed_globalFiresOncePerDependent() throws ApiException {
        // Managed `app.db`, `app.queue`, `app.api` all inheriting from group `app`.
        List<LoggerResource> data = new ArrayList<>();
        for (String k : List.of("app.db", "app.queue", "app.api")) {
            LoggerResource r = buildLoggerResource(k, null, true);
            r.getAttributes().setGroup("app");
            data.add(r);
        }
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(data));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(buildGroupListResponse("app", "WARN"));
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        List<com.smplkit.logging.adapters.DiscoveredLogger> discovered = new ArrayList<>();
        for (String k : List.of("app.db", "app.queue", "app.api")) {
            discovered.add(new com.smplkit.logging.adapters.DiscoveredLogger(k, "INFO"));
        }
        when(adapter.discover()).thenReturn(discovered);
        client.registerAdapter(adapter);
        client.install();

        AtomicInteger globalCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());

        when(mockLogGroupsApi.getLogGroup("app"))
                .thenReturn(buildGroupResponse("app", "ERROR"));
        client.simulateGroupChanged(Map.of("id", "app"));

        assertEquals(3, globalCount.get(),
                "Global listener fires once per dependent logger affected by the group change");
    }

    @Test
    void diag3_group_deleted_globalFiresOncePerDependent_noEventForGroupKey() throws ApiException {
        // Same setup as diag2: 3 dependents of group `app` at WARN.
        List<LoggerResource> data = new ArrayList<>();
        for (String k : List.of("app.db", "app.queue", "app.api")) {
            LoggerResource r = buildLoggerResource(k, null, true);
            r.getAttributes().setGroup("app");
            data.add(r);
        }
        when(mockLoggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new LoggerListResponse().data(data));
        when(mockLogGroupsApi.listLogGroups(isNull(), any(), any(), isNull()))
                .thenReturn(buildGroupListResponse("app", "WARN"));
        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        List<com.smplkit.logging.adapters.DiscoveredLogger> discovered = new ArrayList<>();
        for (String k : List.of("app.db", "app.queue", "app.api")) {
            discovered.add(new com.smplkit.logging.adapters.DiscoveredLogger(k, "INFO"));
        }
        when(adapter.discover()).thenReturn(discovered);
        client.registerAdapter(adapter);
        client.install();

        AtomicInteger globalCount = new AtomicInteger();
        AtomicInteger appGroupCount = new AtomicInteger();
        client.onChange(e -> globalCount.incrementAndGet());
        client.onChange("app", e -> appGroupCount.incrementAndGet());

        client.simulateGroupDeleted(Map.of("id", "app"));

        assertEquals(3, globalCount.get(),
                "Global fires once per dependent whose level moved to fallback");
        assertEquals(0, appGroupCount.get(),
                "No event fires for the deleted group key");
    }

    @Test
    void diag4_noOp_edit_firesNoListeners() throws ApiException {
        // logger_changed where the new payload leaves the resolved level unchanged.
        stubManagedLoggerStart("noop.logger", "INFO");
        client.registerAdapter(noopAdapter("noop.logger", "INFO"));
        client.install();

        AtomicInteger keyedCount = new AtomicInteger();
        AtomicInteger globalCount = new AtomicInteger();
        client.onChange("noop.logger", e -> keyedCount.incrementAndGet());
        client.onChange(e -> globalCount.incrementAndGet());
        keyedCount.set(0);
        globalCount.set(0);

        when(mockLoggersApi.getLogger("noop.logger"))
                .thenReturn(buildLoggerResponse("noop.logger", "INFO", true));
        client.simulateLoggerChanged(Map.of("id", "noop.logger"));

        assertEquals(0, keyedCount.get(), "Key-scoped listener does not fire on no-op");
        assertEquals(0, globalCount.get(), "Global listener does not fire on no-op");
    }
}
