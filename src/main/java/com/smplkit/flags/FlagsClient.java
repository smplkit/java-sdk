package com.smplkit.flags;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotConnectedException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.Flag;
import com.smplkit.internal.generated.flags.model.FlagEnvironment;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import com.smplkit.internal.generated.flags.model.FlagRule;
import com.smplkit.internal.generated.flags.model.FlagValue;
import com.smplkit.internal.generated.flags.model.ResourceFlag;
import com.smplkit.internal.generated.flags.model.ResponseFlag;
import io.github.jamsesso.jsonlogic.JsonLogic;
import io.github.jamsesso.jsonlogic.JsonLogicException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.UUID;
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
 * <p>Provides flag management (CRUD) and the prescriptive runtime tier
 * (typed flag handles, local evaluation, caching, context registration).
 * Obtained via {@link com.smplkit.SmplClient#flags()}.</p>
 */
public final class FlagsClient {

    private static final Logger LOG = Logger.getLogger("smplkit.flags");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final JsonLogic JSON_LOGIC = new JsonLogic();
    private static final int CACHE_MAX_SIZE = 10_000;
    private static final int CONTEXT_BUFFER_MAX_SIZE = 10_000;
    private static final int CONTEXT_BATCH_FLUSH_SIZE = 100;

    private final FlagsApi flagsApi;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String flagsBaseUrl;
    private final String appBaseUrl;
    private final Duration timeout;

    // --- Prescriptive tier state ---
    private volatile boolean connected = false;
    private volatile String environment;
    private volatile String connectionStatus = "disconnected";
    private final Map<String, Map<String, Object>> flagStore = new ConcurrentHashMap<>();
    private final Map<String, FlagHandle<?>> handles = new ConcurrentHashMap<>();

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

    // Service name from parent SmplClient (for auto-injection)
    private volatile String parentService;

    // Shared WebSocket reference (set by SmplClient)
    private volatile SharedWebSocket sharedWs;
    private final Consumer<Map<String, Object>> flagChangedHandler;
    private final Consumer<Map<String, Object>> flagDeletedHandler;

    /**
     * Creates a new FlagsClient.
     */
    public FlagsClient(FlagsApi flagsApi, HttpClient httpClient, String apiKey,
                       String flagsBaseUrl, String appBaseUrl, Duration timeout) {
        this.flagsApi = flagsApi;
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
    }

    /** Package-private test constructor. */
    FlagsClient() {
        this.flagsApi = null;
        this.httpClient = null;
        this.apiKey = null;
        this.flagsBaseUrl = null;
        this.appBaseUrl = null;
        this.timeout = null;
        this.contextFlushExecutor = null;
        this.flagChangedHandler = this::handleFlagChanged;
        this.flagDeletedHandler = this::handleFlagDeleted;
    }

    public void setSharedWs(SharedWebSocket ws) {
        this.sharedWs = ws;
    }

    /** Sets the parent service name for automatic context injection. */
    public void setParentService(String service) {
        this.parentService = service;
    }

    // -----------------------------------------------------------------------
    // Management — Flag CRUD
    // -----------------------------------------------------------------------

    /**
     * Creates a new feature flag.
     */
    public FlagResource create(CreateFlagParams params) {
        try {
            Flag attrs = new Flag();
            attrs.setKey(params.key());
            attrs.setName(params.name());
            attrs.setType(params.type().name());
            attrs.setDefault(params.defaultValue());
            if (params.description() != null) {
                attrs.setDescription(params.description());
            }
            if (params.values() != null) {
                List<FlagValue> fvs = new ArrayList<>();
                for (Map<String, Object> v : params.values()) {
                    FlagValue fv = new FlagValue();
                    fv.setName((String) v.get("name"));
                    fv.setValue(v.get("value"));
                    fvs.add(fv);
                }
                attrs.setValues(fvs);
            } else if (params.type() == FlagType.BOOLEAN) {
                attrs.setValues(List.of(
                        new FlagValue().name("True").value(true),
                        new FlagValue().name("False").value(false)
                ));
            }

            ResourceFlag data = new ResourceFlag().type("flag").attributes(attrs);
            ResponseFlag body = new ResponseFlag().data(data);
            FlagResponse response = flagsApi.createFlag(body);
            return parseResponse(response);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Fetches a single flag by UUID.
     */
    public FlagResource get(String flagId) {
        try {
            FlagResponse response = flagsApi.getFlag(UUID.fromString(flagId));
            return parseResponse(response);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Lists all flags for the account.
     */
    public List<FlagResource> list() {
        try {
            FlagListResponse response = flagsApi.listFlags(null, null);
            return parseListResponse(response);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Deletes a flag by UUID.
     */
    public void delete(String flagId) {
        try {
            flagsApi.deleteFlag(UUID.fromString(flagId));
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Updates an existing flag.
     */
    FlagResource updateFlag(FlagResource flag, UpdateFlagParams params) {
        try {
            // Re-fetch to avoid stale data
            FlagResource current = get(flag.id());

            Flag attrs = new Flag();
            attrs.setKey(current.key());
            attrs.setName(params.name() != null ? params.name() : current.name());
            attrs.setType(current.type());
            attrs.setDefault(params.defaultValue() != null ? params.defaultValue() : current.defaultValue());
            if (params.description() != null) {
                attrs.setDescription(params.description());
            } else if (current.description() != null) {
                attrs.setDescription(current.description());
            }
            if (params.values() != null) {
                List<FlagValue> fvs = new ArrayList<>();
                for (Map<String, Object> v : params.values()) {
                    FlagValue fv = new FlagValue();
                    fv.setName((String) v.get("name"));
                    fv.setValue(v.get("value"));
                    fvs.add(fv);
                }
                attrs.setValues(fvs);
            } else {
                List<FlagValue> fvs = new ArrayList<>();
                for (Map<String, Object> v : current.values()) {
                    FlagValue fv = new FlagValue();
                    fv.setName((String) v.get("name"));
                    fv.setValue(v.get("value"));
                    fvs.add(fv);
                }
                attrs.setValues(fvs);
            }
            if (params.environments() != null) {
                attrs.setEnvironments(buildEnvironments(params.environments()));
            } else {
                attrs.setEnvironments(buildEnvironments(current.environments()));
            }

            ResourceFlag data = new ResourceFlag().id(current.id()).type("flag").attributes(attrs);
            ResponseFlag body = new ResponseFlag().data(data);
            FlagResponse response = flagsApi.updateFlag(UUID.fromString(current.id()), body);
            return parseResponse(response);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Adds a rule to a flag for the specified environment.
     */
    @SuppressWarnings("unchecked")
    FlagResource addRuleToFlag(FlagResource flag, Map<String, Object> builtRule) {
        // Re-fetch for freshness
        FlagResource current = get(flag.id());

        String targetEnv = (String) builtRule.get("environment");
        if (targetEnv == null) {
            throw new SmplValidationException("Rule must specify an environment", null);
        }

        Map<String, Object> envs = new HashMap<>(current.environments());
        Map<String, Object> envData = envs.containsKey(targetEnv)
                ? new HashMap<>((Map<String, Object>) envs.get(targetEnv))
                : new HashMap<>();

        List<Map<String, Object>> rules = envData.containsKey("rules")
                ? new ArrayList<>((List<Map<String, Object>>) envData.get("rules"))
                : new ArrayList<>();

        // Strip "environment" key from the rule before storing
        Map<String, Object> ruleToStore = new HashMap<>(builtRule);
        ruleToStore.remove("environment");
        rules.add(ruleToStore);
        envData.put("rules", rules);
        envs.put(targetEnv, envData);

        return updateFlag(current, UpdateFlagParams.builder().environments(envs).build());
    }

    // -----------------------------------------------------------------------
    // Management — Context Types
    // -----------------------------------------------------------------------

    /**
     * Creates a context type.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createContextType(String key, Map<String, Object> options) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", key);
        if (options != null) {
            if (options.containsKey("name")) attrs.put("name", options.get("name"));
            if (options.containsKey("attributes")) attrs.put("attributes", options.get("attributes"));
        }
        Map<String, Object> data = Map.of("data", Map.of("type", "context_type", "attributes", attrs));
        String responseBody = doAppRequest("POST", "/api/v1/context_types", data);
        try {
            Map<String, Object> resp = OBJECT_MAPPER.readValue(responseBody, new TypeReference<>() {});
            return (Map<String, Object>) resp.get("data");
        } catch (Exception e) {
            throw new SmplException("Failed to parse context type response", 0, responseBody);
        }
    }

    /**
     * Updates a context type.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateContextType(String id, Map<String, Object> options) {
        Map<String, Object> attrs = new HashMap<>(options);
        Map<String, Object> data = Map.of("data", Map.of("type", "context_type", "id", id, "attributes", attrs));
        String responseBody = doAppRequest("PUT", "/api/v1/context_types/" + id, data);
        try {
            Map<String, Object> resp = OBJECT_MAPPER.readValue(responseBody, new TypeReference<>() {});
            return (Map<String, Object>) resp.get("data");
        } catch (Exception e) {
            throw new SmplException("Failed to parse context type response", 0, responseBody);
        }
    }

    /**
     * Lists all context types.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listContextTypes() {
        String responseBody = doAppRequest("GET", "/api/v1/context_types", null);
        try {
            Map<String, Object> resp = OBJECT_MAPPER.readValue(responseBody, new TypeReference<>() {});
            Object data = resp.get("data");
            return data instanceof List ? (List<Map<String, Object>>) data : List.of();
        } catch (Exception e) {
            throw new SmplException("Failed to parse context types response", 0, responseBody);
        }
    }

    /**
     * Deletes a context type.
     */
    public void deleteContextType(String id) {
        doAppRequest("DELETE", "/api/v1/context_types/" + id, null);
    }

    /**
     * Lists contexts for a given context type key.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listContexts(String contextTypeKey) {
        String responseBody = doAppRequest("GET",
                "/api/v1/contexts?filter[context_type]=" + contextTypeKey, null);
        try {
            Map<String, Object> resp = OBJECT_MAPPER.readValue(responseBody, new TypeReference<>() {});
            Object data = resp.get("data");
            return data instanceof List ? (List<Map<String, Object>>) data : List.of();
        } catch (Exception e) {
            throw new SmplException("Failed to parse contexts response", 0, responseBody);
        }
    }

    // -----------------------------------------------------------------------
    // Prescriptive tier — Flag handles
    // -----------------------------------------------------------------------

    /**
     * Declares a boolean flag handle.
     */
    public FlagHandle<Boolean> boolFlag(String key, boolean defaultValue) {
        FlagHandle<Boolean> handle = new FlagHandle<>(key, defaultValue, Boolean.class);
        handle.setNamespace(this);
        handles.put(key, handle);
        return handle;
    }

    /**
     * Declares a string flag handle.
     */
    public FlagHandle<String> stringFlag(String key, String defaultValue) {
        FlagHandle<String> handle = new FlagHandle<>(key, defaultValue, String.class);
        handle.setNamespace(this);
        handles.put(key, handle);
        return handle;
    }

    /**
     * Declares a number flag handle.
     */
    public FlagHandle<Number> numberFlag(String key, Number defaultValue) {
        FlagHandle<Number> handle = new FlagHandle<>(key, defaultValue, Number.class);
        handle.setNamespace(this);
        handles.put(key, handle);
        return handle;
    }

    /**
     * Declares a JSON flag handle.
     */
    @SuppressWarnings("unchecked")
    public FlagHandle<Object> jsonFlag(String key, Object defaultValue) {
        FlagHandle<Object> handle = new FlagHandle<>(key, defaultValue, Object.class);
        handle.setNamespace(this);
        handles.put(key, handle);
        return handle;
    }

    /**
     * Sets the context provider. Called on each evaluation to obtain contexts.
     */
    public void setContextProvider(Supplier<List<Context>> provider) {
        this.contextProvider = provider;
    }

    // -----------------------------------------------------------------------
    // Prescriptive tier — Connect / Disconnect / Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Internal connect called by SmplClient.connect(). Fetches all flag definitions
     * and registers WebSocket listeners.
     *
     * @param environment the target environment
     */
    /** @hidden Internal — called by SmplClient.connect(). */
    public void connectInternal(String environment) {
        this.environment = Objects.requireNonNull(environment);
        connectionStatus = "connecting";

        // Fetch all flags
        fetchAllFlags();

        // Clear cache
        resolutionCache.clear();

        // Register on shared WebSocket
        SharedWebSocket ws = this.sharedWs;
        if (ws != null) {
            ws.on("flag_changed", flagChangedHandler);
            ws.on("flag_deleted", flagDeletedHandler);
        }

        connected = true;
        connectionStatus = "connected";

        // Start context flush scheduler
        if (contextFlushExecutor != null && contextFlushFuture == null) {
            contextFlushFuture = contextFlushExecutor.scheduleAtFixedRate(
                    this::flushContextsSafe, 30, 30, TimeUnit.SECONDS);
        }
    }

    /**
     * Disconnects from the flags runtime.
     */
    public void disconnect() {
        connected = false;
        connectionStatus = "disconnected";

        // Flush remaining contexts
        flushContextsSafe();

        // Stop flush scheduler
        if (contextFlushFuture != null) {
            contextFlushFuture.cancel(false);
            contextFlushFuture = null;
        }

        // Unregister from shared WebSocket
        SharedWebSocket ws = this.sharedWs;
        if (ws != null) {
            ws.off("flag_changed", flagChangedHandler);
            ws.off("flag_deleted", flagDeletedHandler);
        }

        flagStore.clear();
        resolutionCache.clear();
    }

    /**
     * Forces a refresh of all flag definitions.
     */
    public void refresh() {
        fetchAllFlags();
        resolutionCache.clear();
        fireAllChangeListeners("manual");
    }

    /**
     * Returns the current connection status.
     */
    public String connectionStatus() {
        return connectionStatus;
    }

    /**
     * Returns diagnostic statistics.
     */
    public FlagStats stats() {
        return new FlagStats(cacheHits.get(), cacheMisses.get());
    }

    // -----------------------------------------------------------------------
    // Prescriptive tier — Context registration
    // -----------------------------------------------------------------------

    /**
     * Registers one or more contexts for tracking.
     */
    public void register(Context... contexts) {
        for (Context ctx : contexts) {
            observeContext(ctx);
        }
    }

    /**
     * Flushes pending context registrations to the server.
     */
    public void flushContexts() {
        List<Map<String, Object>> batch = drainPendingContexts();
        if (batch.isEmpty()) return;

        Map<String, Object> body = Map.of("contexts", batch);
        try {
            doAppRequest("POST", "/api/v1/contexts/bulk", body);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Context flush failed", e);
        }
    }

    // -----------------------------------------------------------------------
    // Prescriptive tier — Change listeners
    // -----------------------------------------------------------------------

    /**
     * Registers a global change listener that fires for any flag change.
     */
    public void onChange(Consumer<FlagChangeEvent> listener) {
        listeners.add(new ListenerEntry(null, listener));
    }

    /**
     * Registers a flag-specific change listener.
     */
    void onFlagChange(String key, Consumer<FlagChangeEvent> listener) {
        listeners.add(new ListenerEntry(key, listener));
    }

    // -----------------------------------------------------------------------
    // Prescriptive tier — Tier 1 evaluate (stateless)
    // -----------------------------------------------------------------------

    /**
     * Stateless flag evaluation via HTTP. Does not require connect().
     */
    @SuppressWarnings("unchecked")
    public Object evaluate(String key, String evaluateEnvironment, List<Context> contexts) {
        // Build eval data
        Map<String, Object> evalData = buildEvalData(contexts);

        // Fetch the flag
        List<FlagResource> allFlags;
        try {
            FlagListResponse response = flagsApi.listFlags(key, null);
            allFlags = parseListResponse(response);
        } catch (ApiException e) {
            throw mapException(e);
        }
        if (allFlags.isEmpty()) return null;

        FlagResource flag = allFlags.get(0);
        Map<String, Object> flagData = flagToStoreEntry(flag);
        return evaluateFlag(key, flagData, evaluateEnvironment, evalData);
    }

    // -----------------------------------------------------------------------
    // Internal — Evaluation engine
    // -----------------------------------------------------------------------

    /**
     * Called by FlagHandle.get() to perform local evaluation.
     */
    @SuppressWarnings("unchecked")
    Object evaluateHandle(String key, Object defaultValue, List<Context> contexts) {
        if (!connected) {
            throw new SmplNotConnectedException();
        }

        Map<String, Object> flagData = flagStore.get(key);
        if (flagData == null) return defaultValue;

        // Get contexts from provider or explicit override
        List<Context> ctxList;
        if (contexts != null) {
            ctxList = contexts;
        } else if (contextProvider != null) {
            ctxList = contextProvider.get();
        } else {
            ctxList = List.of();
        }

        // Observe contexts for registration
        for (Context ctx : ctxList) {
            observeContext(ctx);
        }

        // Check if buffer needs flushing
        if (pendingContexts.size() >= CONTEXT_BATCH_FLUSH_SIZE) {
            Thread flushThread = new Thread(this::flushContextsSafe, "smplkit-flags-ctx-flush-eager");
            flushThread.setDaemon(true);
            flushThread.start();
        }

        // Build eval data
        Map<String, Object> evalData = buildEvalData(ctxList);

        // Check cache
        String cacheKey = key + ":" + hashContexts(ctxList);
        Object cached = resolutionCache.get(cacheKey);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached == CACHE_NULL_SENTINEL ? defaultValue : cached;
        }
        cacheMisses.incrementAndGet();

        // Evaluate
        Object result = evaluateFlag(key, flagData, environment, evalData);
        if (result == null) {
            resolutionCache.put(cacheKey, CACHE_NULL_SENTINEL);
            return defaultValue;
        }
        resolutionCache.put(cacheKey, result);
        return result;
    }

    private static final Object CACHE_NULL_SENTINEL = new Object();

    /**
     * Core evaluation per ADR-022 §2.6.
     */
    @SuppressWarnings("unchecked")
    private Object evaluateFlag(String key, Map<String, Object> flagData,
                                String env, Map<String, Object> evalData) {
        Object flagDefault = flagData.get("default");
        Map<String, Object> environments = (Map<String, Object>) flagData.get("environments");
        if (environments == null || !environments.containsKey(env)) {
            return flagDefault;
        }

        Map<String, Object> envData = (Map<String, Object>) environments.get(env);

        // Check if environment is disabled
        Boolean enabled = (Boolean) envData.get("enabled");
        if (enabled == null || !enabled) {
            Object envDefault = envData.get("default");
            return envDefault != null ? envDefault : flagDefault;
        }

        // Iterate rules — first match wins
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

        // No match — return env default or flag default
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
    // Internal — Data helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> buildEvalData(List<Context> contexts) {
        Map<String, Object> evalData = new HashMap<>();
        if (contexts != null) {
            for (Context ctx : contexts) {
                evalData.put(ctx.type(), ctx.toEvalDict());
            }
        }
        // Auto-inject service context if configured and not already provided
        if (parentService != null && !evalData.containsKey("service")) {
            evalData.put("service", Map.of("key", parentService));
        }
        return evalData;
    }

    private String hashContexts(List<Context> contexts) {
        if (contexts == null || contexts.isEmpty()) return "empty";
        Map<String, Object> evalData = buildEvalData(contexts);
        StringBuilder sb = new StringBuilder();
        // Use toString-based hashing to avoid checked exceptions from ObjectMapper/MessageDigest
        for (Map.Entry<String, Object> entry : new java.util.TreeMap<>(evalData).entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return String.valueOf(sb.toString().hashCode());
    }

    private void observeContext(Context ctx) {
        String compositeKey = ctx.type() + ":" + ctx.key();
        if (contextBuffer.containsKey(compositeKey)) return;

        Map<String, Object> entry = new HashMap<>();
        entry.put("id", ctx.type() + ":" + ctx.key());
        entry.put("name", ctx.name() != null ? ctx.name() : ctx.key());
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
        // flushContexts() handles its own exceptions internally
        flushContexts();
    }

    private void fetchAllFlags() {
        List<FlagResource> flags = list();
        flagStore.clear();
        for (FlagResource flag : flags) {
            flagStore.put(flag.key(), flagToStoreEntry(flag));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flagToStoreEntry(FlagResource flag) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("key", flag.key());
        entry.put("name", flag.name());
        entry.put("type", flag.type());
        entry.put("default", flag.defaultValue());
        entry.put("values", flag.values());
        entry.put("description", flag.description());
        entry.put("environments", flag.environments());
        return entry;
    }

    // -----------------------------------------------------------------------
    // Internal — WebSocket handlers
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
        for (String flagKey : flagStore.keySet()) {
            FlagChangeEvent event = new FlagChangeEvent(flagKey, source);
            for (ListenerEntry entry : listeners) {
                if (entry.key != null && !entry.key.equals(flagKey)) continue;
                try {
                    entry.listener.accept(event);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Exception in onChange listener for flag '" + flagKey + "'", e);
                }
            }
        }
        // Also fire for handles that are registered but not in store
        for (String handleKey : handles.keySet()) {
            if (!flagStore.containsKey(handleKey)) {
                FlagChangeEvent event = new FlagChangeEvent(handleKey, source);
                for (ListenerEntry entry : listeners) {
                    if (entry.key != null && !entry.key.equals(handleKey)) continue;
                    try {
                        entry.listener.accept(event);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Exception in onChange listener for flag '" + handleKey + "'", e);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal — HTTP helpers for app service (context types, contexts)
    // -----------------------------------------------------------------------

    private String doAppRequest(String method, String path, Object body) {
        try {
            String url = appBaseUrl + path;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");

            if (timeout != null) {
                builder.timeout(timeout);
            }

            switch (method) {
                case "GET" -> builder.GET();
                case "DELETE" -> builder.DELETE();
                case "POST" -> builder.method("POST", HttpRequest.BodyPublishers.ofString(
                        OBJECT_MAPPER.writeValueAsString(body)));
                case "PUT" -> builder.method("PUT", HttpRequest.BodyPublishers.ofString(
                        OBJECT_MAPPER.writeValueAsString(body)));
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status >= 400) {
                String msg = "HTTP " + status;
                String respBody = response.body();
                throw switch (status) {
                    case 404 -> new SmplNotFoundException(msg, respBody);
                    case 409 -> new SmplConflictException(msg, respBody);
                    case 422 -> new SmplValidationException(msg, respBody);
                    default -> new SmplException(msg, status, respBody);
                };
            }
            return response.body();
        } catch (SmplException e) {
            throw e;
        } catch (Exception e) {
            throw new SmplException("Request to app service failed: " + e.getMessage(), 0, null, e);
        }
    }

    // -----------------------------------------------------------------------
    // Internal — Response parsing
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private FlagResource parseResponse(FlagResponse response) {
        Map<String, Object> resp = OBJECT_MAPPER.convertValue(response, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        return parseFlagData(data);
    }

    @SuppressWarnings("unchecked")
    private List<FlagResource> parseListResponse(FlagListResponse response) {
        Map<String, Object> resp = OBJECT_MAPPER.convertValue(response, new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("data");
        if (items == null) return List.of();
        List<FlagResource> result = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            result.add(parseFlagData(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private FlagResource parseFlagData(Map<String, Object> data) {
        String id = (String) data.get("id");
        Map<String, Object> attrs = (Map<String, Object>) data.get("attributes");
        if (attrs == null) attrs = data; // Some responses may flatten

        String key = (String) attrs.get("key");
        String name = (String) attrs.get("name");
        String description = (String) attrs.get("description");
        String type = (String) attrs.get("type");
        Object defaultValue = attrs.get("default");
        List<Map<String, Object>> values = (List<Map<String, Object>>) attrs.get("values");
        Map<String, Object> environments = (Map<String, Object>) attrs.get("environments");

        Instant createdAt = parseInstant(attrs.get("created_at"));
        Instant updatedAt = parseInstant(attrs.get("updated_at"));

        FlagResource resource = new FlagResource(
                id != null ? id : "", key != null ? key : "", name != null ? name : "",
                description, type != null ? type : "", defaultValue,
                values, environments, createdAt, updatedAt);
        resource.setClient(this);
        return resource;
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

    private static SmplException mapException(ApiException e) {
        int code = e.getCode();
        String body = e.getResponseBody();
        String msg = e.getMessage() != null ? e.getMessage() : "HTTP " + code;
        return switch (code) {
            case 404 -> new SmplNotFoundException(msg, body);
            case 409 -> new SmplConflictException(msg, body);
            case 422 -> new SmplValidationException(msg, body);
            default -> new SmplException(msg, code, body);
        };
    }

    /** Package-private: simulate a WebSocket flag_changed event (testing). */
    void simulateFlagChanged() {
        handleFlagChanged(Map.of());
    }

    /** Package-private: simulate a WebSocket flag_deleted event (testing). */
    void simulateFlagDeleted() {
        handleFlagDeleted(Map.of());
    }

    /** Package-private: test access to connected state. */
    boolean isConnected() {
        return connected;
    }

    private record ListenerEntry(String key, Consumer<FlagChangeEvent> listener) {}
}
