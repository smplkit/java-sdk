package com.smplkit.logging;

import com.smplkit.LogLevel;
import com.smplkit.SharedWebSocket;
import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.errors.NotFoundError;
import com.smplkit.internal.Debug;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
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
import org.openapitools.jackson.nullable.JsonNullable;
import com.smplkit.logging.adapters.DiscoveredLogger;
import com.smplkit.logging.adapters.LoggingAdapter;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Client for the smplkit Logging service.
 *
 * <p>Provides logger and group management via {@link #management()} and runtime
 * level control ({@link #install()}, {@link #onChange}).</p>
 *
 * <p>Supports JUL, Logback, and Log4j2 via pluggable {@link LoggingAdapter} instances.</p>
 */
public final class LoggingClient {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("smplkit.logging");

    final LoggersApi loggersApi;
    final LogGroupsApi logGroupsApi;
    private final HttpClient httpClient;
    private final String apiKey;
    private volatile com.smplkit.MetricsReporter metrics;

    // Adapter state
    private final List<LoggingAdapter> adapters = new ArrayList<>();
    private boolean explicitAdapters = false;

    // Runtime state
    private volatile boolean started = false;
    private String environment;
    private String service;
    private final Map<String, String> nameMap = new ConcurrentHashMap<>();
    private Map<String, Map<String, Object>> loggersCache = new ConcurrentHashMap<>();
    private Map<String, Map<String, Object>> groupsCache = new ConcurrentHashMap<>();
    private final List<Consumer<LoggerChangeEvent>> globalListeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<LoggerChangeEvent>>> keyListeners = new ConcurrentHashMap<>();
    private volatile SharedWebSocket wsManager;

    // Post-startup registration buffer
    private final LoggerRegistrationBuffer loggerBuffer = new LoggerRegistrationBuffer();
    private final ScheduledExecutorService loggerFlushExecutor;
    private volatile ScheduledFuture<?> loggerFlushFuture;

    // WS event handlers (stored as fields so they can be unregistered)
    private final java.util.function.Consumer<java.util.Map<String, Object>> loggerChangedHandler =
            this::handleLoggerChanged;
    private final java.util.function.Consumer<java.util.Map<String, Object>> loggerDeletedHandler =
            this::handleLoggerDeleted;
    private final java.util.function.Consumer<java.util.Map<String, Object>> groupChangedHandler =
            this::handleGroupChanged;
    private final java.util.function.Consumer<java.util.Map<String, Object>> groupDeletedHandler =
            this::handleGroupDeleted;
    private final java.util.function.Consumer<java.util.Map<String, Object>> loggersChangedHandler =
            this::handleLoggersChanged;

    // Management accessor
    private final LoggingManagement management;

    /**
     * Creates a new LoggingClient.
     *
     * @param loggersApi   the generated Loggers API client
     * @param logGroupsApi the generated LogGroups API client
     * @param httpClient   shared HTTP client
     * @param apiKey       bearer token for authentication
     */
    public LoggingClient(LoggersApi loggersApi, LogGroupsApi logGroupsApi,
                         HttpClient httpClient, String apiKey) {
        this.loggersApi = loggersApi;
        this.logGroupsApi = logGroupsApi;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.management = new LoggingManagement(this);
        this.loggerFlushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smplkit-logger-flush");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // Management accessor
    // -----------------------------------------------------------------------

    /**
     * Returns the management-plane API for logger and log group CRUD operations.
     *
     * @return the {@link LoggingManagement} instance
     */
    public LoggingManagement management() {
        return management;
    }

    // -----------------------------------------------------------------------
    // Configuration (set by SmplClient before use)
    // -----------------------------------------------------------------------

    /** Sets the environment for level resolution. */
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /** Sets the service name. */
    public void setService(String service) {
        this.service = service;
    }

    /** Sets the metrics reporter. */
    public void setMetrics(com.smplkit.MetricsReporter metrics) {
        this.metrics = metrics;
    }

    /** Sets the shared WebSocket manager. Called by SmplClient before start(). */
    public void setSharedWs(SharedWebSocket ws) {
        this.wsManager = ws;
    }

    // -----------------------------------------------------------------------
    // Adapter registration
    // -----------------------------------------------------------------------

    /**
     * Registers a custom logging adapter. Must be called before {@link #install()}.
     *
     * <p>Registering an adapter disables automatic adapter detection.</p>
     *
     * @param adapter the adapter to register
     * @throws IllegalStateException if called after install()
     */
    public void registerAdapter(LoggingAdapter adapter) {
        if (started) {
            throw new IllegalStateException("Cannot register adapters after install()");
        }
        explicitAdapters = true;
        adapters.add(adapter);
    }

    // -----------------------------------------------------------------------
    // Internal: create / update (called by Logger.save() and LogGroup.save())
    // -----------------------------------------------------------------------

    /** Saves a logger on the server (upsert via PUT). Called by {@link Logger#save()}. */
    Logger _saveLogger(Logger lg) {
        try {
            LoggerRequest body = buildLoggerBody(lg.getId(), lg);
            LoggerResponse response = loggersApi.updateLogger(lg.getId(), body);
            return loggerResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Updates an existing logger on the server. Called by {@link Logger#save()}. */
    Logger _updateLogger(Logger lg) {
        return _saveLogger(lg);
    }

    /** Creates a new group on the server. Called by {@link LogGroup#save()}. */
    LogGroup _createGroup(LogGroup grp) {
        try {
            LogGroupRequest body = buildGroupBody(null, grp);
            LogGroupResponse response = logGroupsApi.createLogGroup(body);
            return logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Updates an existing group on the server. Called by {@link LogGroup#save()}. */
    LogGroup _updateGroup(LogGroup grp) {
        try {
            LogGroupRequest body = buildGroupBody(grp.getId(), grp);
            LogGroupResponse response = logGroupsApi.updateLogGroup(grp.getId(), body);
            return logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Runtime: install, onChange
    // -----------------------------------------------------------------------

    /**
     * Installs runtime logging control. Idempotent.
     *
     * <p>Mirrors Python's {@code client.logging.install()}: hooks the SDK into
     * the existing logging machinery — loads adapters, scans loggers, applies
     * the resolved levels from the server. There is no {@code stop()}; close
     * the parent {@link com.smplkit.SmplClient} to release adapters.</p>
     *
     * <p>Renamed from {@code start()} in the Python-PR-127 mirror to make the
     * "install adapters into your runtime" intent explicit.</p>
     */
    public void install() {
        if (started) {
            return;
        }

        // 1. Load adapters (auto or explicit)
        if (!explicitAdapters) {
            autoLoadAdapters();
        }

        // 2. Discover existing loggers — add each to the registration buffer
        int discoveredCount = 0;
        for (LoggingAdapter adapter : adapters) {
            try {
                List<DiscoveredLogger> discovered = adapter.discover();
                for (DiscoveredLogger dl : discovered) {
                    String normalized = normalizeKey(dl.name());
                    nameMap.put(dl.name(), normalized);
                    loggerBuffer.add(normalized, dl.level(), dl.resolvedLevel(), service, environment);
                    Debug.log("discovery", "discovered logger: " + normalized + " (level=" + dl.level() + ")");
                    discoveredCount++;
                }
            } catch (NoClassDefFoundError e) {
                Debug.log("lifecycle", "Skipped adapter " + adapter.name() + " discover() — dependency missing: " + e.getMessage());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Adapter " + adapter.name() + " discover() failed", e);
            }
        }
        Debug.log("lifecycle", "discovered " + discoveredCount + " loggers from adapters");
        if (metrics != null && discoveredCount > 0) {
            metrics.record("logging.loggers_discovered", discoveredCount, "loggers");
        }

        // 3. Install hooks for new logger detection
        for (LoggingAdapter adapter : adapters) {
            try {
                adapter.installHook(this::onNewLogger);
            } catch (NoClassDefFoundError e) {
                Debug.log("lifecycle", "Skipped adapter " + adapter.name() + " installHook() — dependency missing: " + e.getMessage());
            } catch (Exception e) {
                LOG.warning("Adapter " + adapter.name() + " installHook() failed: " + e.getMessage());
                Debug.log("lifecycle", "Adapter " + adapter.name() + " installHook() failed: " + e);
            }
        }
        Debug.log("registration", "installed hooks on " + adapters.size() + " adapters");

        // 4. Flush buffer — bulk-registers all discovered loggers with the server
        // (flushLoggerBuffer catches all exceptions internally)
        flushLoggerBuffer();
        Debug.log("registration", "initial registration flush complete");

        // 5. Fetch all loggers and groups, resolve and apply levels
        Debug.log("api", "fetching logger and group definitions");
        try {
            fetchAndApply("start");
        } catch (Exception e) {
            LOG.warning("Failed to fetch/apply logging levels during start: " + e.getMessage());
            Debug.log("resolution", "Failed to fetch/apply logging levels during start: " + e);
        }
        Debug.log("api", "fetched " + loggersCache.size() + " loggers and " + groupsCache.size() + " groups");

        // 6. Register WebSocket listeners for real-time updates
        if (wsManager != null) {
            wsManager.on("logger_changed", loggerChangedHandler);
            wsManager.on("logger_deleted", loggerDeletedHandler);
            wsManager.on("group_changed", groupChangedHandler);
            wsManager.on("group_deleted", groupDeletedHandler);
            wsManager.on("loggers_changed", loggersChangedHandler);
        }

        // 7. Start periodic flush for loggers discovered after start()
        loggerFlushFuture = loggerFlushExecutor.scheduleAtFixedRate(
                this::flushLoggerBufferSafe, 30, 30, TimeUnit.SECONDS);

        started = true;
    }

    /**
     * Register a global change listener that fires for any logger change.
     *
     * @param listener the callback
     */
    public void onChange(Consumer<LoggerChangeEvent> listener) {
        globalListeners.add(listener);
    }

    /**
     * Register an id-scoped change listener that fires only for the given logger id.
     *
     * @param id       the logger id to watch
     * @param listener the callback
     */
    public void onChange(String id, Consumer<LoggerChangeEvent> listener) {
        keyListeners.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Re-fetches logger and group definitions from the server, recomputes
     * resolved levels, applies them through the registered adapters, and fires
     * change listeners for any logger whose resolved level changed.
     *
     * <p>Mirrors Python's {@code client.logging.refresh()}. The runtime path is
     * normally driven by WebSocket events (registered in {@link #install()});
     * call this to force a re-pull when you've made server-side changes through
     * a different process and want them reflected immediately.</p>
     *
     * <p>No-op if {@link #install()} has not been called.</p>
     */
    public void refresh() {
        if (!started) return;
        Map<String, String> preLevels = snapshotLevels();
        try {
            fetchOnly();
        } catch (Exception e) {
            LOG.warning("Failed to fetch levels during refresh: " + e.getMessage());
            Debug.log("resolution", "refresh fetch failed: " + e);
            return;
        }
        diffAndFireLevels(preLevels, "manual");
    }

    /** Returns whether {@link #install()} has been called. */
    public boolean isInstalled() {
        return started;
    }

    /** Returns the list of loaded adapters. */
    public List<LoggingAdapter> getAdapters() {
        return adapters;
    }

    // -----------------------------------------------------------------------
    // Key normalization (ADR-034 section 5)
    // -----------------------------------------------------------------------

    /** Normalizes a logger name to a canonical key form. */
    static String normalizeKey(String name) {
        return name.replace("/", ".").replace(":", ".").toLowerCase();
    }

    // -----------------------------------------------------------------------
    // Adapter auto-loading
    // -----------------------------------------------------------------------

    private void autoLoadAdapters() {
        loadAdaptersFromProviders(ServiceLoader.load(LoggingAdapter.class).stream().collect(java.util.stream.Collectors.toList()));
    }

    /** Package-private for testing — production code always calls autoLoadAdapters(). */
    void loadAdaptersFromProviders(Iterable<ServiceLoader.Provider<LoggingAdapter>> providers) {
        for (ServiceLoader.Provider<LoggingAdapter> provider : providers) {
            try {
                LoggingAdapter adapter = provider.get();
                adapters.add(adapter);
                Debug.log("lifecycle", "Loaded logging adapter: " + adapter.name());
            } catch (ServiceConfigurationError | NoClassDefFoundError e) {
                Debug.log("lifecycle", "Skipped logging adapter (dependency not on classpath): " + e.getMessage());
            } catch (Exception e) {
                LOG.warning("Failed to load logging adapter: " + e.getMessage());
            }
        }
        if (adapters.isEmpty()) {
            LOG.warning("No logging framework detected. Runtime logging control requires a supported framework.");
        }
    }

    // -----------------------------------------------------------------------
    // New logger callback (for adapter hooks)
    // -----------------------------------------------------------------------

    private void onNewLogger(String originalName, String level) {
        String normalized = normalizeKey(originalName);
        Debug.log("discovery", "new logger from hook: " + normalized + " (level=" + level + ")");
        nameMap.put(originalName, normalized);
        loggerBuffer.add(normalized, level, level, service, environment);

        if (loggerBuffer.pendingCount() >= 50) {
            Thread t = new Thread(this::flushLoggerBufferSafe, "smplkit-logger-flush-eager");
            t.setDaemon(true);
            t.start();
        }

        // If already started, apply managed level from cache immediately
        if (started) {
            Map<String, Object> entry = loggersCache.get(normalized);
            if (entry != null && Boolean.TRUE.equals(entry.get("managed"))) {
                String resolved = Resolution.resolveLevel(normalized, environment, loggersCache, groupsCache);
                for (LoggingAdapter adapter : adapters) {
                    try {
                        adapter.applyLevel(originalName, resolved);
                    } catch (Exception e) {
                        // ignore adapter errors
                    }
                }
            }
        }
    }

    private void handleLoggerChanged(Map<String, Object> data) {
        if (!started) return;
        String loggerKey = data.get("id") instanceof String s ? s : null;
        if (loggerKey == null) {
            Debug.log("websocket", "logger_changed event missing id, skipping");
            return;
        }
        Debug.log("websocket", "logger_changed event received, key=" + loggerKey);

        // Snapshot pre-levels across ALL tracked loggers so dot-descendants
        // (which inherit from this key when they have no own level/group) get
        // re-resolved and their listeners fire on resolved-level deltas.
        Map<String, String> preLevels = snapshotLevels();

        // Scoped fetch: GET /loggers/{key}
        try {
            LoggerResponse resp = loggersApi.getLogger(loggerKey);
            Logger lg = loggerResponseToModel(resp);
            var attrs = resp.getData().getAttributes();
            String gid = lg.getId() != null ? lg.getId() : "";
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", gid);
            entry.put("level", attrs.getLevel() != null ? attrs.getLevel().getValue() : null);
            entry.put("group", attrs.getGroup());
            entry.put("managed", attrs.getManaged());
            entry.put("environments", attrs.getEnvironments() != null
                    ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
            loggersCache.put(loggerKey, entry);
        } catch (ApiException e) {
            LOG.warning("Failed scoped fetch for logger key=" + loggerKey + ": " + e.getMessage());
            Debug.log("websocket", "logger_changed scoped fetch failed for key=" + loggerKey + ": " + e);
            return;
        }

        // Diff-based re-apply: pushes new levels to adapters and fires
        // key-scoped listeners for every tracked logger whose resolved level
        // changed — including dot-descendants of the changed key.
        diffAndFireLevels(preLevels, "websocket");
    }

    private void handleLoggerDeleted(Map<String, Object> data) {
        if (!started) return;
        String loggerKey = data.get("id") instanceof String s ? s : null;
        if (loggerKey == null) {
            Debug.log("websocket", "logger_deleted event missing id, skipping");
            return;
        }
        Debug.log("websocket", "logger_deleted event received, key=" + loggerKey);

        // Deletion is not a level change. Snapshot resolved levels, evict
        // the logger from cache, then re-apply for every tracked logger
        // whose effective level moved — EXCEPT the deleted key itself.
        // We deliberately stop pushing levels to a key the platform no
        // longer manages: per the listener contract, deletion fires no
        // listener for the deleted logger.
        Map<String, String> preLevels = snapshotLevels();
        loggersCache.remove(loggerKey);
        diffAndFireLevels(preLevels, "websocket", java.util.Set.of(loggerKey));
    }

    private void handleGroupChanged(Map<String, Object> data) {
        if (!started) return;
        String groupKey = data.get("id") instanceof String s ? s : null;
        if (groupKey == null) {
            Debug.log("websocket", "group_changed event missing id, skipping");
            return;
        }
        Debug.log("websocket", "group_changed event received, key=" + groupKey);

        // Snapshot pre-levels for all loggers in this group
        Map<String, String> preLevels = snapshotLevels();

        // Scoped fetch: GET /log_groups/{key}
        try {
            LogGroupResponse resp = logGroupsApi.getLogGroup(groupKey);
            var attrs = resp.getData().getAttributes();
            String gid = resp.getData().getId() != null ? resp.getData().getId() : "";
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", gid);
            entry.put("level", attrs.getLevel() != null ? attrs.getLevel().getValue() : null);
            entry.put("group", attrs.getParentId());
            entry.put("environments", attrs.getEnvironments() != null
                    ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
            groupsCache.put(groupKey, entry);
        } catch (ApiException e) {
            LOG.warning("Failed scoped fetch for group key=" + groupKey + ": " + e.getMessage());
            Debug.log("websocket", "group_changed scoped fetch failed for key=" + groupKey + ": " + e);
            return;
        }

        // Diff and fire for loggers whose resolved level changed
        diffAndFireLevels(preLevels, "websocket");
    }

    private void handleGroupDeleted(Map<String, Object> data) {
        if (!started) return;
        String groupKey = data.get("id") instanceof String s ? s : null;
        if (groupKey == null) {
            Debug.log("websocket", "group_deleted event missing id, skipping");
            return;
        }
        Debug.log("websocket", "group_deleted event received, key=" + groupKey);

        // Deletion is not a level change. Snapshot resolved levels, evict the
        // group from cache, then re-apply for every tracked logger whose
        // effective level moved (typically loggers whose resolution chain ran
        // through this group and now fall through to a parent group, a
        // dot-ancestor, or INFO fallback). No synthetic event fires for the
        // deleted group key itself.
        Map<String, String> preLevels = snapshotLevels();
        groupsCache.remove(groupKey);
        diffAndFireLevels(preLevels, "websocket");
    }

    private void handleLoggersChanged(Map<String, Object> data) {
        if (!started) return;
        Debug.log("websocket", "loggers_changed event received");

        // Snapshot pre-levels
        Map<String, String> preLevels = snapshotLevels();

        // Full refetch of both loggers AND log_groups (without firing listeners)
        try {
            fetchOnly();
        } catch (Exception e) {
            LOG.warning("Failed to re-fetch levels after loggers_changed event: " + e.getMessage());
            Debug.log("websocket", "loggers_changed fetch failed: " + e);
            return;
        }

        // Diff-based firing
        diffAndFireLevels(preLevels, "websocket");
    }

    /** Snapshots current resolved levels for all tracked loggers. */
    private Map<String, String> snapshotLevels() {
        Map<String, String> snapshot = new HashMap<>();
        for (String key : nameMap.values()) {
            snapshot.put(key, Resolution.resolveLevel(key, environment, loggersCache, groupsCache));
        }
        return snapshot;
    }

    /**
     * Diffs pre vs post resolved levels and applies the new level (plus fires
     * listeners) for each tracked logger whose level moved. Each apply goes
     * through {@link #applyLevelForKey}, which fires the key-scoped listener
     * AND every global listener exactly once for that key — so a single
     * trigger that re-resolves N loggers produces N global invocations, not
     * one summary event.
     */
    private void diffAndFireLevels(Map<String, String> preLevels, String source) {
        diffAndFireLevels(preLevels, source, java.util.Set.of());
    }

    /**
     * Variant that skips a set of keys — used by deletion handlers so the
     * just-deleted key fires no listener even if its post-deletion fallback
     * resolution differs from its pre-deletion own level.
     */
    private void diffAndFireLevels(Map<String, String> preLevels, String source,
                                   java.util.Set<String> skipKeys) {
        for (String normalizedKey : nameMap.values()) {
            if (skipKeys.contains(normalizedKey)) continue;
            String preLevel = preLevels.get(normalizedKey);
            String postLevel = Resolution.resolveLevel(normalizedKey, environment, loggersCache, groupsCache);
            if (!java.util.Objects.equals(preLevel, postLevel)) {
                applyLevelForKey(normalizedKey, postLevel, source);
            }
        }
    }

    /**
     * Applies a resolved level to every adapter for the given key and fires
     * the resulting {@link LoggerChangeEvent} to the key-scoped listeners for
     * that key AND every global listener.
     */
    private void applyLevelForKey(String normalizedKey, String level, String source) {
        LogLevel logLevel = tryParseLogLevel(level, normalizedKey);
        if (logLevel == null) return;
        for (Map.Entry<String, String> mapping : nameMap.entrySet()) {
            if (!normalizedKey.equals(mapping.getValue())) continue;
            String originalName = mapping.getKey();
            for (LoggingAdapter adapter : adapters) {
                try {
                    adapter.applyLevel(originalName, level);
                } catch (Exception e) {
                    Debug.log("adapter", "Adapter " + adapter.name()
                            + " applyLevel failed for " + originalName + ": " + e);
                }
            }
            if (metrics != null) {
                metrics.record("logging.level_changes", "changes",
                        java.util.Map.of("logger", normalizedKey));
            }
            LoggerChangeEvent event = new LoggerChangeEvent(normalizedKey, logLevel, source);
            fireChangeListeners(normalizedKey, event);
        }
    }

    /** Parse a resolved level string into a typed LogLevel, returning null if
     * the string isn't one of the known enum values. Generated logger / log-group
     * model bindings reject unknown values during deserialization, so production
     * traffic never reaches this fallback, but defensive guarding lets a stray
     * cache injection (test harnesses, hot-patched memory) degrade gracefully
     * instead of throwing out of the apply loop.
     */
    LogLevel tryParseLogLevel(String level, String contextKey) {
        try {
            return LogLevel.valueOf(level);
        } catch (IllegalArgumentException e) {
            Debug.log("resolution", "Unknown level '" + level + "' for logger '" + contextKey + "'");
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Internal: fetch, resolve, apply
    // -----------------------------------------------------------------------

    /**
     * Drains the registration buffer and sends all pending loggers to the server via
     * the bulk-register endpoint. No-op if the buffer is empty.
     *
     * <p>Package-private so the management facade {@link LoggersClient#flush()}
     * can call it; not part of the public {@link LoggingClient} surface.</p>
     */
    void flushLoggerBuffer() {
        List<LoggerRegistrationEntry> batch = loggerBuffer.drain();
        if (batch.isEmpty()) return;

        LoggerBulkRequest req = new LoggerBulkRequest();
        for (LoggerRegistrationEntry entry : batch) {
            LoggerBulkItem item = new LoggerBulkItem();
            item.setId(entry.id());

            // level: only set when explicitly configured on the logger
            if (entry.level() != null) {
                item.setLevel_JsonNullable(JsonNullable.of(entry.level()));
            }
            // resolved_level: always set — effective level after framework inheritance
            item.setResolvedLevel(entry.resolvedLevel());

            if (entry.service() != null) item.setService(entry.service());
            if (entry.environment() != null) item.setEnvironment(entry.environment());

            req.addLoggersItem(item);
        }

        try {
            loggersApi.bulkRegisterLoggers(req);
            Debug.log("registration", "bulk-registered " + batch.size() + " logger(s)");
        } catch (Exception e) {
            LOG.warning("Bulk logger registration failed: " + e.getMessage());
            Debug.log("registration", "Bulk logger registration failed: " + e);
        }
    }

    private void flushLoggerBufferSafe() {
        flushLoggerBuffer(); // all exceptions handled internally
    }

    private static final int RUNTIME_PAGE_SIZE = 1000;

    @SuppressWarnings("unchecked")
    private void fetchAndApply(String source) {
        Map<String, Map<String, Object>> loggersData = fetchAllLoggers();
        Map<String, Map<String, Object>> groupsData = fetchAllGroups();

        this.loggersCache = new ConcurrentHashMap<>(loggersData);
        this.groupsCache = new ConcurrentHashMap<>(groupsData);

        // Resolve and apply levels
        applyLevels(source);
    }

    /** Fetches loggers and groups and updates the caches without firing any listeners. */
    @SuppressWarnings("unchecked")
    private void fetchOnly() {
        Map<String, Map<String, Object>> loggersData = fetchAllLoggers();
        Map<String, Map<String, Object>> groupsData = fetchAllGroups();

        this.loggersCache = new ConcurrentHashMap<>(loggersData);
        this.groupsCache = new ConcurrentHashMap<>(groupsData);
    }

    private Map<String, Map<String, Object>> fetchAllLoggers() {
        Map<String, Map<String, Object>> loggersData = new HashMap<>();
        int page = 1;
        try {
            while (true) {
                LoggerListResponse loggerResp = loggersApi.listLoggers(
                        null, null, null, null, page, RUNTIME_PAGE_SIZE, null);
                List<LoggerResource> rows = loggerResp.getData() != null
                        ? loggerResp.getData() : List.of();
                for (LoggerResource r : rows) {
                    var attrs = r.getAttributes();
                    String id = r.getId() != null ? r.getId() : "";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", id);
                    entry.put("level", attrs.getLevel() != null ? attrs.getLevel().getValue() : null);
                    entry.put("group", attrs.getGroup());
                    entry.put("managed", attrs.getManaged());
                    entry.put("environments", attrs.getEnvironments() != null
                            ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                    loggersData.put(id, entry);
                }
                if (rows.size() < RUNTIME_PAGE_SIZE) break;
                page++;
            }
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
        return loggersData;
    }

    private Map<String, Map<String, Object>> fetchAllGroups() {
        Map<String, Map<String, Object>> groupsData = new HashMap<>();
        int page = 1;
        try {
            while (true) {
                LogGroupListResponse groupResp = logGroupsApi.listLogGroups(
                        null, page, RUNTIME_PAGE_SIZE, null);
                List<LogGroupResource> rows = groupResp.getData() != null
                        ? groupResp.getData() : List.of();
                for (LogGroupResource r : rows) {
                    var attrs = r.getAttributes();
                    String gid = r.getId() != null ? r.getId() : "";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", gid);
                    entry.put("level", attrs.getLevel() != null ? attrs.getLevel().getValue() : null);
                    entry.put("group", attrs.getParentId());
                    entry.put("environments", attrs.getEnvironments() != null
                            ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                    groupsData.put(gid, entry);
                }
                if (rows.size() < RUNTIME_PAGE_SIZE) break;
                page++;
            }
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
        return groupsData;
    }

    @SuppressWarnings("unchecked")
    private void applyLevels(String source) {
        for (Map.Entry<String, String> mapping : nameMap.entrySet()) {
            String originalName = mapping.getKey();
            String normalizedKey = mapping.getValue();
            Map<String, Object> entry = loggersCache.get(normalizedKey);
            if (entry == null) continue;
            Boolean managed = (Boolean) entry.get("managed");
            if (managed == null || !managed) continue;

            String resolved = Resolution.resolveLevel(normalizedKey, environment, loggersCache, groupsCache);
            Debug.log("resolution", "resolved " + normalizedKey + " → " + resolved);
            LogLevel logLevel = tryParseLogLevel(resolved, normalizedKey);
            if (logLevel == null) continue;

            // Apply through all adapters
            for (LoggingAdapter adapter : adapters) {
                try {
                    adapter.applyLevel(originalName, resolved);
                    Debug.log("adapter", "applied level " + resolved + " to logger " + originalName);
                } catch (Exception e) {
                    Debug.log("adapter", "Adapter " + adapter.name()
                            + " applyLevel failed for " + originalName + ": " + e);
                }
            }

            if (metrics != null) {
                metrics.record("logging.level_changes", "changes",
                        java.util.Map.of("logger", normalizedKey));
            }

            // Fire change listeners
            LoggerChangeEvent event = new LoggerChangeEvent(normalizedKey, logLevel, source);
            fireChangeListeners(normalizedKey, event);
        }
    }

    private void fireChangeListeners(String key, LoggerChangeEvent event) {
        for (Consumer<LoggerChangeEvent> listener : globalListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Global change listener threw exception", e);
            }
        }
        List<Consumer<LoggerChangeEvent>> scoped = keyListeners.get(key);
        if (scoped != null) {
            for (Consumer<LoggerChangeEvent> listener : scoped) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Key-scoped change listener threw exception", e);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    /** Releases resources and removes logging hooks. */
    public void close() {
        Debug.log("lifecycle", "LoggingClient.close() called");
        if (loggerFlushFuture != null) {
            loggerFlushFuture.cancel(false);
            loggerFlushFuture = null;
        }
        loggerFlushExecutor.shutdownNow();
        if (wsManager != null) {
            wsManager.off("logger_changed", loggerChangedHandler);
            wsManager.off("logger_deleted", loggerDeletedHandler);
            wsManager.off("group_changed", groupChangedHandler);
            wsManager.off("group_deleted", groupDeletedHandler);
            wsManager.off("loggers_changed", loggersChangedHandler);
        }
        for (LoggingAdapter adapter : adapters) {
            try {
                adapter.uninstallHook();
            } catch (Exception e) {
                Debug.log("lifecycle", "Adapter " + adapter.name() + " uninstallHook() failed: " + e);
            }
        }
        started = false;
    }

    // -----------------------------------------------------------------------
    // Model conversion (package-private for LoggingManagement)
    // -----------------------------------------------------------------------

    Logger loggerResponseToModel(LoggerResponse response) {
        return loggerResourceToModel(response.getData());
    }

    Logger loggerResourceToModel(LoggerResource resource) {
        var attrs = resource.getAttributes();
        return new Logger(
                this,
                resource.getId() != null ? resource.getId() : null,
                attrs.getName(),
                attrs.getLevel() != null ? attrs.getLevel().getValue() : null,
                attrs.getGroup(),
                attrs.getManaged() != null ? attrs.getManaged() : false,
                attrs.getSources() != null ? new ArrayList<>(attrs.getSources()) : new ArrayList<>(),
                attrs.getEnvironments() != null ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>(),
                toInstant(attrs.getCreatedAt()),
                toInstant(attrs.getUpdatedAt())
        );
    }

    LogGroup logGroupResponseToModel(LogGroupResponse response) {
        return logGroupResourceToModel(response.getData());
    }

    LogGroup logGroupResourceToModel(LogGroupResource resource) {
        var attrs = resource.getAttributes();
        return new LogGroup(
                this,
                resource.getId() != null ? resource.getId() : null,
                attrs.getName(),
                attrs.getLevel() != null ? attrs.getLevel().getValue() : null,
                attrs.getParentId(),
                attrs.getEnvironments() != null ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>(),
                toInstant(attrs.getCreatedAt()),
                toInstant(attrs.getUpdatedAt())
        );
    }

    // -----------------------------------------------------------------------
    // Request body building
    // -----------------------------------------------------------------------

    private LoggerRequest buildLoggerBody(String loggerId, Logger lg) {
        var attrs = new com.smplkit.internal.generated.logging.model.Logger();
        attrs.setName(lg.getName());
        if (lg.getLevel() != null) {
            attrs.setLevel(com.smplkit.internal.generated.logging.model.Logger.LevelEnum.fromValue(lg.getLevel()));
        }
        if (lg.getGroup() != null) attrs.setGroup(lg.getGroup());
        attrs.setManaged(lg.isManaged());
        if (lg.getEnvironments() != null && !lg.getEnvironments().isEmpty()) {
            attrs.setEnvironments(new HashMap<>(lg.getEnvironments()));
        }

        LoggerResource data = new LoggerResource();
        data.setType(LoggerResource.TypeEnum.LOGGER);
        data.setAttributes(attrs);
        data.setId(loggerId != null ? loggerId : lg.getId());

        LoggerRequest body = new LoggerRequest();
        body.setData(data);
        return body;
    }

    private LogGroupRequest buildGroupBody(String groupId, LogGroup grp) {
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup();
        attrs.setName(grp.getName());
        if (grp.getLevel() != null) {
            attrs.setLevel(com.smplkit.internal.generated.logging.model.LogGroup.LevelEnum.fromValue(grp.getLevel()));
        }
        if (grp.getGroup() != null) attrs.setParentId(grp.getGroup());
        if (grp.getEnvironments() != null && !grp.getEnvironments().isEmpty()) {
            attrs.setEnvironments(new HashMap<>(grp.getEnvironments()));
        }

        LogGroupResource data = new LogGroupResource();
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);
        data.setId(groupId != null ? groupId : grp.getId());

        LogGroupRequest body = new LogGroupRequest();
        body.setData(data);
        return body;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }

    static RuntimeException mapLoggingException(ApiException e) {
        if (e.getCode() == 0) {
            return ApiExceptionHandler.mapApiException(e);
        }
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }

    // -----------------------------------------------------------------------
    // Package-private test helpers
    // -----------------------------------------------------------------------

    /** Simulates a logger_changed WebSocket event (for testing). */
    void simulateLoggerChanged(Map<String, Object> data) {
        handleLoggerChanged(data);
    }

    /** Simulates a logger_deleted WebSocket event (for testing). */
    void simulateLoggerDeleted(Map<String, Object> data) {
        handleLoggerDeleted(data);
    }

    /** Simulates a group_changed WebSocket event (for testing). */
    void simulateGroupChanged(Map<String, Object> data) {
        handleGroupChanged(data);
    }

    /** Simulates a group_deleted WebSocket event (for testing). */
    void simulateGroupDeleted(Map<String, Object> data) {
        handleGroupDeleted(data);
    }

    /** Simulates a loggers_changed WebSocket event (for testing). */
    void simulateLoggersChanged(Map<String, Object> data) {
        handleLoggersChanged(data);
    }

    /** Simulates a new logger detected by adapter hook (for testing). */
    void simulateNewLogger(String name, String level) {
        onNewLogger(name, level);
    }

    /** Returns the number of loggers pending registration in the buffer (for testing). */
    int getLoggerBufferPendingCount() {
        return loggerBuffer.pendingCount();
    }

    /** Returns true if the periodic flush future is active (for testing). */
    boolean isFlushScheduled() {
        ScheduledFuture<?> f = loggerFlushFuture;
        return f != null && !f.isDone();
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    private record LoggerRegistrationEntry(
            String id, String level, String resolvedLevel, String service, String environment) {}

    private static final class LoggerRegistrationBuffer {
        private final Set<String> seen = new HashSet<>();
        private final List<LoggerRegistrationEntry> pending = new ArrayList<>();
        private final Object lock = new Object();

        void add(String id, String level, String resolvedLevel, String service, String environment) {
            synchronized (lock) {
                if (seen.add(id)) {
                    pending.add(new LoggerRegistrationEntry(id, level, resolvedLevel, service, environment));
                }
            }
        }

        List<LoggerRegistrationEntry> drain() {
            synchronized (lock) {
                if (pending.isEmpty()) return List.of();
                List<LoggerRegistrationEntry> batch = new ArrayList<>(pending);
                pending.clear();
                return batch;
            }
        }

        int pendingCount() {
            synchronized (lock) { return pending.size(); }
        }
    }
}
