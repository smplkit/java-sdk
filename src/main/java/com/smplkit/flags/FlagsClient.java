package com.smplkit.flags;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smplkit.Context;
import com.smplkit.SharedWebSocket;
import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.errors.SmplException;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.model.ContextBulkItem;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagEnvironment;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResource;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import com.smplkit.internal.generated.flags.model.FlagRule;
import com.smplkit.internal.generated.flags.model.FlagValue;
import io.github.jamsesso.jsonlogic.JsonLogic;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for the Smpl Flags service.
 *
 * <p>Provides flag management via {@link #management()} and runtime evaluation
 * ({@link #booleanFlag}, {@link #stringFlag}, etc.).</p>
 */
public final class FlagsClient {

    private static final Logger LOG = Logger.getLogger("smplkit.flags");
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final JsonLogic JSON_LOGIC = new JsonLogic();
    private static final int CACHE_MAX_SIZE = 10_000;
    private static final int CONTEXT_BUFFER_MAX_SIZE = 10_000;
    private static final int CONTEXT_BATCH_FLUSH_SIZE = 100;

    final FlagsApi flagsApi;
    private final ContextsApi contextsApi;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String flagsBaseUrl;
    private final String appBaseUrl;
    private final Duration timeout;

    // --- Runtime state ---
    private volatile boolean connected = false;
    private volatile String environment;
    private final Map<String, Map<String, Object>> flagStore = new ConcurrentHashMap<>();
    private final Map<String, Flag<?>> handles = new ConcurrentHashMap<>();

    // Resolution cache — synchronized LRU
    private final Map<String, Object> resolutionCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            }
    );
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private static final Object CACHE_NULL_SENTINEL = new Object();

    // Context registration buffer
    private final Map<String, Map<String, Object>> contextBuffer = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > CONTEXT_BUFFER_MAX_SIZE;
                }
            }
    );
    private final ConcurrentLinkedQueue<Map<String, Object>> pendingContexts = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService contextFlushExecutor;
    private ScheduledFuture<?> contextFlushFuture;

    // Change listeners
    private final List<ListenerEntry> listeners = Collections.synchronizedList(new ArrayList<>());

    // Context provider
    private volatile Supplier<List<Context>> contextProvider;

    // Metrics reporter (optional)
    private volatile com.smplkit.MetricsReporter metrics;

    // Service name from parent SmplClient (for auto-injection)
    private volatile String parentService;

    // Shared WebSocket reference (set by SmplClient)
    private volatile SharedWebSocket sharedWs;
    private final Consumer<Map<String, Object>> flagChangedHandler;
    private final Consumer<Map<String, Object>> flagDeletedHandler;

    // Management accessor
    private final FlagsManagement management;

    public FlagsClient(FlagsApi flagsApi, ContextsApi contextsApi,
                       HttpClient httpClient, String apiKey,
                       String flagsBaseUrl, String appBaseUrl, Duration timeout) {
        this.flagsApi = flagsApi;
        this.contextsApi = contextsApi;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.flagsBaseUrl = flagsBaseUrl;
        this.appBaseUrl = appBaseUrl;
        this.timeout = timeout;

        this.contextFlushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smplkit-flags-ctx-flush");
            t.setDaemon(true);
            return t;
        });

        this.flagChangedHandler = this::handleFlagChanged;
        this.flagDeletedHandler = this::handleFlagDeleted;
        this.management = new FlagsManagement(this);
    }

    /** Package-private test constructor. */
    FlagsClient() {
        this.flagsApi = null;
        this.contextsApi = null;
        this.httpClient = null;
        this.apiKey = null;
        this.flagsBaseUrl = null;
        this.appBaseUrl = null;
        this.timeout = null;
        this.contextFlushExecutor = null;
        this.flagChangedHandler = this::handleFlagChanged;
        this.flagDeletedHandler = this::handleFlagDeleted;
        this.management = new FlagsManagement(this);
    }

    // -----------------------------------------------------------------------
    // Management accessor
    // -----------------------------------------------------------------------

    /**
     * Returns the management-plane API for flag CRUD operations and factory methods.
     *
     * @return the {@link FlagsManagement} instance
     */
    public FlagsManagement management() {
        return management;
    }

    public void setMetrics(com.smplkit.MetricsReporter metrics) {
        this.metrics = metrics;
    }

    public void setSharedWs(SharedWebSocket ws) {
        this.sharedWs = ws;
    }

    public void setParentService(String service) {
        this.parentService = service;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    // -----------------------------------------------------------------------
    // Runtime: typed flag handles
    // -----------------------------------------------------------------------

    public Flag<Boolean> booleanFlag(String id, boolean defaultValue) {
        Flag<Boolean> handle = new Flag<>(this, id, id, "BOOLEAN", defaultValue,
                null, null, null, null, null, Boolean.class);
        handles.put(id, handle);
        return handle;
    }

    public Flag<String> stringFlag(String id, String defaultValue) {
        Flag<String> handle = new Flag<>(this, id, id, "STRING", defaultValue,
                null, null, null, null, null, String.class);
        handles.put(id, handle);
        return handle;
    }

    public Flag<Number> numberFlag(String id, Number defaultValue) {
        Flag<Number> handle = new Flag<>(this, id, id, "NUMERIC", defaultValue,
                null, null, null, null, null, Number.class);
        handles.put(id, handle);
        return handle;
    }

    @SuppressWarnings("unchecked")
    public Flag<Object> jsonFlag(String id, Object defaultValue) {
        Flag<Object> handle = new Flag<>(this, id, id, "JSON", defaultValue,
                null, null, null, null, null, Object.class);
        handles.put(id, handle);
        return handle;
    }

    // -----------------------------------------------------------------------
    // Runtime: context provider
    // -----------------------------------------------------------------------

    public void setContextProvider(Supplier<List<Context>> provider) {
        this.contextProvider = provider;
    }

    // -----------------------------------------------------------------------
    // Runtime: lazy init
    // -----------------------------------------------------------------------

    /** Initializes the flags runtime on first use. Idempotent. */
    void _connectInternal() {
        if (connected) return;
        fetchAllFlags();
        resolutionCache.clear();

        SharedWebSocket ws = this.sharedWs;
        if (ws != null) {
            ws.on("flag_changed", flagChangedHandler);
            ws.on("flag_deleted", flagDeletedHandler);
            ws.ensureConnected(Duration.ofSeconds(10));
        }

        connected = true;

        if (contextFlushExecutor != null && contextFlushFuture == null) {
            contextFlushFuture = contextFlushExecutor.scheduleAtFixedRate(
                    this::flushContextsSafe, 30, 30, TimeUnit.SECONDS);
        }
    }

    /** Refreshes all flag definitions from the server. */
    public void refresh() {
        fetchAllFlags();
        resolutionCache.clear();
        fireAllChangeListeners("manual");
    }

    /** Returns evaluation statistics. */
    public FlagStats stats() {
        return new FlagStats(cacheHits.get(), cacheMisses.get());
    }

    // -----------------------------------------------------------------------
    // Runtime: context registration
    // -----------------------------------------------------------------------

    public void register(Context... contexts) {
        for (Context ctx : contexts) {
            observeContext(ctx);
        }
    }

    public void register(List<Context> contexts) {
        for (Context ctx : contexts) {
            observeContext(ctx);
        }
    }

    public void flushContexts() {
        List<Map<String, Object>> batch = drainPendingContexts();
        if (batch.isEmpty()) return;
        try {
            List<ContextBulkItem> items = new ArrayList<>();
            for (Map<String, Object> entry : batch) {
                String type = (String) entry.get("type");
                String key = (String) entry.get("key");
                @SuppressWarnings("unchecked")
                Map<String, Object> attrs = (Map<String, Object>) entry.get("attributes");
                ContextBulkItem item = new ContextBulkItem()
                        .type(type)
                        .key(key)
                        .attributes(attrs);
                items.add(item);
            }
            ContextBulkRegister reqBody = new ContextBulkRegister().contexts(items);
            contextsApi.bulkRegisterContexts(reqBody);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Context flush failed", e);
        }
    }

    // -----------------------------------------------------------------------
    // Runtime: change listeners
    // -----------------------------------------------------------------------

    /** Registers a listener that fires when any flag changes. */
    public void onChange(Consumer<FlagChangeEvent> listener) {
        listeners.add(new ListenerEntry(null, listener));
    }

    /** Registers a listener that fires when the specified flag changes. */
    public void onChange(String id, Consumer<FlagChangeEvent> listener) {
        listeners.add(new ListenerEntry(id, listener));
    }

    // -----------------------------------------------------------------------
    // Internal: evaluation engine
    // -----------------------------------------------------------------------

    /** Evaluates a flag by id. Called by {@link Flag#get()}. */
    @SuppressWarnings("unchecked")
    Object _evaluateHandle(String id, Object defaultValue, List<Context> contexts) {
        if (!connected) {
            _connectInternal();
        }

        Map<String, Object> flagData = flagStore.get(id);
        if (flagData == null) return defaultValue;

        List<Context> ctxList;
        if (contexts != null) {
            ctxList = contexts;
        } else if (contextProvider != null) {
            ctxList = contextProvider.get();
        } else {
            ctxList = List.of();
        }

        for (Context ctx : ctxList) {
            observeContext(ctx);
        }

        if (pendingContexts.size() >= CONTEXT_BATCH_FLUSH_SIZE) {
            Thread flushThread = new Thread(this::flushContextsSafe, "smplkit-flags-ctx-flush-eager");
            flushThread.setDaemon(true);
            flushThread.start();
        }

        Map<String, Object> evalData = buildEvalData(ctxList);

        String cacheKey = id + ":" + hashContexts(ctxList);
        Object cached = resolutionCache.get(cacheKey);
        if (cached != null) {
            cacheHits.incrementAndGet();
            if (metrics != null) {
                Map<String, String> dims = Map.of("flag", id);
                metrics.record("flags.cache_hits", "hits");
                metrics.record("flags.evaluations", "evaluations", dims);
            }
            return cached == CACHE_NULL_SENTINEL ? defaultValue : cached;
        }
        cacheMisses.incrementAndGet();
        if (metrics != null) {
            Map<String, String> dims = Map.of("flag", id);
            metrics.record("flags.cache_misses", "misses");
            metrics.record("flags.evaluations", "evaluations", dims);
        }

        Object result = evaluateFlag(id, flagData, environment, evalData);
        if (result == null) {
            resolutionCache.put(cacheKey, CACHE_NULL_SENTINEL);
            return defaultValue;
        }
        resolutionCache.put(cacheKey, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object evaluateFlag(String key, Map<String, Object> flagData,
                                String env, Map<String, Object> evalData) {
        Object flagDefault = flagData.get("default");
        Map<String, Object> environments = (Map<String, Object>) flagData.get("environments");
        if (environments == null || !environments.containsKey(env)) {
            return flagDefault;
        }

        Map<String, Object> envData = (Map<String, Object>) environments.get(env);
        Boolean enabled = (Boolean) envData.get("enabled");
        if (enabled == null || !enabled) {
            Object envDefault = envData.get("default");
            return envDefault != null ? envDefault : flagDefault;
        }

        List<Map<String, Object>> rules = (List<Map<String, Object>>) envData.get("rules");
        if (rules != null) {
            for (Map<String, Object> rule : rules) {
                Map<String, Object> logic = (Map<String, Object>) rule.get("logic");
                if (logic == null || logic.isEmpty()) continue;
                try {
                    String logicJson = OBJECT_MAPPER.writeValueAsString(logic);
                    Object result = JSON_LOGIC.apply(logicJson, evalData);
                    if (isTruthy(result)) {
                        return rule.get("value");
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "JSON Logic evaluation error for rule in flag " + key, e);
                }
            }
        }

        Object envDefault = envData.get("default");
        return envDefault != null ? envDefault : flagDefault;
    }

    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) return !s.isEmpty();
        return true;
    }

    // -----------------------------------------------------------------------
    // Internal: data helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> buildEvalData(List<Context> contexts) {
        Map<String, Object> evalData = new HashMap<>();
        if (contexts != null) {
            for (Context ctx : contexts) {
                evalData.put(ctx.type(), ctx.toEvalDict());
            }
        }
        if (parentService != null && !evalData.containsKey("service")) {
            evalData.put("service", Map.of("key", parentService));
        }
        return evalData;
    }

    private String hashContexts(List<Context> contexts) {
        if (contexts == null || contexts.isEmpty()) return "empty";
        Map<String, Object> evalData = buildEvalData(contexts);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : new java.util.TreeMap<>(evalData).entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return String.valueOf(sb.toString().hashCode());
    }

    private void observeContext(Context ctx) {
        String compositeKey = ctx.type() + ":" + ctx.key();
        if (contextBuffer.containsKey(compositeKey)) return;
        Map<String, Object> entry = new HashMap<>();
        entry.put("type", ctx.type());
        entry.put("key", ctx.key());
        entry.put("attributes", ctx.attributes());
        contextBuffer.put(compositeKey, entry);
        pendingContexts.add(entry);
    }

    private List<Map<String, Object>> drainPendingContexts() {
        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> item;
        while ((item = pendingContexts.poll()) != null) {
            batch.add(item);
        }
        return batch;
    }

    private void flushContextsSafe() {
        flushContexts();
    }

    private void fetchAllFlags() {
        List<Flag<?>> flags = management.list();
        flagStore.clear();
        for (Flag<?> flag : flags) {
            flagStore.put(flag.getId(), flagToStoreEntry(flag));
        }
    }

    private Map<String, Object> flagToStoreEntry(Flag<?> flag) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", flag.getId());
        entry.put("name", flag.getName());
        entry.put("type", flag.getType());
        entry.put("default", flag.getDefault());
        entry.put("values", flag.getValues());
        entry.put("description", flag.getDescription());
        entry.put("environments", flag.getEnvironments());
        return entry;
    }

    // -----------------------------------------------------------------------
    // Internal: WebSocket handlers
    // -----------------------------------------------------------------------

    private void handleFlagChanged(Map<String, Object> data) {
        if (!connected) return;
        fetchAllFlags();
        resolutionCache.clear();
        fireAllChangeListeners("websocket");
    }

    private void handleFlagDeleted(Map<String, Object> data) {
        if (!connected) return;
        fetchAllFlags();
        resolutionCache.clear();
        fireAllChangeListeners("websocket");
    }

    private void fireAllChangeListeners(String source) {
        for (String flagId : flagStore.keySet()) {
            FlagChangeEvent event = new FlagChangeEvent(flagId, source);
            for (ListenerEntry entry : listeners) {
                if (entry.id != null && !entry.id.equals(flagId)) continue;
                try {
                    entry.listener.accept(event);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Exception in onChange listener for flag '" + flagId + "'", e);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal: response parsing (package-private for FlagsManagement)
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Flag<?> parseSingleResponse(FlagResponse response) {
        Map<String, Object> resp = OBJECT_MAPPER.convertValue(response, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        return parseFlagData(data);
    }

    @SuppressWarnings("unchecked")
    List<Flag<?>> parseListResponse(FlagListResponse response) {
        Map<String, Object> resp = OBJECT_MAPPER.convertValue(response, new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("data");
        if (items == null) return List.of();
        List<Flag<?>> result = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            result.add(parseFlagData(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Flag<?> parseFlagData(Map<String, Object> data) {
        String id = (String) data.get("id");
        Map<String, Object> attrs = (Map<String, Object>) data.get("attributes");
        if (attrs == null) attrs = data;

        String name = (String) attrs.get("name");
        String description = (String) attrs.get("description");
        String type = (String) attrs.get("type");
        Object defaultValue = attrs.get("default");
        List<Map<String, Object>> values = (List<Map<String, Object>>) attrs.get("values");
        Map<String, Object> environments = (Map<String, Object>) attrs.get("environments");
        Instant createdAt = parseInstant(attrs.get("created_at"));
        Instant updatedAt = parseInstant(attrs.get("updated_at"));

        Flag<Object> flag = new Flag<>(this,
                id != null ? id : "", name != null ? name : "",
                type != null ? type : "", defaultValue,
                values, description,
                environments, createdAt, updatedAt, Object.class);
        return flag;
    }

    static Instant parseInstant(Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FlagEnvironment> buildEnvironments(Map<String, Object> envs) {
        if (envs == null) return null;
        Map<String, FlagEnvironment> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : envs.entrySet()) {
            Map<String, Object> envData = (Map<String, Object>) entry.getValue();
            FlagEnvironment fe = new FlagEnvironment();
            if (envData.containsKey("enabled")) {
                fe.setEnabled((Boolean) envData.get("enabled"));
            }
            if (envData.containsKey("default")) {
                fe.setDefault(envData.get("default"));
            }
            if (envData.containsKey("rules")) {
                List<Map<String, Object>> rules = (List<Map<String, Object>>) envData.get("rules");
                List<FlagRule> flagRules = new ArrayList<>();
                for (Map<String, Object> r : rules) {
                    FlagRule fr = new FlagRule();
                    fr.setDescription((String) r.get("description"));
                    Map<String, Object> logic = (Map<String, Object>) r.get("logic");
                    fr.setLogic(logic != null ? logic : Map.of());
                    fr.setValue(r.get("value"));
                    flagRules.add(fr);
                }
                fe.setRules(flagRules);
            }
            result.put(entry.getKey(), fe);
        }
        return result;
    }

    static SmplException mapException(ApiException e) {
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }

    // -----------------------------------------------------------------------
    // Internal: create/update flags (called by Flag.save())
    // -----------------------------------------------------------------------

    /** Creates a new flag on the server. Called by {@link Flag#save()}. */
    @SuppressWarnings("unchecked")
    <T> Flag<T> _createFlag(Flag<T> flag) {
        try {
            var attrs = new com.smplkit.internal.generated.flags.model.Flag();
            attrs.setName(flag.getName());
            attrs.setType(flag.getType());
            attrs.setDefault(flag.getDefault());
            if (flag.getDescription() != null) {
                attrs.setDescription(flag.getDescription());
            }
            if (flag.getValues() != null) {
                List<FlagValue> fvs = new ArrayList<>();
                for (Map<String, Object> v : flag.getValues()) {
                    FlagValue fv = new FlagValue();
                    fv.setName((String) v.get("name"));
                    fv.setValue(v.get("value"));
                    fvs.add(fv);
                }
                attrs.setValues(fvs);
            } else {
                attrs.setValues(null);
            }
            if (flag.getEnvironments() != null && !flag.getEnvironments().isEmpty()) {
                attrs.setEnvironments(buildEnvironments(flag.getEnvironments()));
            }

            FlagResource data = new FlagResource().id(flag.getId()).type(FlagResource.TypeEnum.FLAG).attributes(attrs);
            FlagResponse body = new FlagResponse().data(data);
            FlagResponse response = flagsApi.createFlag(body);
            Flag<?> result = parseSingleResponse(response);
            return (Flag<T>) result;
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Updates an existing flag on the server. Called by {@link Flag#save()}. */
    @SuppressWarnings("unchecked")
    <T> Flag<T> _updateFlag(Flag<T> flag) {
        try {
            var attrs = new com.smplkit.internal.generated.flags.model.Flag();
            attrs.setName(flag.getName());
            attrs.setType(flag.getType());
            attrs.setDefault(flag.getDefault());
            if (flag.getDescription() != null) {
                attrs.setDescription(flag.getDescription());
            }
            if (flag.getValues() != null) {
                List<FlagValue> fvs = new ArrayList<>();
                for (Map<String, Object> v : flag.getValues()) {
                    FlagValue fv = new FlagValue();
                    fv.setName((String) v.get("name"));
                    fv.setValue(v.get("value"));
                    fvs.add(fv);
                }
                attrs.setValues(fvs);
            } else {
                attrs.setValues(null);
            }
            if (flag.getEnvironments() != null) {
                attrs.setEnvironments(buildEnvironments(flag.getEnvironments()));
            }

            FlagResource data = new FlagResource().id(flag.getId()).type(FlagResource.TypeEnum.FLAG).attributes(attrs);
            FlagResponse body = new FlagResponse().data(data);
            FlagResponse response = flagsApi.updateFlag(flag.getId(), body);
            Flag<?> result = parseSingleResponse(response);
            return (Flag<T>) result;
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Package-private test helpers
    // -----------------------------------------------------------------------

    /** Simulates a flag change event (for testing). */
    void simulateFlagChanged() {
        handleFlagChanged(Map.of());
    }

    /** Simulates a flag deletion event (for testing). */
    void simulateFlagDeleted() {
        handleFlagDeleted(Map.of());
    }

    boolean isConnected() {
        return connected;
    }

    /** Resets runtime state (for testing). */
    void disconnect() {
        connected = false;
        if (contextFlushFuture != null) {
            contextFlushFuture.cancel(false);
            contextFlushFuture = null;
        }
        SharedWebSocket ws = this.sharedWs;
        if (ws != null) {
            ws.off("flag_changed", flagChangedHandler);
            ws.off("flag_deleted", flagDeletedHandler);
        }
        flagStore.clear();
        resolutionCache.clear();
    }

    private record ListenerEntry(String id, Consumer<FlagChangeEvent> listener) {}
}
