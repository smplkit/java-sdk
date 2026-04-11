package com.smplkit.logging;

import com.smplkit.Helpers;
import com.smplkit.LogLevel;
import com.smplkit.errors.ApiExceptionHandler;
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
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Client for the smplkit Logging service.
 *
 * <p>Provides logger and group management ({@link #new_}, {@link #get}, {@link #list},
 * {@link #delete}) and runtime level control ({@link #start()}, {@link #onChange}).</p>
 *
 * <p>Supports JUL, Logback, and Log4j2 via pluggable {@link LoggingAdapter} instances.</p>
 */
public final class LoggingClient {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("smplkit.logging");
    private static final String FALLBACK_LEVEL = "INFO";

    private static final String[][] BUILTIN_ADAPTERS = {
            {"com.smplkit.logging.adapters.JulAdapter", null},
            {"com.smplkit.logging.adapters.Slf4jLogbackAdapter", "ch.qos.logback.classic.LoggerContext"},
            {"com.smplkit.logging.adapters.Log4j2Adapter", "org.apache.logging.log4j.core.LoggerContext"},
    };

    private final LoggersApi loggersApi;
    private final LogGroupsApi logGroupsApi;
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

    // -----------------------------------------------------------------------
    // Adapter registration
    // -----------------------------------------------------------------------

    /**
     * Registers a custom logging adapter. Must be called before {@link #start()}.
     *
     * <p>Registering an adapter disables automatic adapter detection.</p>
     *
     * @param adapter the adapter to register
     * @throws IllegalStateException if called after start()
     */
    public void registerAdapter(LoggingAdapter adapter) {
        if (started) {
            throw new IllegalStateException("Cannot register adapters after start()");
        }
        explicitAdapters = true;
        adapters.add(adapter);
    }

    // -----------------------------------------------------------------------
    // Management API: Loggers
    // -----------------------------------------------------------------------

    /**
     * Create an unsaved Logger with the given id. Call {@link Logger#save()} to persist.
     *
     * @param id the logger id
     * @return an unsaved Logger instance
     */
    public Logger new_(String id) {
        return new Logger(this, id, Helpers.keyToDisplayName(id),
                null, null, false, null, null, null, null);
    }

    /**
     * Create an unsaved Logger with the given id, name, and managed flag.
     *
     * @param id      the logger id
     * @param name    the display name
     * @param managed whether this logger is managed by smplkit
     * @return an unsaved Logger instance
     */
    public Logger new_(String id, String name, boolean managed) {
        return new Logger(this, id, name,
                null, null, managed, null, null, null, null);
    }

    /**
     * Get a logger by id.
     *
     * @param id the logger id
     * @return the Logger
     * @throws SmplNotFoundException if no logger with the given id exists
     */
    public Logger get(String id) {
        try {
            LoggerResponse response = loggersApi.getLogger(id);
            return loggerResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /**
     * List all loggers.
     *
     * @return list of all loggers
     */
    public List<Logger> list() {
        try {
            LoggerListResponse response = loggersApi.listLoggers(null);
            List<Logger> result = new ArrayList<>();
            if (response.getData() != null) {
                for (LoggerResource r : response.getData()) {
                    result.add(loggerResourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /**
     * Delete a logger by id.
     *
     * @param id the logger id to delete
     */
    public void delete(String id) {
        try {
            loggersApi.deleteLogger(id);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Creates a new logger on the server. Called by {@link Logger#save()}. */
    Logger _createLogger(Logger lg) {
        try {
            ResponseLogger body = buildLoggerBody(null, lg);
            LoggerResponse response = loggersApi.createLogger(body);
            return loggerResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Updates an existing logger on the server. Called by {@link Logger#save()}. */
    Logger _updateLogger(Logger lg) {
        try {
            ResponseLogger body = buildLoggerBody(lg.getId(), lg);
            LoggerResponse response = loggersApi.updateLogger(lg.getId(), body);
            return loggerResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Management API: LogGroups
    // -----------------------------------------------------------------------

    /**
     * Create an unsaved LogGroup with the given id. Call {@link LogGroup#save()} to persist.
     *
     * @param id the group id
     * @return an unsaved LogGroup instance
     */
    public LogGroup newGroup(String id) {
        return new LogGroup(this, id, Helpers.keyToDisplayName(id),
                null, null, null, null, null);
    }

    /**
     * Create an unsaved LogGroup with the given id, name, and parent group.
     *
     * @param id          the group id
     * @param name        the display name
     * @param parentGroup the parent group slug or null
     * @return an unsaved LogGroup instance
     */
    public LogGroup newGroup(String id, String name, String parentGroup) {
        return new LogGroup(this, id, name,
                null, parentGroup, null, null, null);
    }

    /**
     * Get a log group by id.
     *
     * @param id the group id
     * @return the LogGroup
     * @throws SmplNotFoundException if no group with the given id exists
     */
    public LogGroup getGroup(String id) {
        try {
            LogGroupResponse response = logGroupsApi.getLogGroup(id);
            return logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /**
     * List all log groups.
     *
     * @return list of all log groups
     */
    public List<LogGroup> listGroups() {
        try {
            LogGroupListResponse response = logGroupsApi.listLogGroups();
            List<LogGroup> result = new ArrayList<>();
            if (response.getData() != null) {
                for (LogGroupResource r : response.getData()) {
                    result.add(logGroupResourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /**
     * Delete a log group by id.
     *
     * @param id the group id to delete
     */
    public void deleteGroup(String id) {
        try {
            logGroupsApi.deleteLogGroup(id);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Creates a new group on the server. Called by {@link LogGroup#save()}. */
    LogGroup _createGroup(LogGroup grp) {
        try {
            ResponseLogGroup body = buildGroupBody(null, grp);
            LogGroupResponse response = logGroupsApi.createLogGroup(body);
            return logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Updates an existing group on the server. Called by {@link LogGroup#save()}. */
    LogGroup _updateGroup(LogGroup grp) {
        try {
            ResponseLogGroup body = buildGroupBody(grp.getId(), grp);
            LogGroupResponse response = logGroupsApi.updateLogGroup(grp.getId(), body);
            return logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Runtime: start, onChange
    // -----------------------------------------------------------------------

    /**
     * Starts runtime logging control. Idempotent.
     *
     * <p>Fetches managed log levels from the server and applies them
     * to the logging framework(s) in use.</p>
     */
    public void start() {
        if (started) {
            return;
        }

        // 1. Load adapters (auto or explicit)
        if (!explicitAdapters) {
            autoLoadAdapters();
        }

        // 2. Discover existing loggers from all adapters
        int totalDiscovered = 0;
        for (LoggingAdapter adapter : adapters) {
            try {
                List<DiscoveredLogger> discovered = adapter.discover();
                totalDiscovered += discovered.size();
                for (DiscoveredLogger dl : discovered) {
                    String normalized = normalizeKey(dl.name());
                    nameMap.put(dl.name(), normalized);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Adapter " + adapter.name() + " discover() failed", e);
            }
        }
        if (metrics != null && totalDiscovered > 0) {
            metrics.record("logging.loggers_discovered", totalDiscovered, "loggers");
        }

        // 3. Install hooks for new logger detection
        for (LoggingAdapter adapter : adapters) {
            try {
                adapter.installHook(this::onNewLogger);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Adapter " + adapter.name() + " installHook() failed", e);
            }
        }

        // 4. Fetch all loggers and groups, resolve and apply levels
        try {
            fetchAndApply("start");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fetch/apply logging levels during start", e);
        }

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

    /** Returns whether start() has been called. */
    public boolean isStarted() {
        return started;
    }

    /** Returns the list of loaded adapters. */
    public List<LoggingAdapter> getAdapters() {
        return adapters;
    }

    // -----------------------------------------------------------------------
    // Level resolution (per ADR-034 section 3.1)
    // -----------------------------------------------------------------------

    /**
     * Resolves the effective log level for a logger in the given environment.
     *
     * <p>Package-private for testing.</p>
     */
    String resolveLevel(String loggerKey, String env,
                        Map<String, Map<String, Object>> loggers,
                        Map<String, Map<String, Object>> groups) {
        String result = resolveForEntry(loggerKey, env, loggers, groups);
        if (result != null) return result;

        // Dot-notation ancestry: walk up the hierarchy
        String[] parts = loggerKey.split("\\.");
        for (int i = parts.length - 1; i > 0; i--) {
            StringBuilder ancestor = new StringBuilder(parts[0]);
            for (int j = 1; j < i; j++) {
                ancestor.append('.').append(parts[j]);
            }
            result = resolveForEntry(ancestor.toString(), env, loggers, groups);
            if (result != null) return result;
        }

        return FALLBACK_LEVEL;
    }

    private String resolveForEntry(String key, String env,
                                   Map<String, Map<String, Object>> loggers,
                                   Map<String, Map<String, Object>> groups) {
        Map<String, Object> entry = loggers.get(key);
        if (entry == null) return null;

        // Step 1: env override on the entry itself
        String envLevel = extractEnvLevel(entry, env);
        if (envLevel != null) return envLevel;

        // Step 2: base level on the entry itself
        String base = (String) entry.get("level");
        if (base != null) return base;

        // Step 3: group chain
        String groupId = (String) entry.get("group");
        return resolveGroupChain(groupId, env, groups);
    }

    private String resolveGroupChain(String groupId, String env,
                                     Map<String, Map<String, Object>> groups) {
        Set<String> visited = new HashSet<>();
        String currentId = groupId;
        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            Map<String, Object> group = groups.get(currentId);
            if (group == null) break;

            String envLevel = extractEnvLevel(group, env);
            if (envLevel != null) return envLevel;

            String base = (String) group.get("level");
            if (base != null) return base;

            currentId = (String) group.get("group");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractEnvLevel(Map<String, Object> entry, String env) {
        Object envs = entry.get("environments");
        if (envs instanceof Map) {
            Object envData = ((Map<String, Object>) envs).get(env);
            if (envData instanceof Map) {
                Object level = ((Map<String, Object>) envData).get("level");
                if (level instanceof String) {
                    return (String) level;
                }
            }
        }
        return null;
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
        loadAdaptersFromTable(BUILTIN_ADAPTERS);
    }

    /** Loads adapters from a configuration table. Package-private for testing. */
    void loadAdaptersFromTable(String[][] adapterTable) {
        for (String[] entry : adapterTable) {
            String adapterClass = entry[0];
            String probeClass = entry[1];
            try {
                if (probeClass != null) {
                    Class.forName(probeClass);
                }
                LoggingAdapter adapter = (LoggingAdapter) Class.forName(adapterClass)
                        .getDeclaredConstructor().newInstance();
                adapters.add(adapter);
                LOG.fine("Loaded logging adapter: " + adapter.name());
            } catch (ClassNotFoundException e) {
                LOG.fine("Skipped adapter " + adapterClass + " (dependency not on classpath)");
            } catch (Exception e) {
                LOG.warning("Failed to load adapter " + adapterClass + ": " + e.getMessage());
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
        nameMap.put(originalName, normalized);
    }

    // -----------------------------------------------------------------------
    // Internal: fetch, resolve, apply
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void fetchAndApply(String source) {
        // Fetch loggers
        Map<String, Map<String, Object>> loggersData = new HashMap<>();
        try {
            LoggerListResponse loggerResp = loggersApi.listLoggers(null);
            if (loggerResp.getData() != null) {
                for (LoggerResource r : loggerResp.getData()) {
                    var attrs = r.getAttributes();
                    String id = r.getId() != null ? r.getId() : "";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", id);
                    entry.put("level", attrs.getLevel());
                    entry.put("group", attrs.getGroup());
                    entry.put("managed", attrs.getManaged());
                    entry.put("environments", attrs.getEnvironments() != null
                            ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                    loggersData.put(id, entry);
                }
            }
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }

        // Fetch groups
        Map<String, Map<String, Object>> groupsData = new HashMap<>();
        try {
            LogGroupListResponse groupResp = logGroupsApi.listLogGroups();
            if (groupResp.getData() != null) {
                for (LogGroupResource r : groupResp.getData()) {
                    var attrs = r.getAttributes();
                    String gid = r.getId() != null ? r.getId() : "";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", gid);
                    entry.put("level", attrs.getLevel());
                    entry.put("group", attrs.getGroup());
                    entry.put("environments", attrs.getEnvironments() != null
                            ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                    groupsData.put(gid, entry);
                }
            }
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }

        this.loggersCache = new ConcurrentHashMap<>(loggersData);
        this.groupsCache = new ConcurrentHashMap<>(groupsData);

        // Resolve and apply levels
        applyLevels(source);
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

            String resolved = resolveLevel(normalizedKey, environment, loggersCache, groupsCache);
            try {
                // Validate the level string
                LogLevel logLevel = LogLevel.valueOf(resolved);

                // Apply through all adapters
                for (LoggingAdapter adapter : adapters) {
                    try {
                        adapter.applyLevel(originalName, resolved);
                    } catch (Exception e) {
                        LOG.log(Level.FINE, "Adapter " + adapter.name()
                                + " applyLevel failed for " + originalName, e);
                    }
                }

                if (metrics != null) {
                    metrics.record("logging.level_changes", "changes",
                            java.util.Map.of("logger", normalizedKey));
                }

                // Fire change listeners
                LoggerChangeEvent event = new LoggerChangeEvent(normalizedKey, logLevel, source);
                fireChangeListeners(normalizedKey, event);
            } catch (IllegalArgumentException e) {
                LOG.log(Level.FINE, "Unknown level ''{0}'' for logger ''{1}''",
                        new Object[]{resolved, normalizedKey});
            }
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
        for (LoggingAdapter adapter : adapters) {
            try {
                adapter.uninstallHook();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Adapter " + adapter.name() + " uninstallHook() failed", e);
            }
        }
        started = false;
    }

    // -----------------------------------------------------------------------
    // Model conversion
    // -----------------------------------------------------------------------

    private Logger loggerResponseToModel(LoggerResponse response) {
        return loggerResourceToModel(response.getData());
    }

    private Logger loggerResourceToModel(LoggerResource resource) {
        var attrs = resource.getAttributes();
        return new Logger(
                this,
                resource.getId() != null ? resource.getId() : null,
                attrs.getName(),
                attrs.getLevel(),
                attrs.getGroup(),
                attrs.getManaged() != null ? attrs.getManaged() : false,
                attrs.getSources() != null ? new ArrayList<>(attrs.getSources()) : new ArrayList<>(),
                attrs.getEnvironments() != null ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>(),
                toInstant(attrs.getCreatedAt()),
                toInstant(attrs.getUpdatedAt())
        );
    }

    private LogGroup logGroupResponseToModel(LogGroupResponse response) {
        return logGroupResourceToModel(response.getData());
    }

    private LogGroup logGroupResourceToModel(LogGroupResource resource) {
        var attrs = resource.getAttributes();
        return new LogGroup(
                this,
                resource.getId() != null ? resource.getId() : null,
                attrs.getName(),
                attrs.getLevel(),
                attrs.getGroup(),
                attrs.getEnvironments() != null ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>(),
                toInstant(attrs.getCreatedAt()),
                toInstant(attrs.getUpdatedAt())
        );
    }

    // -----------------------------------------------------------------------
    // Request body building
    // -----------------------------------------------------------------------

    private ResponseLogger buildLoggerBody(String loggerId, Logger lg) {
        var attrs = new com.smplkit.internal.generated.logging.model.Logger();
        attrs.setName(lg.getName());
        if (lg.getId() != null) attrs.setId(lg.getId());
        if (lg.getLevel() != null) attrs.setLevel(lg.getLevel());
        if (lg.getGroup() != null) attrs.setGroup(lg.getGroup());
        attrs.setManaged(lg.isManaged());
        if (lg.getEnvironments() != null && !lg.getEnvironments().isEmpty()) {
            attrs.setEnvironments(new HashMap<>(lg.getEnvironments()));
        }

        ResourceLogger data = new ResourceLogger();
        data.setType("logger");
        data.setAttributes(attrs);
        if (loggerId != null) data.setId(loggerId);

        ResponseLogger body = new ResponseLogger();
        body.setData(data);
        return body;
    }

    private ResponseLogGroup buildGroupBody(String groupId, LogGroup grp) {
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup();
        attrs.setName(grp.getName());
        if (grp.getId() != null) attrs.setId(grp.getId());
        if (grp.getLevel() != null) attrs.setLevel(grp.getLevel());
        if (grp.getGroup() != null) attrs.setGroup(grp.getGroup());
        if (grp.getEnvironments() != null && !grp.getEnvironments().isEmpty()) {
            attrs.setEnvironments(new HashMap<>(grp.getEnvironments()));
        }

        ResourceLogGroup data = new ResourceLogGroup();
        data.setType("log_group");
        data.setAttributes(attrs);
        if (groupId != null) data.setId(groupId);

        ResponseLogGroup body = new ResponseLogGroup();
        body.setData(data);
        return body;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }

    private static RuntimeException mapLoggingException(ApiException e) {
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
