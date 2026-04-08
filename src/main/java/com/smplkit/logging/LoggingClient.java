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

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Client for the smplkit Logging service.
 *
 * <p>Provides management CRUD (new_, get, list, delete for loggers and groups)
 * and runtime control ({@link #start()}, {@link #onChange}).</p>
 */
public final class LoggingClient {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("smplkit.logging");
    private static final String FALLBACK_LEVEL = "INFO";

    private final LoggersApi loggersApi;
    private final LogGroupsApi logGroupsApi;
    private final HttpClient httpClient;
    private final String apiKey;

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

    /** Set the environment for level resolution. Called by SmplClient. */
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /** Set the service name. Called by SmplClient. */
    public void setService(String service) {
        this.service = service;
    }

    // -----------------------------------------------------------------------
    // Management API: Loggers
    // -----------------------------------------------------------------------

    /**
     * Create an unsaved Logger with the given key. Call {@link Logger#save()} to persist.
     *
     * @param key the logger key
     * @return an unsaved Logger instance
     */
    public Logger new_(String key) {
        return new Logger(this, null, key, Helpers.keyToDisplayName(key),
                null, null, false, null, null, null, null);
    }

    /**
     * Create an unsaved Logger with the given key, name, and managed flag.
     *
     * @param key     the logger key
     * @param name    the display name
     * @param managed whether this logger is managed by smplkit
     * @return an unsaved Logger instance
     */
    public Logger new_(String key, String name, boolean managed) {
        return new Logger(this, null, key, name,
                null, null, managed, null, null, null, null);
    }

    /**
     * Get a logger by key (always fetches from server via filter).
     *
     * @param key the logger key
     * @return the Logger
     * @throws SmplNotFoundException if no logger with the given key exists
     */
    public Logger get(String key) {
        try {
            LoggerListResponse response = loggersApi.listLoggers(key, null);
            if (response.getData() == null || response.getData().isEmpty()) {
                throw new SmplNotFoundException("Logger with key '" + key + "' not found", null);
            }
            return loggerResourceToModel(response.getData().get(0));
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
            LoggerListResponse response = loggersApi.listLoggers(null, null);
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
     * Delete a logger by key (looks up by key, then deletes by id).
     *
     * @param key the logger key to delete
     */
    public void delete(String key) {
        Logger lg = get(key);
        try {
            loggersApi.deleteLogger(UUID.fromString(lg.getId()));
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Internal: POST a new logger. Called by Logger.save() when id is null. */
    Logger _createLogger(Logger lg) {
        try {
            ResponseLogger body = buildLoggerBody(null, lg);
            LoggerResponse response = loggersApi.createLogger(body);
            return loggerResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Internal: PUT a full logger update. Called by Logger.save() when id is set. */
    Logger _updateLogger(Logger lg) {
        try {
            ResponseLogger body = buildLoggerBody(lg.getId(), lg);
            LoggerResponse response = loggersApi.updateLogger(
                    UUID.fromString(lg.getId()), body);
            return loggerResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Management API: LogGroups
    // -----------------------------------------------------------------------

    /**
     * Create an unsaved LogGroup with the given key. Call {@link LogGroup#save()} to persist.
     *
     * @param key the group key
     * @return an unsaved LogGroup instance
     */
    public LogGroup newGroup(String key) {
        return new LogGroup(this, null, key, Helpers.keyToDisplayName(key),
                null, null, null, null, null);
    }

    /**
     * Create an unsaved LogGroup with the given key, name, and parent group.
     *
     * @param key         the group key
     * @param name        the display name
     * @param parentGroup the parent group UUID or null
     * @return an unsaved LogGroup instance
     */
    public LogGroup newGroup(String key, String name, String parentGroup) {
        return new LogGroup(this, null, key, name,
                null, parentGroup, null, null, null);
    }

    /**
     * Get a log group by key.
     *
     * @param key the group key
     * @return the LogGroup
     * @throws SmplNotFoundException if no group with the given key exists
     */
    public LogGroup getGroup(String key) {
        try {
            LogGroupListResponse response = logGroupsApi.listLogGroups();
            if (response.getData() != null) {
                for (LogGroupResource r : response.getData()) {
                    String rkey = r.getAttributes() != null ? r.getAttributes().getKey() : null;
                    if (key.equals(rkey)) {
                        return logGroupResourceToModel(r);
                    }
                }
            }
            throw new SmplNotFoundException("Log group with key '" + key + "' not found", null);
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
     * Delete a log group by key (looks up by key, then deletes by id).
     *
     * @param key the group key to delete
     */
    public void deleteGroup(String key) {
        LogGroup grp = getGroup(key);
        try {
            logGroupsApi.deleteLogGroup(UUID.fromString(grp.getId()));
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Internal: POST a new group. Called by LogGroup.save() when id is null. */
    LogGroup _createGroup(LogGroup grp) {
        try {
            ResponseLogGroup body = buildGroupBody(null, grp);
            LogGroupResponse response = logGroupsApi.createLogGroup(body);
            return logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    /** Internal: PUT a full group update. Called by LogGroup.save() when id is set. */
    LogGroup _updateGroup(LogGroup grp) {
        try {
            ResponseLogGroup body = buildGroupBody(grp.getId(), grp);
            LogGroupResponse response = logGroupsApi.updateLogGroup(
                    UUID.fromString(grp.getId()), body);
            return logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw mapLoggingException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Runtime: start, onChange
    // -----------------------------------------------------------------------

    /**
     * Explicitly opt in to runtime logging control. Idempotent.
     *
     * <p>Discovers existing JUL loggers, fetches managed levels from the
     * server, and applies them to the local JVM.</p>
     */
    public void start() {
        if (started) {
            return;
        }

        // 1. Discover existing JUL loggers
        java.util.logging.LogManager manager = java.util.logging.LogManager.getLogManager();
        Enumeration<String> loggerNames = manager.getLoggerNames();
        while (loggerNames.hasMoreElements()) {
            String name = loggerNames.nextElement();
            String normalized = normalizeKey(name);
            nameMap.put(name, normalized);
        }

        // 2. Fetch all loggers and groups, resolve and apply levels
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
     * Register a key-scoped change listener that fires only for the given logger key.
     *
     * @param key      the logger key to watch
     * @param listener the callback
     */
    public void onChange(String key, Consumer<LoggerChangeEvent> listener) {
        keyListeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Returns whether start() has been called. Package-private for testing. */
    boolean isStarted() {
        return started;
    }

    // -----------------------------------------------------------------------
    // Level resolution (per ADR-034 section 3.1)
    // -----------------------------------------------------------------------

    /**
     * Resolve the effective log level for a logger in the current environment.
     *
     * <p>Resolution chain (first non-null wins):</p>
     * <ol>
     *   <li>Logger's environment-specific level</li>
     *   <li>Logger's base level</li>
     *   <li>Group chain (recursive: group's env level, group's level, parent group...)</li>
     *   <li>Dot-notation ancestry (walk com.acme.payments, com.acme, com)</li>
     *   <li>System fallback: "INFO"</li>
     * </ol>
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

    /** Normalize a logger name: replace / and : with ., lowercase. */
    static String normalizeKey(String name) {
        return name.replace("/", ".").replace(":", ".").toLowerCase();
    }

    // -----------------------------------------------------------------------
    // JUL level mapping
    // -----------------------------------------------------------------------

    /** Map a smplkit LogLevel to a java.util.logging.Level. */
    public static Level smplToJulLevel(LogLevel level) {
        return switch (level) {
            case TRACE -> Level.FINEST;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR, FATAL -> Level.SEVERE;
            case SILENT -> Level.OFF;
        };
    }

    /** Map a smplkit level string to a java.util.logging.Level. */
    static Level smplStringToJulLevel(String level) {
        return smplToJulLevel(LogLevel.valueOf(level));
    }

    // -----------------------------------------------------------------------
    // Internal: fetch, resolve, apply
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void fetchAndApply(String source) {
        // Fetch loggers
        Map<String, Map<String, Object>> loggersData = new HashMap<>();
        try {
            LoggerListResponse loggerResp = loggersApi.listLoggers(null, null);
            if (loggerResp.getData() != null) {
                for (LoggerResource r : loggerResp.getData()) {
                    var attrs = r.getAttributes();
                    String key = attrs.getKey() != null ? attrs.getKey() : "";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("key", key);
                    entry.put("level", attrs.getLevel());
                    entry.put("group", attrs.getGroup());
                    entry.put("managed", attrs.getManaged());
                    entry.put("environments", attrs.getEnvironments() != null
                            ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>());
                    loggersData.put(key, entry);
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
                    entry.put("key", attrs.getKey() != null ? attrs.getKey() : "");
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
                Level julLevel = smplStringToJulLevel(resolved);
                java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(originalName);
                julLogger.setLevel(julLevel);

                // Fire change listeners
                LogLevel logLevel = LogLevel.valueOf(resolved);
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

    /** Called by SmplClient.close() to clean up logging resources. */
    public void close() {
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
                attrs.getKey() != null ? attrs.getKey() : "",
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
                attrs.getKey() != null ? attrs.getKey() : "",
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
        if (lg.getKey() != null) attrs.setKey(lg.getKey());
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
        if (grp.getKey() != null) attrs.setKey(grp.getKey());
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
