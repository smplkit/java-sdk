package com.smplkit.logging;

import com.smplkit.LogLevel;
import com.smplkit.SharedWebSocket;
import com.smplkit.internal.ApiExceptionHandler;
import com.smplkit.errors.NotInstalledError;
import com.smplkit.internal.Debug;
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

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The Smpl Logging client (sync).
 *
 * <p>Smpl Logging has two surfaces on a single client, mirroring how the config,
 * flags, audit, and jobs clients expose their full surface from one class:</p>
 *
 * <ul>
 *   <li><strong>CRUD surface</strong> — works without {@link #install}. Two sub-clients:
 *   <ul>
 *     <li>{@code client.logging.loggers} — logger CRUD + discovery: {@code new_} /
 *       {@code list} / {@code get} / {@code delete} plus {@code register} /
 *       {@code flush} / {@code pendingCount}.</li>
 *     <li>{@code client.logging.logGroups} — log-group CRUD: {@code new_} /
 *       {@code list} / {@code get} / {@code delete}.</li>
 *   </ul>
 *   The fused client owns the logger-discovery buffer directly; the {@code loggers}
 *   sub-client shares that same buffer so discovery and explicit registration
 *   drain through one queue.</li>
 *   <li><strong>Live surface</strong> — directly on the client. {@link #registerAdapter}
 *   is a PRE-install configuration call (allowed before {@link #install}).
 *   {@link #install} opens the live connection (hooks into the application's
 *   logging framework via the registered adapters, discovers loggers, fetches +
 *   applies levels, opens the shared WebSocket). {@code onChange} / {@code refresh} require {@link #install} first;
 *   calling them earlier raises {@link NotInstalledError}.</li>
 * </ul>
 *
 * <p>One client exposes the full surface, reachable as {@code client.logging}
 * ({@link com.smplkit.SmplClient}) or constructed directly:</p>
 *
 * <pre>{@code
 * try (LoggingClient logging = LoggingClient.builder()
 *         .environment("production").build()) {
 *     logging.loggers.new_("sqlalchemy.engine").save();
 *     logging.install();
 * }
 * }</pre>
 *
 * <p>The client supports two construction shapes:</p>
 *
 * <ul>
 *   <li><strong>Wired</strong> into {@link com.smplkit.SmplClient} — borrows the
 *   parent's logging transport for both runtime fetch and CRUD and the parent's
 *   shared WebSocket for the live channel. This is the common path.</li>
 *   <li><strong>Standalone</strong> — {@code LoggingClient.builder()...build()}
 *   builds and owns its own logging transport, and on {@link #install} opens and
 *   owns its own WebSocket (the WebSocket gateway lives on the app service).
 *   {@link #close()} tears down only the owned transport and owned WebSocket.</li>
 * </ul>
 */
public final class LoggingClient implements AutoCloseable {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("smplkit.logging");

    private static final String NOT_INSTALLED_MESSAGE =
            "Smpl Logging live operations require install() first — this opens a live "
            + "connection to your running service and hooks into your application's "
            + "logging framework. Call client.logging.install() before "
            + "onChange()/refresh().";

    private static final int RUNTIME_PAGE_SIZE = 1000;

    /** Logger CRUD + discovery ({@code client.logging.loggers}). */
    public final LoggersClient loggers;
    /** Log-group CRUD ({@code client.logging.logGroups}). */
    public final LogGroupsClient logGroups;

    final LoggersApi loggersApi;
    final LogGroupsApi logGroupsApi;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String appBaseUrl;
    private final boolean ownsTransport;
    private volatile com.smplkit.MetricsReporter metrics;

    // Discovery buffer is owned by this client; the loggers sub-client
    // shares it so discovery and explicit registration drain together.
    private final LoggerRegistrationBuffer buffer = new LoggerRegistrationBuffer();

    // Adapter state
    private final List<LoggingAdapter> adapters = new ArrayList<>();
    private boolean explicitAdapters = false;

    // Live-surface state
    private volatile boolean connected = false;
    private String environment;
    private String service;
    private final Map<String, String> nameMap = new ConcurrentHashMap<>();
    private Map<String, Map<String, Object>> loggersCache = new ConcurrentHashMap<>();
    private Map<String, Map<String, Object>> groupsCache = new ConcurrentHashMap<>();
    private final List<Consumer<LoggerChangeEvent>> globalListeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<LoggerChangeEvent>>> keyListeners = new ConcurrentHashMap<>();
    private volatile SharedWebSocket wsManager;
    private boolean ownsWs = false;

    // WS event handlers (stored as fields so they can be unregistered)
    private final Consumer<Map<String, Object>> loggerChangedHandler = this::handleLoggerChanged;
    private final Consumer<Map<String, Object>> loggerDeletedHandler = this::handleLoggerDeleted;
    private final Consumer<Map<String, Object>> groupChangedHandler = this::handleGroupChanged;
    private final Consumer<Map<String, Object>> groupDeletedHandler = this::handleGroupDeleted;
    private final Consumer<Map<String, Object>> loggersChangedHandler = this::handleLoggersChanged;

    /**
     * Wired constructor — called by {@link com.smplkit.SmplClient}.
     *
     * <p><strong>Exact signature:</strong>
     * {@code LoggingClient(LoggersApi loggersApi, LogGroupsApi logGroupsApi,
     * java.net.http.HttpClient httpClient, String apiKey)}.</p>
     *
     * <p>SmplClient builds the generated logging {@code ApiClient} via the
     * {@code HttpClients.compositeInterceptor} transport idiom, derives the two
     * generated {@code *Api} instances, and passes the parent's shared
     * {@link HttpClient} (used by the parent {@link SharedWebSocket}) plus the
     * api key. After construction SmplClient calls {@link #setEnvironment},
     * {@link #setService}, {@link #setMetrics}, and {@link #setSharedWs} to wire
     * the runtime context. A wired client borrows the parent's transport and
     * WebSocket and closes neither.</p>
     *
     * @param loggersApi   the generated Loggers API client
     * @param logGroupsApi the generated LogGroups API client
     * @param httpClient   shared HTTP client (for the parent's WebSocket)
     * @param apiKey       bearer token for authentication
     */
    public LoggingClient(LoggersApi loggersApi, LogGroupsApi logGroupsApi,
                         HttpClient httpClient, String apiKey) {
        this(loggersApi, logGroupsApi, httpClient, apiKey, null, false);
    }

    private LoggingClient(LoggersApi loggersApi, LogGroupsApi logGroupsApi,
                          HttpClient httpClient, String apiKey,
                          String appBaseUrl, boolean ownsTransport) {
        this.loggersApi = loggersApi;
        this.logGroupsApi = logGroupsApi;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.appBaseUrl = appBaseUrl;
        this.ownsTransport = ownsTransport;
        this.loggers = new LoggersClient(loggersApi, buffer);
        this.logGroups = new LogGroupsClient(logGroupsApi);
    }

    /**
     * Internal: build a standalone client owning a freshly-built logging transport.
     *
     * @param loggersApi   the standalone Loggers API
     * @param logGroupsApi the standalone LogGroups API
     * @param httpClient   the standalone HTTP client used to open the owned WebSocket
     * @param apiKey       the resolved api key
     * @param appBaseUrl   the app-service base URL (the WebSocket gateway lives on app)
     */
    static LoggingClient standalone(LoggersApi loggersApi, LogGroupsApi logGroupsApi,
                                    HttpClient httpClient, String apiKey, String appBaseUrl) {
        return new LoggingClient(loggersApi, logGroupsApi, httpClient, apiKey, appBaseUrl, true);
    }

    // -----------------------------------------------------------------------
    // Standalone construction
    // -----------------------------------------------------------------------

    /**
     * Construct a standalone {@link LoggingClient} resolving credentials from the
     * standard sources (env vars, {@code ~/.smplkit}). Owns its own logging transport.
     */
    public static LoggingClient create() {
        return builder().build();
    }

    /** Construct a standalone {@link LoggingClient} with the given API key. */
    public static LoggingClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** Returns a builder for a standalone {@link LoggingClient}. */
    public static LoggingClientBuilder builder() {
        return new LoggingClientBuilder();
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

    /** Sets the shared WebSocket manager. Called by SmplClient before install(). */
    public void setSharedWs(SharedWebSocket ws) {
        this.wsManager = ws;
    }

    private volatile Runnable ensureStartedHook;

    /** Sets the parent's deferred-start hook, run once when the live surface first connects (wired path). */
    public void setEnsureStarted(Runnable hook) {
        this.ensureStartedHook = hook;
    }

    // -----------------------------------------------------------------------
    // Adapter registration (pre-install, ungated)
    // -----------------------------------------------------------------------

    /**
     * Register a logging adapter. Must be called before install().
     *
     * <p>If called at least once, auto-loading is disabled — only explicitly
     * registered adapters are used. This is a pre-install configuration
     * call: it is intentionally NOT gated by {@link #install}.</p>
     *
     * @param adapter the adapter to register
     * @throws IllegalStateException if called after install()
     */
    public void registerAdapter(LoggingAdapter adapter) {
        if (connected) {
            throw new IllegalStateException("Cannot register adapters after install()");
        }
        explicitAdapters = true;
        adapters.add(adapter);
    }

    // -----------------------------------------------------------------------
    // Live surface: install (gate) + WebSocket helpers
    // -----------------------------------------------------------------------

    private void requireInstalled() {
        if (!connected) {
            throw new NotInstalledError(NOT_INSTALLED_MESSAGE);
        }
    }

    /** Return the shared WebSocket — the parent's when wired, else our own. */
    private SharedWebSocket ensureWs() {
        if (wsManager == null && ownsTransport && appBaseUrl != null && httpClient != null) {
            wsManager = new SharedWebSocket(httpClient, appBaseUrl, apiKey, metrics);
            wsManager.start();
            ownsWs = true;
        }
        return wsManager;
    }

    /**
     * Hook smplkit into the application's logging machinery.
     *
     * <p>Loads adapters, scans existing loggers, applies levels from the
     * smplkit server, and wires WebSocket handlers for live updates. This
     * IS the explicit consent gate — {@link #onChange} / {@link #refresh}
     * require it first.</p>
     *
     * <p>Idempotent — safe to call multiple times.</p>
     */
    public void install() {
        Debug.log("lifecycle", "LoggingClient.install() called");
        Runnable h = this.ensureStartedHook;
        if (h != null) {
            h.run();
        }
        if (connected) {
            return;
        }

        // 0. Load adapters
        if (!explicitAdapters && adapters.isEmpty()) {
            autoLoadAdapters();
        }

        // 1. Discover existing loggers from all adapters
        for (LoggingAdapter adapter : adapters) {
            try {
                List<DiscoveredLogger> existing = adapter.discover();
                Debug.log("discovery", "adapter '" + adapter.name() + "' discovered "
                        + existing.size() + " existing loggers");
                for (DiscoveredLogger dl : existing) {
                    String normalized = normalizeKey(dl.name());
                    nameMap.put(dl.name(), normalized);
                    loggers.register(loggerSourceFor(dl.name(), dl.level(), dl.resolvedLevel()));
                }
            } catch (NoClassDefFoundError e) {
                Debug.log("lifecycle", "Skipped adapter " + adapter.name()
                        + " discover() — dependency missing: " + e.getMessage());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Adapter " + adapter.name() + " discover() failed", e);
            }
        }

        // 2. Install continuous discovery hooks
        for (LoggingAdapter adapter : adapters) {
            try {
                adapter.installHook(this::onNewLogger);
            } catch (NoClassDefFoundError e) {
                Debug.log("lifecycle", "Skipped adapter " + adapter.name()
                        + " installHook() — dependency missing: " + e.getMessage());
            } catch (Exception e) {
                LOG.warning("Adapter " + adapter.name() + " install_hook() failed: " + e.getMessage());
            }
        }

        // 3. Flush initial batch
        try {
            loggers.flush();
        } catch (Exception exc) {
            LOG.warning("Bulk logger registration failed: " + exc);
            Debug.log("registration", "Bulk logger registration failed: " + exc);
        }

        // 4-6. Fetch, resolve, apply
        try {
            fetchAndApply("install()");
        } catch (Exception exc) {
            LOG.warning("Failed to fetch/apply logging levels during connect: " + exc);
            Debug.log("resolution", "Failed to fetch/apply logging levels during connect: " + exc);
        }

        // 7. Register WebSocket event handlers for real-time level updates
        SharedWebSocket ws = ensureWs();
        if (ws != null) {
            ws.on("logger_changed", loggerChangedHandler);
            ws.on("logger_deleted", loggerDeletedHandler);
            ws.on("group_changed", groupChangedHandler);
            ws.on("group_deleted", groupDeletedHandler);
            ws.on("loggers_changed", loggersChangedHandler);
        }

        connected = true;
    }

    // -----------------------------------------------------------------------
    // Live surface: change listeners
    // -----------------------------------------------------------------------

    /**
     * Register a global change listener that fires for any logger change.
     *
     * <p>Requires {@link #install} first; raises {@link NotInstalledError}
     * otherwise.</p>
     *
     * @param listener the callback invoked with a {@link LoggerChangeEvent}
     *     whenever a logger's effective level changes
     * @throws NotInstalledError if called before {@link #install}
     */
    public void onChange(Consumer<LoggerChangeEvent> listener) {
        requireInstalled();
        globalListeners.add(listener);
    }

    /**
     * Register a key-scoped change listener that fires only for the given logger id.
     *
     * <p>Requires {@link #install} first; raises {@link NotInstalledError}
     * otherwise.</p>
     *
     * @param id       the logger id to watch
     * @param listener the callback invoked with a {@link LoggerChangeEvent}
     *     whenever this logger's effective level changes
     * @throws NotInstalledError if called before {@link #install}
     */
    public void onChange(String id, Consumer<LoggerChangeEvent> listener) {
        requireInstalled();
        keyListeners.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Re-fetch all loggers and groups and fire listener events for any deltas.
     *
     * <p>Requires {@link #install} first; raises {@link NotInstalledError}
     * otherwise.</p>
     *
     * @throws NotInstalledError if called before {@link #install}
     */
    public void refresh() {
        requireInstalled();
        Debug.log("resolution", "refresh() called, triggering full resolution pass");
        Map<String, String> pre = snapshotEffectiveLevels();
        try {
            fetchCache("refresh()");
        } catch (Exception e) {
            LOG.warning("Failed to fetch levels during refresh: " + e.getMessage());
            Debug.log("resolution", "refresh fetch failed: " + e);
            return;
        }
        applyDeltasAndFire(pre, "manual");
    }

    /**
     * Returns whether {@link #install} has been called.
     *
     * @return {@code true} once {@link #install} has run, {@code false} otherwise
     */
    public boolean isInstalled() {
        return connected;
    }

    /**
     * Returns the loaded logging-framework adapters.
     *
     * @return the adapters used to discover loggers and apply levels — those
     *     registered via {@link #registerAdapter}, or the auto-loaded set
     */
    public List<LoggingAdapter> getAdapters() {
        return adapters;
    }

    // -----------------------------------------------------------------------
    // Key normalization
    // -----------------------------------------------------------------------

    /**
     * Normalize a logger name.
     *
     * <ul>
     *   <li>Replace {@code /} with {@code .}</li>
     *   <li>Replace {@code :} with {@code .}</li>
     *   <li>Lowercase everything</li>
     * </ul>
     */
    static String normalizeKey(String name) {
        return name.replace("/", ".").replace(":", ".").toLowerCase();
    }

    // -----------------------------------------------------------------------
    // Adapter auto-loading
    // -----------------------------------------------------------------------

    private void autoLoadAdapters() {
        loadAdaptersFromProviders(
                ServiceLoader.load(LoggingAdapter.class).stream()
                        .collect(java.util.stream.Collectors.toList()));
    }

    /** Package-private for testing — production code always calls autoLoadAdapters(). */
    void loadAdaptersFromProviders(Iterable<ServiceLoader.Provider<LoggingAdapter>> providers) {
        for (ServiceLoader.Provider<LoggingAdapter> provider : providers) {
            try {
                LoggingAdapter adapter = provider.get();
                adapters.add(adapter);
                Debug.log("lifecycle", "Loaded logging adapter: " + adapter.name());
            } catch (ServiceConfigurationError | NoClassDefFoundError e) {
                Debug.log("lifecycle", "Skipped logging adapter (dependency not installed): " + e.getMessage());
            } catch (Exception e) {
                LOG.warning("Failed to load logging adapter: " + e.getMessage());
            }
        }
        if (adapters.isEmpty()) {
            LOG.warning("No logging framework detected. Runtime logging control requires a supported framework.");
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /** Build a LoggerSource from an adapter's (name, explicit, effective) discovery tuple. */
    private LoggerSource loggerSourceFor(String name, String explicitLevel, String effectiveLevel) {
        LogLevel resolved = effectiveLevel != null ? tryParseLogLevel(effectiveLevel, name) : null;
        LogLevel explicit = explicitLevel != null ? tryParseLogLevel(explicitLevel, name) : null;
        return new LoggerSource(name, resolved, explicit, service, environment);
    }

    /** Callback from adapters when a new logger is created. */
    private void onNewLogger(String name, String explicitLevel) {
        String normalized = normalizeKey(name);
        Debug.log("discovery", "new logger intercepted via callback: '" + name
                + "' (normalized: '" + normalized + "')");
        nameMap.put(name, normalized);
        loggers.register(loggerSourceFor(name, explicitLevel, explicitLevel));
        Debug.log("registration", "queued '" + name
                + "' for bulk registration (buffer size: " + loggers.pendingCount() + ")");

        // If connected, try to apply level from cache
        if (connected && loggersCache.containsKey(normalized)) {
            Map<String, Object> entry = loggersCache.get(normalized);
            if (Boolean.TRUE.equals(entry.get("managed"))) {
                Debug.log("resolution",
                        "applying immediate level for newly discovered managed logger '" + name + "'");
                String resolved = Resolution.resolveLevel(normalized, environment, loggersCache, groupsCache);
                for (LoggingAdapter adapter : adapters) {
                    try {
                        adapter.applyLevel(name, resolved);
                    } catch (Exception e) {
                        LOG.warning("Adapter " + adapter.name() + " apply_level() failed for " + name);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal: event handlers (called by SharedWebSocket)
    // -----------------------------------------------------------------------

    private void handleLoggerChanged(Map<String, Object> data) {
        if (!connected) {
            return;
        }
        String key = data.get("id") instanceof String s ? s : "";
        Debug.log("websocket", "logger_changed: fetching logger '" + key + "'");
        Map<String, String> pre = snapshotEffectiveLevels();
        try {
            LoggerResponse resp = loggersApi.getLogger(key);
            if (resp != null && resp.getData() != null) {
                var attrs = resp.getData().getAttributes();
                String lid = resp.getData().getId() != null ? resp.getData().getId() : key;
                Map<String, Object> entry = new HashMap<>();
                entry.put("level", attrs.getLevel() != null ? attrs.getLevel().getValue() : null);
                entry.put("group", attrs.getGroup());
                entry.put("managed", attrs.getManaged());
                entry.put("environments", attrs.getEnvironments() != null
                        ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                loggersCache.put(lid, entry);
            }
        } catch (ApiException e) {
            LOG.warning("Failed to fetch logger '" + key + "' after WS event: " + e.getMessage());
            Debug.log("websocket", "logger_changed scoped fetch failed for '" + key + "': " + e);
            return;
        }
        applyDeltasAndFire(pre, "websocket");
    }

    private void handleLoggerDeleted(Map<String, Object> data) {
        if (!connected) {
            return;
        }
        String key = data.get("id") instanceof String s ? s : "";
        Debug.log("websocket", "logger_deleted: removing logger '" + key + "'");
        Map<String, String> pre = snapshotEffectiveLevels();
        loggersCache.remove(key);
        applyDeltasAndFire(pre, "websocket");
    }

    private void handleGroupChanged(Map<String, Object> data) {
        if (!connected) {
            return;
        }
        String key = data.get("id") instanceof String s ? s : "";
        Debug.log("websocket", "group_changed: fetching group '" + key + "'");
        Map<String, String> pre = snapshotEffectiveLevels();
        try {
            LogGroupResponse resp = logGroupsApi.getLogGroup(key);
            if (resp != null && resp.getData() != null) {
                var attrs = resp.getData().getAttributes();
                String gid = resp.getData().getId() != null ? resp.getData().getId() : key;
                Map<String, Object> entry = new HashMap<>();
                entry.put("level", attrs.getLevel() != null ? attrs.getLevel().getValue() : null);
                entry.put("group", attrs.getParentId());
                entry.put("environments", attrs.getEnvironments() != null
                        ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                groupsCache.put(gid, entry);
            }
        } catch (ApiException e) {
            LOG.warning("Failed to fetch log group '" + key + "' after WS event: " + e.getMessage());
            Debug.log("websocket", "group_changed scoped fetch failed for '" + key + "': " + e);
            return;
        }
        applyDeltasAndFire(pre, "websocket");
    }

    private void handleGroupDeleted(Map<String, Object> data) {
        if (!connected) {
            return;
        }
        String key = data.get("id") instanceof String s ? s : "";
        Debug.log("websocket", "group_deleted: removing group '" + key + "'");
        Map<String, String> pre = snapshotEffectiveLevels();
        groupsCache.remove(key);
        applyDeltasAndFire(pre, "websocket");
    }

    private void handleLoggersChanged(Map<String, Object> data) {
        if (!connected) {
            return;
        }
        Debug.log("websocket", "loggers_changed: full re-fetch");
        try {
            Map<String, String> pre = snapshotEffectiveLevels();
            fetchCache("loggers_changed WS event");
            applyDeltasAndFire(pre, "websocket");
        } catch (Exception exc) {
            LOG.warning("Failed to re-fetch/apply logging levels after loggers_changed event: " + exc);
            Debug.log("websocket", "loggers_changed fetch failed: " + exc);
        }
    }

    /**
     * Effective level for every locally-tracked managed logger.
     *
     * <p>This is the universe of loggers the adapter applies levels to —
     * the only loggers whose listener can fire. A logger not in
     * {@code nameMap} (never instantiated locally) or marked
     * {@code managed=false} in the cache is excluded.</p>
     */
    private Map<String, String> snapshotEffectiveLevels() {
        Map<String, String> snapshot = new HashMap<>();
        for (String normalizedId : nameMap.values()) {
            Map<String, Object> entry = loggersCache.get(normalizedId);
            if (entry == null || !Boolean.TRUE.equals(entry.get("managed"))) {
                continue;
            }
            snapshot.put(normalizedId,
                    Resolution.resolveLevel(normalizedId, environment, loggersCache, groupsCache));
        }
        return snapshot;
    }

    /**
     * Apply + fire per-logger whenever the effective level moved.
     *
     * <p>For every locally-tracked managed logger, recompute the effective
     * level and compare to {@code pre}. On a delta: call {@code applyLevel} on
     * every adapter AND fire one {@link LoggerChangeEvent} per affected
     * logger — once to each matching key-scoped listener and once to
     * every global listener (a global is semantically a key-scoped
     * subscription on every logger). No-op when nothing moved: no apply,
     * no fire.</p>
     */
    private void applyDeltasAndFire(Map<String, String> pre, String source) {
        for (Map.Entry<String, String> mapping : nameMap.entrySet()) {
            String originalName = mapping.getKey();
            String normalizedId = mapping.getValue();
            Map<String, Object> entry = loggersCache.get(normalizedId);
            if (entry == null || !Boolean.TRUE.equals(entry.get("managed"))) {
                continue;
            }
            String newLevel = Resolution.resolveLevel(normalizedId, environment, loggersCache, groupsCache);
            if (java.util.Objects.equals(pre.get(normalizedId), newLevel)) {
                continue;
            }
            LogLevel logLevel = tryParseLogLevel(newLevel, normalizedId);
            if (logLevel == null) {
                continue;
            }
            for (LoggingAdapter adapter : adapters) {
                try {
                    adapter.applyLevel(originalName, newLevel);
                } catch (Exception e) {
                    LOG.warning("Adapter " + adapter.name() + " apply_level() failed for " + originalName);
                }
            }
            if (metrics != null) {
                metrics.record("logging.level_changes", "changes", Map.of("logger", normalizedId));
            }
            fireForLogger(normalizedId, logLevel, source);
        }
    }

    /**
     * Fire one {@link LoggerChangeEvent} to every matching subscriber.
     *
     * <p>Both the key-scoped listeners registered for {@code loggerId} and
     * every global listener receive the same payload.</p>
     */
    private void fireForLogger(String loggerId, LogLevel level, String source) {
        LoggerChangeEvent event = new LoggerChangeEvent(loggerId, level, source);
        for (Consumer<LoggerChangeEvent> cb : globalListeners) {
            try {
                cb.accept(event);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Exception in global logging on_change listener", e);
            }
        }
        List<Consumer<LoggerChangeEvent>> scoped = keyListeners.get(loggerId);
        if (scoped != null) {
            for (Consumer<LoggerChangeEvent> cb : scoped) {
                try {
                    cb.accept(event);
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Exception in key-scoped logging on_change listener", e);
                }
            }
        }
    }

    /**
     * Parse a resolved level string into a typed LogLevel, returning null if
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

    /** Re-fetch loggers/groups into the cache (no apply, no fire). */
    private void fetchCache(String trigger) {
        Debug.log("resolution", "full resolution pass starting (trigger: " + trigger + ")");
        Map<String, Map<String, Object>> loggersData = fetchAllLoggers();
        Map<String, Map<String, Object>> groupsData = fetchAllGroups();
        this.loggersCache = new ConcurrentHashMap<>(loggersData);
        this.groupsCache = new ConcurrentHashMap<>(groupsData);
    }

    /**
     * Fetch loggers/groups and unconditionally apply levels (initial install path).
     *
     * <p>Silent — does not fire change-listener events. Use
     * {@code applyDeltasAndFire} from the WS / refresh paths to get
     * per-logger fanout.</p>
     */
    private void fetchAndApply(String trigger) {
        fetchCache(trigger);
        applyLevels();
    }

    private Map<String, Map<String, Object>> fetchAllLoggers() {
        Map<String, Map<String, Object>> loggersData = new HashMap<>();
        int page = 1;
        try {
            while (true) {
                Debug.log("api", "GET /api/v1/loggers (page " + page + ")");
                // Positional args: filterManaged, filterService, filterLastSeen,
                // filterSearch, sort, pageNumber, pageSize, metaTotal.
                LoggerListResponse loggerResp = loggersApi.listLoggers(
                        null, null, null, null, null, page, RUNTIME_PAGE_SIZE, null);
                List<LoggerResource> rows = loggerResp.getData() != null
                        ? loggerResp.getData() : List.of();
                for (LoggerResource r : rows) {
                    var attrs = r.getAttributes();
                    String id = r.getId() != null ? r.getId() : "";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("level", attrs.getLevel() != null ? attrs.getLevel().getValue() : null);
                    entry.put("group", attrs.getGroup());
                    entry.put("managed", attrs.getManaged());
                    entry.put("environments", attrs.getEnvironments() != null
                            ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                    loggersData.put(id, entry);
                }
                if (rows.size() < RUNTIME_PAGE_SIZE) {
                    break;
                }
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
                Debug.log("api", "GET /api/v1/log-groups (page " + page + ")");
                LogGroupListResponse groupResp = logGroupsApi.listLogGroups(
                        null, page, RUNTIME_PAGE_SIZE, null);
                List<LogGroupResource> rows = groupResp.getData() != null
                        ? groupResp.getData() : List.of();
                for (LogGroupResource r : rows) {
                    var attrs = r.getAttributes();
                    String gid = r.getId() != null ? r.getId() : "";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("level", attrs.getLevel() != null ? attrs.getLevel().getValue() : null);
                    entry.put("group", attrs.getParentId());
                    entry.put("environments", attrs.getEnvironments() != null
                            ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                    groupsData.put(gid, entry);
                }
                if (rows.size() < RUNTIME_PAGE_SIZE) {
                    break;
                }
                page++;
            }
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
        return groupsData;
    }

    /** Apply resolved levels to all managed, locally-present loggers. */
    private void applyLevels() {
        Debug.log("resolution", "running full resolution pass for " + nameMap.size() + " local loggers");
        for (Map.Entry<String, String> mapping : nameMap.entrySet()) {
            String originalName = mapping.getKey();
            String normalizedId = mapping.getValue();
            Map<String, Object> entry = loggersCache.get(normalizedId);
            if (entry == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(entry.get("managed"))) {
                continue;
            }
            String resolved = Resolution.resolveLevel(normalizedId, environment, loggersCache, groupsCache);
            LogLevel logLevel = tryParseLogLevel(resolved, normalizedId);
            if (logLevel == null) {
                continue;
            }
            for (LoggingAdapter adapter : adapters) {
                try {
                    adapter.applyLevel(originalName, resolved);
                } catch (Exception e) {
                    LOG.warning("Adapter " + adapter.name() + " apply_level() failed for " + originalName);
                }
            }
            if (metrics != null) {
                metrics.record("logging.level_changes", "changes", Map.of("logger", normalizedId));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    /**
     * Release resources — only those this client owns.
     *
     * <p>Uninstalls the adapter hooks, unsubscribes from the WebSocket, and
     * tears down the owned WebSocket (standalone install) and the owned
     * logging transport (standalone construction). A wired client borrows
     * the parent's transport and WebSocket and closes neither.</p>
     */
    @Override
    public void close() {
        Debug.log("lifecycle", "LoggingClient.close() called");
        for (LoggingAdapter adapter : adapters) {
            try {
                adapter.uninstallHook();
            } catch (Exception e) {
                Debug.log("lifecycle", "Adapter " + adapter.name() + " uninstall_hook() failed: " + e);
            }
        }
        if (wsManager != null) {
            wsManager.off("logger_changed", loggerChangedHandler);
            wsManager.off("logger_deleted", loggerDeletedHandler);
            wsManager.off("group_changed", groupChangedHandler);
            wsManager.off("group_deleted", groupDeletedHandler);
            wsManager.off("loggers_changed", loggersChangedHandler);
            if (ownsWs) {
                wsManager.close();
                ownsWs = false;
            }
            wsManager = null;
        }
        // The standalone logging transport is a JDK HttpClient managed by the
        // generated ApiClient; the JDK reclaims its connection pool on GC.
        // Nothing further to release for ownsTransport today.
        connected = false;
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
        return buffer.pendingCount();
    }
}
