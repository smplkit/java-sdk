package com.smplkit.config;

import com.smplkit.SharedWebSocket;
import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.errors.NotFoundError;
import com.smplkit.errors.SmplError;
import com.smplkit.internal.Debug;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigCreateRequest;
import com.smplkit.internal.generated.config.model.ConfigCreateResource;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigRequest;
import com.smplkit.internal.generated.config.model.ConfigResponse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for the Smpl Config service.
 *
 * <p>Two ways to read config values:</p>
 * <ul>
 *   <li>{@link #bind(String, Object) bind(id, target)} — declarative,
 *       schema-first. Pass a Java object (POJO with mutable fields) or
 *       a {@link Map}; the SDK registers the schema and values, then
 *       mutates the <i>same</i> target in place when the server pushes
 *       updates. Property access on the target always sees the live
 *       resolved value.</li>
 *   <li>{@link #get(String)} — lookup-only escape hatch. Returns a
 *       {@link LiveConfigProxy} (a read-only, {@link Map}-like view of
 *       the resolved values). Throws {@link NotFoundError} on missing
 *       configs. Two- and three-argument overloads read a single value
 *       with optional default fallback and code-first auto-registration.</li>
 * </ul>
 *
 * <p>Management/CRUD lives on {@code client.manage().config}.</p>
 */
public final class ConfigClient {

    private static final Logger LOG = Logger.getLogger("smplkit.config");

    final ConfigsApi configsApi;
    private volatile boolean connected;
    private volatile String environment;
    private volatile String service;
    private volatile com.smplkit.MetricsReporter metrics;
    private Map<String, Map<String, Object>> configCache = new HashMap<>();
    /** Raw Config objects keyed by id, kept around so a single-config
     * change (WS event) can refetch one config and rebuild the resolved
     * cache for everyone — including descendants that inherit from the
     * changed config — without a full re-list. Mirrors Python's
     * {@code _raw_config_cache}. */
    private Map<String, Config> configStore = new HashMap<>();
    /** Identity-stable proxy cache: same id → same proxy instance across calls. */
    private final Map<String, LiveConfigProxy> proxyCache = new ConcurrentHashMap<>();
    /** Targets bound via {@link #bind} keyed by config id. Mutated in
     * place on WebSocket events. */
    private final Map<String, Object> bindings = new ConcurrentHashMap<>();
    private final List<ListenerEntry> listeners = Collections.synchronizedList(new ArrayList<>());
    private volatile ConfigManagement management;
    private volatile SharedWebSocket wsManager;
    private final Consumer<Map<String, Object>> configChangedHandler;
    private final Consumer<Map<String, Object>> configDeletedHandler;
    private final Consumer<Map<String, Object>> configsChangedHandler;

    /**
     * Creates a new ConfigClient. Use {@link com.smplkit.SmplClient} to obtain an instance.
     */
    public ConfigClient(ConfigsApi configsApi, java.net.http.HttpClient httpClient, String apiKey) {
        this.configsApi = configsApi;
        this.management = new ConfigManagement(this);
        this.configChangedHandler = this::handleConfigChanged;
        this.configDeletedHandler = this::handleConfigDeleted;
        this.configsChangedHandler = this::handleConfigsChanged;
    }

    // -----------------------------------------------------------------------
    // Management accessor
    // -----------------------------------------------------------------------

    /**
     * Returns the management-plane API for config CRUD operations.
     */
    public ConfigManagement management() {
        return management;
    }

    // -----------------------------------------------------------------------
    // Environment + dependencies
    // -----------------------------------------------------------------------

    /** Sets the target environment for config resolution. */
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /** Sets the owner service identifier (used to attribute discovery rows). */
    public void setService(String service) {
        this.service = service;
    }

    /** Sets the metrics reporter. */
    public void setMetrics(com.smplkit.MetricsReporter metrics) {
        this.metrics = metrics;
    }

    /** Sets the shared WebSocket for real-time config updates. */
    public void setSharedWs(SharedWebSocket ws) {
        this.wsManager = ws;
    }

    /**
     * Swap this client's {@link ConfigManagement} (and its registration
     * buffer) for an external one — used by {@link com.smplkit.SmplClient}
     * to share a single buffer with {@code client.manage().config}. With
     * a shared buffer, declarations queued by {@link #bind} and
     * {@link #get(String, String, Object)} drain whenever <em>either</em>
     * side calls {@code flush()}.
     */
    public void setManagement(ConfigManagement management) {
        this.management = management;
    }

    // -----------------------------------------------------------------------
    // Internal: create / update (called by Config.save())
    // -----------------------------------------------------------------------

    /** Creates a new config on the server. Called by {@link Config#save()}. */
    Config _createConfig(Config config) {
        try {
            var attrs = new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(config.getName());
            if (config.getDescription() != null) {
                attrs.setDescription(config.getDescription());
            }
            if (config.getParent() != null) {
                attrs.setParent(config.getParent());
            }
            if (config.getItems() != null && !config.getItems().isEmpty()) {
                attrs.setItems(wrapValuesAsItems(config.getResolvedItems()));
            }
            if (config.getEnvironments() != null && !config.getEnvironments().isEmpty()) {
                attrs.setEnvironments(wrapEnvironments(config.getEnvironments()));
            }

            // Create uses a dedicated envelope where the caller-supplied id is required.
            ConfigCreateResource data = new ConfigCreateResource()
                    .id(config.getId())
                    .type(ConfigCreateResource.TypeEnum.CONFIG)
                    .attributes(attrs);
            ConfigCreateRequest body = new ConfigCreateRequest().data(data);

            ConfigResponse response = configsApi.createConfig(body);
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Updates an existing config on the server. Called by {@link Config#save()}. */
    Config _updateConfig(Config config) {
        try {
            var attrs = new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(config.getName());
            if (config.getDescription() != null) {
                attrs.setDescription(config.getDescription());
            }
            if (config.getParent() != null) {
                attrs.setParent(config.getParent());
            }
            if (config.getItems() != null) {
                attrs.setItems(wrapValuesAsItems(config.getResolvedItems()));
            }
            if (config.getEnvironments() != null) {
                attrs.setEnvironments(wrapEnvironments(config.getEnvironments()));
            }

            ConfigResource data = new ConfigResource()
                    .id(config.getId())
                    .type(ConfigResource.TypeEnum.CONFIG)
                    .attributes(attrs);
            ConfigRequest body = new ConfigRequest().data(data);

            ConfigResponse response = configsApi.updateConfig(config.getId(), body);
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Public API: bind, get, start
    // -----------------------------------------------------------------------

    /**
     * Bind a target (POJO or {@link Map}) to a config id; return the same
     * target back, live. Convenience for {@link #bind(String, Object, Object)}
     * with no parent.
     */
    public <T> T bind(String id, T target) {
        return bind(id, target, null);
    }

    /**
     * Bind a target to a config id with an optional parent reference.
     *
     * <p>Declarative, code-first API. The target's fields (or Map keys) are
     * the schema; their values are the in-code defaults. On first call:</p>
     * <ol>
     *   <li>Every leaf (recursively through nested POJO fields and nested
     *       {@link Map} entries) is registered with the server as a config
     *       item, with its value as the in-code default and a type inferred
     *       from {@code instanceof}.</li>
     *   <li>After the SDK's cache is populated, any server-side overrides
     *       for this config are applied to the target in place.</li>
     * </ol>
     *
     * <p>On every WebSocket-delivered change thereafter the target is
     * mutated in place — readers of {@code target.foo} always see the
     * current resolved value. The returned reference is the same one you
     * passed in (referential identity preserved).</p>
     *
     * <p>Idempotent. Repeated calls with the same id return the
     * originally-bound target; the new {@code target} argument is ignored.</p>
     *
     * <p><strong>POJO vs. Map.</strong> Use a {@link Map} when you want
     * omit-to-inherit semantics — keys present are explicit overrides,
     * absent keys inherit from the parent. POJO targets register every
     * non-static, non-transient field as explicit (Java does not preserve
     * a "did the caller construct this field explicitly?" bit, so the
     * field set is the override set).</p>
     *
     * <p><strong>Mutability.</strong> Bound POJOs must expose mutable
     * fields (the SDK assigns via {@code Field.setAccessible(true)} +
     * {@code Field.set}). {@code final} fields and {@code record}
     * components cannot be live-updated; bind them with a {@link Map}
     * instead, or use a mutable holder.</p>
     *
     * @param id     the config id to register under
     * @param target a POJO with mutable fields or a {@link Map} carrying
     *               the in-code defaults
     * @param parent optional — a target previously returned from
     *               {@link #bind} on this client; activates parent-chain
     *               inheritance for keys this target omits
     * @param <T>    inferred from {@code target}; the return is the same
     *               reference passed in
     * @return the same {@code target} reference, registered and live
     * @throws IllegalArgumentException if {@code target} is null, or if
     *         {@code parent} is non-null but was not previously bound
     */
    public <T> T bind(String id, T target, Object parent) {
        if (target == null) {
            throw new IllegalArgumentException("bind() requires a non-null target");
        }

        String parentId = null;
        if (parent != null) {
            parentId = configIdForBinding(parent);
            if (parentId == null) {
                throw new IllegalArgumentException(
                        "bind(): parent must be a target previously returned from bind(). "
                                + "Bind the parent first.");
            }
        }

        // Derive a console display name from the class (for POJO targets)
        // or leave null (Map targets carry no class metadata).
        String name = null;
        String description = null;
        if (!(target instanceof Map)) {
            String simple = target.getClass().getSimpleName();
            if (simple != null && !simple.isEmpty()) name = simple;
        }
        _observeConfigDeclaration(id, parentId, name, description);

        // Walk the target shape and register every leaf.
        List<Item> items = new ArrayList<>();
        iterTargetItemsInto(target, "", items);
        for (Item item : items) {
            _observeItemDeclaration(id, item.key, item.type, item.value, null);
        }

        // Race-safe: only one putIfAbsent winner per id; losers reuse the
        // existing binding (the buffer dedups duplicate item declarations).
        Object prior = bindings.putIfAbsent(id, target);
        if (prior != null) {
            @SuppressWarnings("unchecked")
            T existingBound = (T) prior;
            return existingBound;
        }

        _connectInternal();
        syncTargetFromCache(target, id);
        return target;
    }

    /**
     * Eagerly initialize the config subclient — fetch all configs, resolve
     * the environment-scoped values into the local cache, and subscribe to
     * the shared WebSocket for live updates. Idempotent. Called automatically
     * on first {@link #get} / {@link #bind}.
     */
    public void start() {
        _connectInternal();
    }

    /**
     * Returns a {@link LiveConfigProxy} — a read-only, {@link Map}-like
     * view of {@code id}'s resolved values. The proxy is identity-stable:
     * calling {@code get(id)} twice returns the same instance.
     *
     * @throws NotFoundError if {@code id} is not in the cache
     */
    public LiveConfigProxy get(String id) {
        _connectInternal();
        if (!configCache.containsKey(id)) {
            throw new NotFoundError(
                    "Config with id '" + id + "' not found in cache.", null);
        }
        if (metrics != null) {
            metrics.record("config.resolutions", "resolutions", Map.of("config", id));
        }
        return cachedProxy(id);
    }

    /**
     * Read a single value from {@code id}. Throws {@link NotFoundError}
     * if the config or the key is missing. No registration.
     *
     * <p>For typed/declarative access, use {@link #bind} instead.</p>
     */
    public Object get(String id, String key) {
        _connectInternal();
        if (!configCache.containsKey(id)) {
            throw new NotFoundError(
                    "Config with id '" + id + "' not found in cache.", null);
        }
        Map<String, Object> values = configCache.get(id);
        if (!values.containsKey(key)) {
            throw new NotFoundError(
                    "Config item '" + key + "' not found in config '" + id + "'.", null);
        }
        return values.get(key);
    }

    /**
     * Read a single value, falling back to {@code defaultValue} when the
     * config or the key is missing. Never throws.
     *
     * <p><strong>Side effect:</strong> registers the config (if new) and
     * the key (with {@code defaultValue} as its default value) for
     * code-first console observability. The buffer is idempotent at the
     * {@code (configId, itemKey)} level, so repeat calls do not pile up.</p>
     */
    public Object get(String id, String key, Object defaultValue) {
        _connectInternal();
        // Register the reference so code-declared keys appear in the console
        // alongside bind()-declared ones.
        _observeConfigDeclaration(id, null, null, null);
        _observeItemDeclaration(id, key, inferItemType(defaultValue), defaultValue, null);

        if (!configCache.containsKey(id)) return defaultValue;
        Map<String, Object> values = configCache.get(id);
        return values.containsKey(key) ? values.get(key) : defaultValue;
    }

    // -----------------------------------------------------------------------
    // Binding helpers (internal)
    // -----------------------------------------------------------------------

    /** Return the config id this target was bound under, or null. */
    private String configIdForBinding(Object target) {
        for (Map.Entry<String, Object> e : bindings.entrySet()) {
            if (e.getValue() == target) return e.getKey();
        }
        return null;
    }

    /** Apply current cached values to a freshly-bound target in place. */
    private void syncTargetFromCache(Object target, String configId) {
        Map<String, Object> cache = configCache.get(configId);
        if (cache == null || cache.isEmpty()) return;
        for (Map.Entry<String, Object> e : cache.entrySet()) {
            applyChangeToTarget(target, e.getKey(), e.getValue());
        }
    }

    private LiveConfigProxy cachedProxy(String id) {
        return proxyCache.computeIfAbsent(id, k -> new LiveConfigProxy(this, k));
    }

    /** Internal: queue a config declaration with the management buffer. */
    void _observeConfigDeclaration(String configId, String parent, String name, String description) {
        management.registerConfig(configId, service, environment, parent, name, description);
    }

    /** Internal: queue a config item declaration with the management buffer. */
    public void _observeItemDeclaration(String configId, String itemKey, String itemType,
                                        Object defaultValue, String description) {
        management.registerConfigItem(configId, itemKey, itemType, defaultValue, description);
    }

    /** A single flattened leaf in a bound target. */
    private record Item(String key, String type, Object value) {}

    /**
     * Map a runtime value (bind value or get default) to a config item type.
     * Mirrors the python / typescript inference: {@code boolean → BOOLEAN},
     * any {@link Number} → {@code NUMBER}, {@link CharSequence} → {@code STRING},
     * everything else → {@code STRING} (safest fallback — admins can retype
     * to {@code JSON}, {@code NUMBER}, or {@code BOOLEAN} in the console).
     *
     * <p>{@code Boolean} is checked before {@code Number} for symmetry with
     * other SDKs (in Python, {@code bool} is a subclass of {@code int}).</p>
     */
    private static String inferItemType(Object value) {
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Number) return "NUMBER";
        if (value instanceof CharSequence) return "STRING";
        return "STRING";
    }

    /**
     * Walk a bound target (POJO or {@link Map}) and append every leaf to
     * {@code out} as a dotted-key {@code Item}. Nested {@link Map} entries
     * and nested POJO fields are descended into; primitives, strings,
     * collections, dates, and other JDK built-ins are treated as opaque
     * leaves.
     */
    private static void iterTargetItemsInto(Object obj, String prefix, List<Item> out) {
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String flat = prefix + key;
                Object value = e.getValue();
                if (value instanceof Map<?, ?>) {
                    iterTargetItemsInto(value, flat + ".", out);
                } else {
                    out.add(new Item(flat, inferItemType(value), value));
                }
            }
            return;
        }
        for (Field field : allInstanceFields(obj.getClass())) {
            Object value = readField(field, obj);
            String flat = prefix + field.getName();
            if (isNestedNamespace(value)) {
                iterTargetItemsInto(value, flat + ".", out);
            } else {
                out.add(new Item(flat, inferItemType(value), value));
            }
        }
    }

    /**
     * True when {@code value} is a nested namespace (Map or user POJO) we
     * should descend into; false for leaves. JDK built-ins (collections,
     * dates, arrays, primitives) are leaves so we don't accidentally
     * dot-flatten a complex object's internals into config keys.
     */
    private static boolean isNestedNamespace(Object value) {
        if (value == null) return false;
        if (value instanceof Map<?, ?>) return true;
        if (value instanceof CharSequence) return false;
        if (value instanceof Number) return false;
        if (value instanceof Boolean) return false;
        if (value instanceof Character) return false;
        if (value instanceof Enum<?>) return false;
        if (value instanceof Iterable<?>) return false;
        if (value instanceof java.time.temporal.Temporal) return false;
        if (value instanceof java.util.Date) return false;
        Class<?> c = value.getClass();
        if (c.isArray()) return false;
        if (c.isPrimitive()) return false;
        // JDK built-ins (java.*, javax.*) are always leaves — only descend
        // into user-defined classes.
        String n = c.getName();
        if (n.startsWith("java.") || n.startsWith("javax.")) return false;
        return true;
    }

    private static List<Field> allInstanceFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
                if (f.isSynthetic()) continue;
                out.add(f);
            }
            c = c.getSuperclass();
        }
        return out;
    }

    /**
     * Apply a server-pushed value to a bound target in place. Walks the
     * dotted key path to the leaf's parent, then assigns the value via
     * {@link Map#put} or reflection. Bails silently if any intermediate
     * is missing or the target field cannot be assigned (e.g. {@code final}
     * fields the JVM refuses to mutate, or a primitive field receiving
     * {@code null}).
     */
    @SuppressWarnings("unchecked")
    static void applyChangeToTarget(Object target, String dottedKey, Object value) {
        String[] parts = dottedKey.split("\\.");
        Object current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            current = stepInto(current, parts[i]);
            if (current == null) return;
        }
        String last = parts[parts.length - 1];
        if (current instanceof Map<?, ?> leafMap) {
            ((Map<String, Object>) leafMap).put(last, value);
            return;
        }
        Field f = findField(current.getClass(), last);
        if (f == null) return;
        writeField(f, current, coerce(f.getType(), value));
    }

    /** Walk one step into a namespace. Returns null if the step cannot be
     *  taken (missing key/field) so the caller bails the dotted walk. */
    @SuppressWarnings("unchecked")
    private static Object stepInto(Object current, String part) {
        if (current instanceof Map<?, ?> mapNode) {
            if (!mapNode.containsKey(part)) return null;
            return ((Map<String, Object>) mapNode).get(part);
        }
        Field f = findField(current.getClass(), part);
        if (f == null) return null;
        return readField(f, current);
    }

    /** Read a field, bypassing access controls. Any reflective failure
     *  (including {@link IllegalAccessException} which would only occur in
     *  unusual JVM configurations after {@code setAccessible}) is wrapped
     *  as {@link IllegalStateException}. */
    static Object readField(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Could not read field " + field.getDeclaringClass().getName()
                            + "." + field.getName(), ex);
        }
    }

    /** Write a field, bypassing access controls. Silently skips and logs at
     *  FINE level on reflective failure (final fields the JVM refuses to
     *  mutate, type mismatch, primitive-null) so server pushes never crash
     *  the runtime. */
    static void writeField(Field field, Object owner, Object value) {
        try {
            field.setAccessible(true);
            field.set(owner, value);
        } catch (Exception ex) {
            LOG.log(Level.FINE,
                    "Could not write field " + field.getDeclaringClass().getName()
                            + "." + field.getName() + " = " + value, ex);
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Widen / narrow a server value into the target field's type. */
    private static Object coerce(Class<?> fieldType, Object value) {
        if (value == null) return null;
        if (fieldType.isInstance(value)) return value;
        if (value instanceof Number n) {
            if (fieldType == int.class || fieldType == Integer.class) return n.intValue();
            if (fieldType == long.class || fieldType == Long.class) return n.longValue();
            if (fieldType == double.class || fieldType == Double.class) return n.doubleValue();
            if (fieldType == float.class || fieldType == Float.class) return n.floatValue();
            if (fieldType == short.class || fieldType == Short.class) return n.shortValue();
            if (fieldType == byte.class || fieldType == Byte.class) return n.byteValue();
        }
        if ((fieldType == boolean.class || fieldType == Boolean.class) && value instanceof Boolean) {
            return value;
        }
        if (fieldType == String.class) return value.toString();
        return value;
    }

    // -----------------------------------------------------------------------
    // Runtime: refresh / change listeners
    // -----------------------------------------------------------------------

    /**
     * Refreshes all config values from the server and fires change listeners
     * for any values that changed.
     */
    public void refresh() {
        String env = this.environment;
        if (env == null) return;

        Map<String, Map<String, Object>> newCache = buildCache(env);
        Map<String, Map<String, Object>> oldCache = configCache;
        configCache = newCache;
        diffAndFire(oldCache, newCache, "manual");
    }

    /** Registers a global change listener. */
    public void onChange(Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(null, null, listener));
    }

    /** Registers a config-scoped change listener. */
    public void onChange(String configId, Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(configId, null, listener));
    }

    /** Registers an item-scoped change listener. */
    public void onChange(String configId, String itemKey, Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(configId, itemKey, listener));
    }

    /**
     * Initializes the config cache on first use. Idempotent.
     */
    void _connectInternal() {
        if (connected) return;
        String env = this.environment;
        if (env == null) return;

        Debug.log("websocket", "config runtime initializing");

        // Per ADR-037 §2.14: flush any buffered discovery declarations
        // BEFORE the initial fetch so newly-discovered configs appear in
        // the cache. The flush itself swallows network/server failures.
        management.flush();

        configCache = buildCache(env);

        SharedWebSocket ws = this.wsManager;
        if (ws != null) {
            Debug.log("registration", "registering config_changed, config_deleted, and configs_changed handlers");
            ws.on("config_changed", configChangedHandler);
            ws.on("config_deleted", configDeletedHandler);
            ws.on("configs_changed", configsChangedHandler);
            ws.ensureConnected(java.time.Duration.ofSeconds(10));
        }

        connected = true;
        Debug.log("websocket", "config runtime connected");
    }

    private void handleConfigChanged(Map<String, Object> data) {
        if (!connected) return;
        String configKey = data.get("id") instanceof String s ? s : null;
        if (configKey == null) {
            Debug.log("websocket", "config_changed event missing id, skipping");
            return;
        }
        Debug.log("websocket", "config_changed event received, key=" + configKey);

        String env = this.environment;
        if (env == null) return;

        // Refetch JUST the changed config (single GET — fast), update the
        // local raw store, then rebuild every config's resolved cache from
        // the store. This handles the cascade case: any config that has
        // `configKey` in its parent chain has a stale resolved value.
        Config fetched;
        try {
            fetched = management.get(configKey);
        } catch (Exception e) {
            Debug.log("websocket", "config_changed scoped fetch failed for key=" + configKey + ": " + e);
            return;
        }

        Map<String, Config> newStore = new HashMap<>(configStore);
        newStore.put(configKey, fetched);
        Map<String, Map<String, Object>> newCache = resolveAllFromStore(newStore, env);

        Map<String, Map<String, Object>> oldCache = configCache;
        configCache = newCache;
        configStore = newStore;

        diffAndFire(oldCache, newCache, "websocket");
    }

    private void handleConfigDeleted(Map<String, Object> data) {
        if (!connected) return;
        String configKey = data.get("id") instanceof String s ? s : null;
        if (configKey == null) {
            Debug.log("websocket", "config_deleted event missing id, skipping");
            return;
        }
        Debug.log("websocket", "config_deleted event received, key=" + configKey);

        Map<String, Map<String, Object>> oldCache = configCache;
        Map<String, Map<String, Object>> newCache = new HashMap<>(configCache);
        Map<String, Object> removed = newCache.remove(configKey);
        configCache = newCache;

        if (removed != null) {
            for (String itemKey : removed.keySet()) {
                ConfigChangeEvent event = new ConfigChangeEvent(configKey, itemKey,
                        removed.get(itemKey), null, "websocket", true);
                fireListenersFor(event);
            }
            if (removed.isEmpty()) {
                ConfigChangeEvent event = new ConfigChangeEvent(configKey, null,
                        null, null, "websocket", true);
                fireListenersFor(event);
            }
        }
    }

    private void fireListenersFor(ConfigChangeEvent event) {
        for (ListenerEntry entry : listeners) {
            if (entry.configId != null && !entry.configId.equals(event.configId())) continue;
            if (entry.itemKey != null && !entry.itemKey.equals(event.itemKey())) continue;
            try {
                entry.listener.accept(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                        "Exception in onChange listener for "
                                + event.configId() + "/" + event.itemKey(), e);
            }
        }
    }

    private void handleConfigsChanged(Map<String, Object> data) {
        if (!connected) return;
        Debug.log("websocket", "configs_changed event received");
        refresh();
    }

    // -----------------------------------------------------------------------
    // Internal: cache building
    // -----------------------------------------------------------------------

    private static final int RUNTIME_PAGE_SIZE = 1000;

    private Map<String, Map<String, Object>> buildCache(String env) {
        Map<String, Config> configById = new HashMap<>();
        int page = 1;
        while (true) {
            List<Config> rows = management.list(page, RUNTIME_PAGE_SIZE);
            for (Config cfg : rows) {
                if (cfg.getId() != null) {
                    configById.put(cfg.getId(), cfg);
                }
            }
            if (rows.size() < RUNTIME_PAGE_SIZE) break;
            page++;
        }
        configStore = configById;
        return resolveAllFromStore(configById, env);
    }

    /** Build the resolved cache from a pre-fetched config store keyed by id. */
    private Map<String, Map<String, Object>> resolveAllFromStore(
            Map<String, Config> store, String env) {
        Map<String, Map<String, Object>> newCache = new HashMap<>();
        for (Config cfg : store.values()) {
            List<Resolver.ChainEntry> chain = new ArrayList<>();
            chain.add(toChainEntry(cfg));
            Config current = cfg;
            while (current.getParent() != null && store.containsKey(current.getParent())) {
                Config parent = store.get(current.getParent());
                chain.add(toChainEntry(parent));
                current = parent;
            }
            newCache.put(cfg.getId(), Resolver.resolve(chain, env));
        }
        return newCache;
    }

    // -----------------------------------------------------------------------
    // Internal: diff and fire
    // -----------------------------------------------------------------------

    /** Fires change listeners (and updates bound targets) for any values
     *  that differ between old and new snapshots. */
    void diffAndFire(
            Map<String, Map<String, Object>> oldCache,
            Map<String, Map<String, Object>> newCache,
            String source
    ) {
        Set<String> allConfigIds = new HashSet<>();
        allConfigIds.addAll(oldCache.keySet());
        allConfigIds.addAll(newCache.keySet());

        for (String cfgId : allConfigIds) {
            Map<String, Object> oldItems = oldCache.getOrDefault(cfgId, Map.of());
            Map<String, Object> newItems = newCache.getOrDefault(cfgId, Map.of());

            Set<String> allItemKeys = new HashSet<>();
            allItemKeys.addAll(oldItems.keySet());
            allItemKeys.addAll(newItems.keySet());

            Object target = bindings.get(cfgId);

            for (String itemKey : allItemKeys) {
                Object oldVal = oldItems.get(itemKey);
                Object newVal = newItems.get(itemKey);
                if (Objects.equals(oldVal, newVal)) continue;

                // Apply to bound target first so listeners reading the
                // target see the new value.
                if (target != null) {
                    applyChangeToTarget(target, itemKey, newVal);
                }

                if (metrics != null) {
                    metrics.record("config.changes", "changes", Map.of("config", cfgId));
                }
                ConfigChangeEvent event = new ConfigChangeEvent(cfgId, itemKey, oldVal, newVal, source);
                fireListenersFor(event);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Package-private: cache access (for LiveConfigProxy)
    // -----------------------------------------------------------------------

    /** Returns resolved values for a config id. */
    Map<String, Object> _getResolvedCache(String id) {
        return configCache.getOrDefault(id, Map.of());
    }

    /** Package-private: check if connected (for testing). */
    boolean isConnected() {
        return connected;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static Resolver.ChainEntry toChainEntry(Config config) {
        Map<String, Object> resolvedItems = config.getResolvedItems();
        // Per ADR-024 §2.4 environments are flat: {env: {key: rawValue}}.
        // Pass straight through to the resolver — no wrapper extraction.
        Map<String, Map<String, Object>> envMap = new HashMap<>(config.getEnvironments());
        return new Resolver.ChainEntry(
                config.getId() != null ? config.getId() : "",
                resolvedItems,
                envMap);
    }

    /** Converts a server resource into the SDK's Config model. */
    Config parseResource(ConfigResource resource) {
        String id = resource.getId();
        var attrs = resource.getAttributes();

        String name = attrs.getName();
        String description = attrs.getDescription();
        String parent = attrs.getParent();

        Map<String, Object> items = new HashMap<>();
        Map<String, ConfigItemDefinition> rawItems = attrs.getItems();
        if (rawItems != null) {
            for (Map.Entry<String, ConfigItemDefinition> entry : rawItems.entrySet()) {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("value", entry.getValue().getValue());
                if (entry.getValue().getType() != null) {
                    itemData.put("type", entry.getValue().getType().getValue());
                }
                if (entry.getValue().getDescription() != null) {
                    itemData.put("description", entry.getValue().getDescription());
                }
                items.put(entry.getKey(), itemData);
            }
        }

        // Per ADR-024 §2.4 the wire shape is flat: {env: {key: rawValue}}.
        // The generated client already returns Map<String, Map<String, Object>>,
        // so this is a defensive shallow copy preserving the in-memory shape.
        Map<String, Map<String, Object>> environments = new HashMap<>();
        Map<String, Map<String, Object>> rawEnvs = attrs.getEnvironments();
        if (rawEnvs != null) {
            for (Map.Entry<String, Map<String, Object>> envEntry : rawEnvs.entrySet()) {
                Map<String, Object> envValues = envEntry.getValue();
                environments.put(envEntry.getKey(),
                        envValues != null ? new HashMap<>(envValues) : new HashMap<>());
            }
        }

        Instant createdAt = attrs.getCreatedAt() != null ? attrs.getCreatedAt().toInstant() : null;
        Instant updatedAt = attrs.getUpdatedAt() != null ? attrs.getUpdatedAt().toInstant() : null;

        Config config = new Config(this, id != null ? id : "", name != null ? name : "");
        config.setDescription(description);
        config.setParent(parent);
        config.setItems(items);
        config.setEnvironments(environments);
        config.setCreatedAt(createdAt);
        config.setUpdatedAt(updatedAt);
        return config;
    }

    /** Wraps plain values as typed items for the server. */
    static Map<String, ConfigItemDefinition> wrapValuesAsItems(Map<String, Object> values) {
        Map<String, ConfigItemDefinition> items = new HashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ConfigItemDefinition def = new ConfigItemDefinition();
            def.setValue(entry.getValue());
            def.setType(inferType(entry.getValue()));
            items.put(entry.getKey(), def);
        }
        return items;
    }

    /**
     * Copy environments for the wire. Per ADR-024 §2.4 the wire shape is the
     * flat in-memory shape, so this is a defensive shallow copy.
     */
    static Map<String, Map<String, Object>> wrapEnvironments(
            Map<String, Map<String, Object>> environments) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : environments.entrySet()) {
            Map<String, Object> envValues = entry.getValue();
            result.put(entry.getKey(),
                    envValues != null ? new HashMap<>(envValues) : new HashMap<>());
        }
        return result;
    }

    /** Returns the type enum for a value (used for setX-built items). */
    static ConfigItemDefinition.TypeEnum inferType(Object value) {
        if (value instanceof String) return ConfigItemDefinition.TypeEnum.STRING;
        if (value instanceof Number) return ConfigItemDefinition.TypeEnum.NUMBER;
        if (value instanceof Boolean) return ConfigItemDefinition.TypeEnum.BOOLEAN;
        return ConfigItemDefinition.TypeEnum.JSON;
    }

    /**
     * Converts dot-notation keys to nested maps.
     *
     * <p>Example: {@code {"database.host": "localhost", "database.port": 5432}}
     * becomes {@code {"database": {"host": "localhost", "port": 5432}}}.</p>
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> unflatten(Map<String, Object> flat) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            Map<String, Object> current = result;
            for (int i = 0; i < parts.length - 1; i++) {
                current = (Map<String, Object>) current.computeIfAbsent(
                        parts[i], k -> new HashMap<>());
            }
            current.put(parts[parts.length - 1], entry.getValue());
        }
        return result;
    }

    static SmplError mapException(ApiException e) {
        if (e.getCode() == 0) {
            return ApiExceptionHandler.mapApiException(e);
        }
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }

    /** Simulates a config_changed WebSocket event (for testing). */
    void simulateConfigChanged(Map<String, Object> data) {
        handleConfigChanged(data);
    }

    /** Simulates a config_deleted WebSocket event (for testing). */
    void simulateConfigDeleted(Map<String, Object> data) {
        handleConfigDeleted(data);
    }

    /** Simulates a configs_changed WebSocket event (for testing). */
    void simulateConfigsChanged(Map<String, Object> data) {
        handleConfigsChanged(data);
    }

    private record ListenerEntry(String configId, String itemKey,
                                 Consumer<ConfigChangeEvent> listener) {}
}
