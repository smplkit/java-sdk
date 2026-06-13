package com.smplkit.config;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.Helpers;
import com.smplkit.MetricsReporter;
import com.smplkit.SharedWebSocket;
import com.smplkit.internal.ApiExceptionHandler;
import com.smplkit.errors.NotFoundError;
import com.smplkit.errors.SmplError;
import com.smplkit.errors.ValidationError;
import com.smplkit.internal.ConfigRegistrationBuffer;
import com.smplkit.internal.Debug;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.config.ApiClient;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigBulkItem;
import com.smplkit.internal.generated.config.model.ConfigBulkRequest;
import com.smplkit.internal.generated.config.model.ConfigCreateRequest;
import com.smplkit.internal.generated.config.model.ConfigCreateResource;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigRequest;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Smpl Config client.
 *
 * <p>Smpl Config has two surfaces on a single client, mirroring how the audit
 * and jobs clients expose their full surface from one class:</p>
 *
 * <ul>
 *   <li><b>CRUD surface</b> — pure CRUD, no live connection: {@code new_}
 *       / {@code get} / {@code list} / {@code delete} and the discovery buffer
 *       ({@code registerConfig} / {@code registerConfigItem} / {@code flush} /
 *       {@code pendingCount}). The client owns the discovery buffer directly.</li>
 *   <li><b>Live surface</b> — lazily connects to your running service on first
 *       use: {@code subscribe} (a live dict-like {@link LiveConfigProxy}),
 *       {@code getValue} (an ad-hoc resolved read), {@code bind} (a live POJO/Map
 *       binding), {@code onChange}, and {@code refresh}. The first live call
 *       transparently flushes discovery, fetches and resolves every config into
 *       the local cache, and opens the live-updates WebSocket — no explicit
 *       install step.</li>
 * </ul>
 *
 * <p>The client supports two construction shapes:</p>
 *
 * <ul>
 *   <li><b>Wired</b> into {@link com.smplkit.SmplClient} — borrows the parent's
 *       config transport for both runtime fetch and CRUD and the parent's shared
 *       WebSocket for the live channel. This is the common path.</li>
 *   <li><b>Standalone</b> — {@code ConfigClient.builder()...build()} builds and
 *       owns its own config transport, and on first live use opens and owns its
 *       own WebSocket. {@code close()} tears down only the owned transport and
 *       owned WebSocket.</li>
 * </ul>
 */
public final class ConfigClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("smplkit.config");
    private static final Logger WS_LOG = Logger.getLogger("smplkit.config.ws");

    private static final int CONFIG_BATCH_FLUSH_SIZE = 50;
    private static final int RUNTIME_PAGE_SIZE = 1000;

    final ConfigsApi configsApi;
    private volatile String environment;
    private volatile String service;
    private volatile MetricsReporter metrics;

    // Discovery buffer is owned by this client (no management delegation).
    private final ConfigRegistrationBuffer buffer = new ConfigRegistrationBuffer();

    // Live-surface state.
    private volatile boolean connected;
    private volatile Runnable ensureStartedHook;
    private Map<String, Map<String, Object>> configCache = new HashMap<>();
    /** Raw {@link Config} objects keyed by id, kept around so a single-config
     * change (WS event) can refetch one config and rebuild the resolved cache
     * for everyone — including descendants that inherit from the changed
     * config — without a full re-list. */
    private Map<String, Config> rawConfigCache = new HashMap<>();
    /** Identity-stable proxy cache: same id maps to the same proxy instance. */
    private final Map<String, LiveConfigProxy> proxies = new ConcurrentHashMap<>();
    /** Targets bound via {@link #bind} keyed by config id. Mutated in place on
     * WebSocket events. */
    private final Map<String, Object> bindings = new ConcurrentHashMap<>();
    /** Parent config id each binding was bound under ({@code null} for roots) —
     * drives in-memory cache seeding through the bound parent chain. */
    private final Map<String, String> boundParents = new ConcurrentHashMap<>();
    private final List<ListenerEntry> listeners = Collections.synchronizedList(new ArrayList<>());
    private final Object cacheLock = new Object();

    private volatile SharedWebSocket wsManager;
    private volatile boolean ownsWs;
    private final Consumer<Map<String, Object>> configChangedHandler;
    private final Consumer<Map<String, Object>> configDeletedHandler;
    private final Consumer<Map<String, Object>> configsChangedHandler;

    // Standalone-only state (null when wired into a parent SmplClient).
    private final boolean ownsTransport;
    private final HttpClient ownedHttpClient;
    private final String standaloneApiKey;
    private final String appBaseUrl;

    /**
     * Wired constructor invoked by {@link com.smplkit.SmplClient}. Receives a
     * pre-built generated {@link ConfigsApi}, the parent's {@link HttpClient},
     * and the resolved {@code apiKey}. The wired client borrows the parent's
     * transport and (via {@link #setSharedWs}) the parent's WebSocket; it owns
     * neither and closes neither.
     */
    public ConfigClient(ConfigsApi configsApi, HttpClient httpClient, String apiKey) {
        this.configsApi = configsApi;
        this.ownsTransport = false;
        this.ownedHttpClient = null;
        this.standaloneApiKey = null;
        this.appBaseUrl = null;
        this.configChangedHandler = this::handleConfigChanged;
        this.configDeletedHandler = this::handleConfigDeleted;
        this.configsChangedHandler = this::handleConfigsChanged;
    }

    /**
     * Standalone constructor — owns its transport and (lazily) its WebSocket.
     * Used by {@link ConfigClientBuilder}.
     */
    ConfigClient(ConfigsApi configsApi, HttpClient ownedHttpClient, String standaloneApiKey,
                 String appBaseUrl, String environment, String service) {
        this.configsApi = configsApi;
        this.ownsTransport = true;
        this.ownedHttpClient = ownedHttpClient;
        this.standaloneApiKey = standaloneApiKey;
        this.appBaseUrl = appBaseUrl;
        this.environment = environment;
        this.service = service;
        this.configChangedHandler = this::handleConfigChanged;
        this.configDeletedHandler = this::handleConfigDeleted;
        this.configsChangedHandler = this::handleConfigsChanged;
    }

    // -----------------------------------------------------------------------
    // Standalone construction
    // -----------------------------------------------------------------------

    /** Returns a builder for a standalone {@link ConfigClient}. */
    public static ConfigClientBuilder builder() {
        return new ConfigClientBuilder();
    }

    /** Creates a standalone {@link ConfigClient} resolving credentials from env / {@code ~/.smplkit}. */
    public static ConfigClient create() {
        return builder().build();
    }

    /** Creates a standalone {@link ConfigClient} with an explicit API key. */
    public static ConfigClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    // -----------------------------------------------------------------------
    // Wired setters (called by SmplClient)
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
    public void setMetrics(MetricsReporter metrics) {
        this.metrics = metrics;
    }

    /** Sets the shared WebSocket for real-time config updates (wired path). */
    public void setSharedWs(SharedWebSocket ws) {
        this.wsManager = ws;
    }

    /** Sets the parent's deferred-start hook, run once when the live surface first connects (wired path). */
    public void setEnsureStarted(Runnable hook) {
        this.ensureStartedHook = hook;
    }

    // -----------------------------------------------------------------------
    // CRUD surface (no live connection)
    // -----------------------------------------------------------------------

    /**
     * Return a new unsaved {@link Config}. Call {@link Config#save} to persist.
     *
     * @param id the config id (slug)
     */
    public Config new_(String id) {
        return new_(id, null, null, (String) null);
    }

    /**
     * Return a new unsaved {@link Config}. Call {@link Config#save} to persist.
     *
     * @param id          the config id (slug)
     * @param name        display name (auto-generated from id if {@code null})
     * @param description optional description
     * @param parent      parent config id (slug), or {@code null}
     */
    public Config new_(String id, String name, String description, String parent) {
        return new Config(this, id, name != null ? name : Helpers.keyToDisplayName(id),
                description, parent, new HashMap<>(), new HashMap<>(), null, null);
    }

    /**
     * Return a new unsaved {@link Config}. Call {@link Config#save} to persist.
     *
     * <p>{@code parent} accepts either a config id (string) or an existing
     * {@link Config} instance — passing the instance lets you skip naming the
     * id explicitly when you already have the parent in scope.</p>
     *
     * @param id          the config id (slug)
     * @param name        display name (auto-generated from id if {@code null})
     * @param description optional description
     * @param parent      an existing {@link Config} to use as parent (uses its id)
     */
    public Config new_(String id, String name, String description, Config parent) {
        return new_(id, name, description, resolveParentId(parent));
    }

    /** Normalize a {@code parent} argument to a config id string. */
    private static String resolveParentId(Config parent) {
        if (parent == null) {
            return null;
        }
        if (parent.getId() == null) {
            throw new IllegalArgumentException(
                    "parent config must be saved (have an id) before being used as a parent");
        }
        return parent.getId();
    }

    /**
     * Fetch the editable {@link Config} resource by id.
     *
     * @param id the config identifier (slug) to fetch
     * @return the editable {@link Config} resource
     * @throws com.smplkit.errors.NotFoundError if no config with that id exists
     */
    public Config get(String id) {
        try {
            ConfigResponse response = configsApi.getConfig(id);
            if (response == null || response.getData() == null) {
                throw new NotFoundError("Config with id '" + id + "' not found", null);
            }
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * List configs for the authenticated account.
     *
     * @return the configs on the server's default first page, or an empty list
     *     if there are none
     */
    public List<Config> list() {
        return list(null, null);
    }

    /**
     * List configs for the authenticated account.
     *
     * @param pageNumber 1-based page to fetch. When {@code null}, the server's
     *     default first page is returned.
     * @param pageSize   number of configs per page. When {@code null}, the
     *     server's default page size is used.
     * @return the configs on the requested page, or an empty list if there are
     *     none
     */
    public List<Config> list(Integer pageNumber, Integer pageSize) {
        try {
            // Positional args: filterParent, filterSearch, filterManaged,
            // sort, pageNumber, pageSize, metaTotal.
            ConfigListResponse response = configsApi.listConfigs(
                    null, null, null, null, pageNumber, pageSize, null);
            List<ConfigResource> data = response.getData();
            if (data == null) {
                return Collections.emptyList();
            }
            List<Config> result = new ArrayList<>(data.size());
            for (ConfigResource resource : data) {
                result.add(parseResource(resource));
            }
            return Collections.unmodifiableList(result);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Delete a config by id.
     *
     * @param id the config identifier (slug) to delete
     */
    public void delete(String id) {
        try {
            configsApi.deleteConfig(id);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Creates a new config on the server. Called by {@link Config#save}. */
    Config _createConfig(Config config) {
        try {
            com.smplkit.internal.generated.config.model.Config attrs =
                    new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(config.getName());
            if (config.getDescription() != null) {
                attrs.setDescription(config.getDescription());
            }
            if (config.getParent() != null) {
                attrs.setParent(config.getParent());
            }
            if (!config.rawItemsMap().isEmpty()) {
                attrs.setItems(makeItems(config.rawItemsMap()));
            }
            if (!config.rawEnvironmentsMap().isEmpty()) {
                attrs.setEnvironments(makeEnvironments(config.rawEnvironmentsMap()));
            }

            // Create uses a dedicated envelope where the caller-supplied id is required.
            ConfigCreateResource data = new ConfigCreateResource()
                    .id(config.getId())
                    .type(ConfigCreateResource.TypeEnum.CONFIG)
                    .attributes(attrs);
            ConfigCreateRequest body = new ConfigCreateRequest().data(data);

            ConfigResponse response = configsApi.createConfig(body);
            if (response == null || response.getData() == null) {
                throw new ValidationError("unexpected response", null);
            }
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Updates an existing config on the server. Called by {@link Config#save}. */
    Config _updateConfigFromModel(Config config) {
        try {
            com.smplkit.internal.generated.config.model.Config attrs =
                    new com.smplkit.internal.generated.config.model.Config();
            attrs.setName(config.getName());
            if (config.getDescription() != null) {
                attrs.setDescription(config.getDescription());
            }
            if (config.getParent() != null) {
                attrs.setParent(config.getParent());
            }
            attrs.setItems(makeItems(config.rawItemsMap()));
            attrs.setEnvironments(makeEnvironments(config.rawEnvironmentsMap()));

            ConfigResource data = new ConfigResource()
                    .id(config.getId())
                    .type(ConfigResource.TypeEnum.CONFIG)
                    .attributes(attrs);
            ConfigRequest body = new ConfigRequest().data(data);

            ConfigResponse response = configsApi.updateConfig(config.getId(), body);
            if (response == null || response.getData() == null) {
                throw new ValidationError("unexpected response", null);
            }
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // CRUD surface: discovery buffer (owned directly)
    // -----------------------------------------------------------------------

    /**
     * Queue a configuration declaration for bulk-discovery upload.
     *
     * <p>The declaration is buffered and sent in the background; it surfaces the
     * config in the smplkit console even if no values are set yet.</p>
     *
     * @param configId    the config identifier (slug) being declared
     * @param service     name of the service declaring the config, or {@code null}
     * @param environment environment the declaration is scoped to, or {@code null}
     * @param parent      optional parent config id this config inherits from
     * @param name        optional display name for the config
     * @param description optional human-readable description
     */
    public void registerConfig(String configId, String service, String environment,
                               String parent, String name, String description) {
        buffer.declare(configId, service, environment, parent, name, description);
        if (buffer.pendingCount() >= CONFIG_BATCH_FLUSH_SIZE) {
            triggerBackgroundFlush();
        }
    }

    /**
     * Queue a config item declaration. {@link #registerConfig} must run first.
     *
     * <p>The declaration is buffered and sent in the background, surfacing the
     * item (with its type and default) in the smplkit console.</p>
     *
     * @param configId     the config identifier (slug) the item belongs to
     * @param itemKey      key of the item within the config
     * @param itemType     item value type — one of {@code "STRING"},
     *                     {@code "NUMBER"}, {@code "BOOLEAN"}, or {@code "JSON"}
     * @param defaultValue the in-code default value for the item
     * @param description  optional human-readable description
     */
    public void registerConfigItem(String configId, String itemKey, String itemType,
                                   Object defaultValue, String description) {
        buffer.addItem(configId, itemKey, itemType, defaultValue, description);
        if (buffer.pendingCount() >= CONFIG_BATCH_FLUSH_SIZE) {
            triggerBackgroundFlush();
        }
    }

    /** Latest in-flight background-flush thread; package-private for test waits. */
    volatile Thread lastFlushThread;

    private synchronized void triggerBackgroundFlush() {
        Thread existing = lastFlushThread;
        if (existing != null && existing.isAlive()) {
            return; // Coalesce — one in-flight flush at a time.
        }
        // flush() is best-effort and never propagates (it swallows its own
        // POST failures), so the background thread needs no extra guard.
        Thread t = new Thread(this::flush, "smplkit-config-flush");
        t.setDaemon(true);
        lastFlushThread = t;
        t.start();
    }

    /**
     * Send any queued config and item declarations to the server.
     *
     * <p>Discovery is best-effort — failures here never propagate to your code.
     * Drained entries are not requeued; the SDK re-observes them on the next
     * process start.</p>
     */
    public void flush() {
        List<ConfigRegistrationBuffer.Entry> batch = buffer.drain();
        if (batch.isEmpty()) {
            return;
        }
        ConfigBulkRequest body = new ConfigBulkRequest();
        List<ConfigBulkItem> items = new ArrayList<>(batch.size());
        for (ConfigRegistrationBuffer.Entry entry : batch) {
            ConfigBulkItem item = new ConfigBulkItem();
            item.setId(entry.id);
            if (entry.service != null) {
                item.setService(entry.service);
            }
            if (entry.environment != null) {
                item.setEnvironment(entry.environment);
            }
            if (entry.parent != null) {
                item.setParent(entry.parent);
            }
            if (entry.name != null) {
                item.setName(entry.name);
            }
            if (entry.description != null) {
                item.setDescription(entry.description);
            }
            if (!entry.items.isEmpty()) {
                Map<String, ConfigItemDefinition> defs = new LinkedHashMap<>();
                for (Map.Entry<String, ConfigRegistrationBuffer.ItemEntry> e : entry.items.entrySet()) {
                    ConfigItemDefinition def = new ConfigItemDefinition();
                    def.setValue(e.getValue().defaultValue);
                    def.setType(toTypeEnum(e.getValue().itemType));
                    if (e.getValue().description != null) {
                        def.setDescription(e.getValue().description);
                    }
                    defs.put(e.getKey(), def);
                }
                item.setItems(defs);
            }
            items.add(item);
        }
        body.setConfigs(items);
        try {
            configsApi.bulkRegisterConfigs(body);
        } catch (Exception ex) {
            // Fire-and-forget: discovery is best-effort and never propagates.
            Debug.log("registration", "bulk register failed: " + ex.getMessage());
        }
    }

    /**
     * Async variant of {@link #flush()}.
     *
     * <p>Sends any queued config and item declarations to the server on the JDK
     * common pool. Discovery is best-effort — failures never propagate to your
     * code. Drained entries are not requeued; the SDK re-observes them on the
     * next process start.</p>
     *
     * @return a future that completes when the queued declarations have been sent
     */
    public java.util.concurrent.CompletableFuture<Void> flushAsync() {
        return java.util.concurrent.CompletableFuture.runAsync(
                this::flush, java.util.concurrent.ForkJoinPool.commonPool());
    }

    /** Number of pending config declarations awaiting flush. */
    public int pendingCount() {
        return buffer.pendingCount();
    }

    // -----------------------------------------------------------------------
    // Live surface: lazy connect + WebSocket helpers
    // -----------------------------------------------------------------------

    /** Return the shared WebSocket — the parent's when wired, else our own. */
    private SharedWebSocket ensureWs() {
        SharedWebSocket ws = this.wsManager;
        if (ws != null) {
            return ws;
        }
        // Standalone: open and own a WebSocket against the event gateway.
        if (appBaseUrl != null) {
            ws = new SharedWebSocket(ownedHttpClient, appBaseUrl, standaloneApiKey, metrics);
            ws.start();
            this.wsManager = ws;
            this.ownsWs = true;
        }
        return this.wsManager;
    }

    /**
     * Open the live connection to the running Smpl Config service.
     *
     * <p>Flushes any buffered discovery declarations, fetches and resolves
     * every config for the configured environment into the local cache,
     * opens the shared WebSocket, and subscribes to {@code config_changed} /
     * {@code config_deleted} / {@code configs_changed} events.</p>
     *
     * <p>Idempotent and internal — every live method calls it on first use, so
     * the live surface auto-connects with no explicit step.</p>
     */
    void ensureConnected() {
        Runnable h = this.ensureStartedHook;
        if (h != null) {
            h.run();
        }
        if (connected) {
            return;
        }
        synchronized (cacheLock) {
            if (connected) {
                return;
            }
            Debug.log("websocket", "config runtime initializing");

            // Flush any buffered discovery declarations BEFORE the initial fetch,
            // so newly-discovered configs appear in the cache on first read.
            // flush() is best-effort and never propagates.
            flush();

            // Fetch + resolve + cache + fire change listeners (against empty
            // old_cache, so any registered listeners see "initial" events).
            doRefresh("initial");
            connected = true;

            SharedWebSocket ws = ensureWs();
            if (ws != null) {
                Debug.log("registration",
                        "registering config_changed, config_deleted, and configs_changed handlers");
                ws.on("config_changed", configChangedHandler);
                ws.on("config_deleted", configDeletedHandler);
                ws.on("configs_changed", configsChangedHandler);
                ws.ensureConnected(Duration.ofSeconds(10));
            }
            Debug.log("websocket", "config runtime connected");
        }
    }

    /** List configs directly from the API for the runtime cache. */
    private List<Config> fetchAllConfigs() {
        List<Config> all = new ArrayList<>();
        int page = 1;
        while (true) {
            try {
                ConfigListResponse response = configsApi.listConfigs(
                        null, null, null, null, page, RUNTIME_PAGE_SIZE, null);
                List<ConfigResource> data = response.getData();
                if (data == null || data.isEmpty()) {
                    break;
                }
                for (ConfigResource resource : data) {
                    all.add(parseResource(resource));
                }
                if (data.size() < RUNTIME_PAGE_SIZE) {
                    break;
                }
                page++;
            } catch (ApiException e) {
                throw mapException(e);
            }
        }
        return all;
    }

    /** Fetch a single config from the API. Returns {@code null} on missing data. */
    private Config fetchConfig(String configId) {
        try {
            ConfigResponse response = configsApi.getConfig(configId);
            if (response == null || response.getData() == null) {
                return null;
            }
            return parseResource(response.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Live surface: bind, subscribe
    // -----------------------------------------------------------------------

    /**
     * Bind a Java object or {@link Map} to a config id; return it live.
     * Convenience for {@link #bind(String, Object, Object)} with no parent.
     */
    public <T> T bind(String id, T config) {
        return bind(id, config, null);
    }

    /**
     * Bind a Java object or {@link Map} to a config id; return it live.
     *
     * <p>Declarative, code-first API. Two flavors:</p>
     *
     * <ul>
     *   <li><b>POJO instance</b>: the class is the schema; the instance carries
     *       the defaults. With {@code parent} set, only fields the caller
     *       explicitly populated act as overrides — Java does not preserve a
     *       "did the caller construct this field explicitly?" bit, so every
     *       non-static, non-transient field is registered.</li>
     *   <li><b>Map</b>: every key in the map is a leaf to register, with its
     *       value as the in-code default. Nested maps flatten to dot-notation.
     *       There is no class-default concept — keys the caller wants to inherit
     *       are simply omitted from the map.</li>
     * </ul>
     *
     * <p>On first boot the schema and values are registered with the server.
     * The local cache is then seeded so reads work immediately: if the config
     * already exists server-side (fetched on connect) its values are
     * authoritative and synced onto the bound object; if it is brand-new, the
     * cache entry is seeded in-memory from the bound object's values resolved
     * through its bound parent chain (no network round-trip). On every
     * WebSocket-delivered change thereafter the bound object is mutated in place
     * — POJO instances via {@code Field.set}, maps via {@code Map.put}. Readers
     * always see the current resolved value with no proxy indirection.</p>
     *
     * <p>Idempotent. Repeated calls with the same {@code id} return the
     * originally-bound object; the new {@code config} argument is ignored.</p>
     *
     * <p>Connects lazily on first use — no explicit install step.</p>
     *
     * @param id     the config id to register under
     * @param config a populated POJO instance or a {@link Map}. Both supply the
     *               schema (via the POJO's fields or the map's keys) and the
     *               in-code defaults.
     * @param parent optional parent — any object previously returned from a
     *               {@link #bind} call (POJO or map). Activates parent-chain
     *               inheritance for fields the caller omitted.
     * @param <T>    inferred from {@code config}; the return is the same
     *               reference passed in
     * @return the same {@code config} object, registered and live
     * @throws IllegalArgumentException if {@code config} is {@code null}, or if
     *         {@code parent} is non-null but was not previously bound via
     *         {@link #bind}
     */
    public <T> T bind(String id, T config, Object parent) {
        ensureConnected();
        if (config == null) {
            throw new IllegalArgumentException(
                    "bind() requires a non-null POJO instance or Map");
        }

        @SuppressWarnings("unchecked")
        T existing = (T) bindings.get(id);
        if (existing != null) {
            return existing;
        }

        String parentId = registerBindingDeclaration(id, config, parent);

        // Register the binding BEFORE syncing so WebSocket dispatch finds it.
        bindings.put(id, config);
        if (parentId != null) {
            boundParents.put(id, parentId);
        }
        seedOrSyncBinding(id, config);
        return config;
    }

    /**
     * Return a live, dict-like {@link LiveConfigProxy} for a config id.
     *
     * <p>The proxy always reflects the latest resolved values; reads happen
     * through it ({@code proxy.get("key")}, {@code proxy.getOrDefault("key",
     * default)}). Subscribing registers the config declaration for code-first
     * observability so the reference appears in the smplkit console.</p>
     *
     * <p>Connects lazily on first use — no explicit install step.</p>
     *
     * @param id the config identifier (slug) to subscribe to
     * @return a live {@link LiveConfigProxy} whose reads always see the current
     *     resolved values
     * @throws com.smplkit.errors.NotFoundError if the config is unknown
     */
    public LiveConfigProxy subscribe(String id) {
        ensureConnected();
        return subscribeNoConnect(id);
    }

    /**
     * Subscribe WITHOUT triggering lazy connect — used by
     * {@link AsyncConfigClient#subscribe}, which (mirroring python's async
     * {@code subscribe}) reads the already-populated cache rather than
     * connecting. Registers the declaration and returns the cached proxy.
     */
    LiveConfigProxy subscribeNoConnect(String id) {
        observeConfigDeclaration(id, null, null, null);
        if (!configCache.containsKey(id)) {
            throw new NotFoundError("Config with id '" + id + "' not found", null);
        }
        if (metrics != null) {
            metrics.record("config.resolutions", "resolutions", Map.of("config", id));
        }
        return cachedProxy(id);
    }

    /**
     * Read a single resolved config value (inheritance-aware), throwing when
     * the config or key is missing.
     *
     * <p>The value comes from the locally-cached resolved chain, so parent
     * configs are already folded in. For a live dict-like view use
     * {@link #subscribe}; for typed access via a Java POJO use {@link #bind}.
     * Connects lazily on first use — no explicit install step.</p>
     *
     * @param id  the config identifier (slug) to read from
     * @param key the item key within the config
     * @return the resolved value
     * @throws com.smplkit.errors.NotFoundError if the config is unknown, or if
     *     the key is absent from the config
     */
    public Object getValue(String id, String key) {
        ensureConnected();
        if (!configCache.containsKey(id)) {
            throw new NotFoundError("Config with id '" + id + "' not found", null);
        }
        Map<String, Object> values = configCache.get(id);
        if (!values.containsKey(key)) {
            throw new NotFoundError(
                    "Config item '" + key + "' not found in config '" + id + "'", null);
        }
        return values.get(key);
    }

    /**
     * Read a single resolved config value (inheritance-aware), falling back to
     * {@code defaultValue} if the config or key is missing. Never raises.
     *
     * <p><b>Registers</b> the config (if new) and the key (inferred type,
     * {@code defaultValue} as default) for code-first observability, so the
     * reference appears in the smplkit console.</p>
     *
     * <p>For a live dict-like view use {@link #subscribe}; for typed access via
     * a Java POJO use {@link #bind}. Connects lazily on first use — no explicit
     * install step.</p>
     *
     * @param id           the config identifier (slug) to read from
     * @param key          the item key within the config
     * @param defaultValue value returned when the config or key is missing.
     *     Supplying it also registers the config (if new) and the key — with its
     *     type inferred and {@code defaultValue} as its value — for console
     *     observability.
     * @return the resolved value, or {@code defaultValue} if the config or key
     *     is missing
     */
    public Object getValue(String id, String key, Object defaultValue) {
        ensureConnected();
        // Register the config + key so the reference shows up in the console
        // even if it's never been declared via bind(). The buffer is idempotent
        // at the (config_id, item_key) level.
        observeConfigDeclaration(id, null, null, null);
        observeItemDeclaration(id, key, inferItemType(defaultValue), defaultValue, null);

        if (!configCache.containsKey(id)) {
            return defaultValue;
        }
        Map<String, Object> values = configCache.get(id);
        return values.containsKey(key) ? values.get(key) : defaultValue;
    }

    // -----------------------------------------------------------------------
    // Internal: binding helpers
    // -----------------------------------------------------------------------

    /**
     * Validate the parent, register the config + item declarations. Returns the
     * resolved parent config id (or {@code null}).
     */
    private String registerBindingDeclaration(String id, Object config, Object parent) {
        String parentId = null;
        if (parent != null) {
            parentId = configIdFor(parent);
            if (parentId == null) {
                throw new IllegalArgumentException(
                        "bind(): parent must be an object previously returned "
                                + "from client.config().bind(). Bind the parent first.");
            }
        }

        String configName = null;
        String configDescription = null;
        if (!(config instanceof Map)) {
            String simple = config.getClass().getSimpleName();
            if (simple != null && !simple.isEmpty()) {
                configName = simple;
            }
        }
        observeConfigDeclaration(id, parentId, configName, configDescription);

        List<Item> items = new ArrayList<>();
        iterTargetItemsInto(config, "", items);
        for (Item item : items) {
            observeItemDeclaration(id, item.key, item.type, item.value, null);
        }
        return parentId;
    }

    /** Return the config_id this target was bound under, or {@code null}. */
    private String configIdFor(Object target) {
        for (Map.Entry<String, Object> e : bindings.entrySet()) {
            if (e.getValue() == target) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Apply current cached values to a freshly-bound target.
     *
     * <p>Handles the existing-config case: on restart, server-side values
     * override the in-code defaults from the constructor (or map).</p>
     */
    private void syncTargetFromCache(Object target, String configId) {
        Map<String, Object> cache = configCache.get(configId);
        if (cache == null || cache.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : cache.entrySet()) {
            applyChangeToTarget(target, e.getKey(), e.getValue());
        }
    }

    /**
     * Seed the resolved cache for a freshly-bound config, or sync from it.
     *
     * <p>If {@code configId} is already in the resolved cache it existed
     * server-side (fetched on connect), so server values are authoritative
     * — sync them onto the bound object. Otherwise the config is brand-new:
     * seed the cache entry in-memory by resolving this object's values through
     * its bound parent chain, so {@link #subscribe} / {@link #getValue} work
     * immediately with no flush or refresh. Pure in-memory — no network.</p>
     */
    private void seedOrSyncBinding(String configId, Object target) {
        synchronized (cacheLock) {
            if (configCache.containsKey(configId)) {
                syncTargetFromCache(target, configId);
                return;
            }
            Map<String, Map<String, Object>> newCache = new HashMap<>(configCache);
            newCache.put(configId, resolveBoundChain(configId));
            configCache = newCache;
        }
    }

    /**
     * Resolve a bound config's values through its bound parent chain.
     *
     * <p>Walks {@code boundParents} from the child up through already-bound
     * ancestors, flattening each bound object's in-code values, then runs
     * the same deep-merge {@link Resolver#resolve} used everywhere else (child
     * wins over parent). Ancestors that aren't bound objects stop the walk.</p>
     *
     * <p>A config that has a bound parent contributes only the fields the
     * caller explicitly set; fields left at their default are omitted so they
     * inherit from the parent. The chain's root ancestor (no bound parent)
     * contributes all its fields.</p>
     */
    private Map<String, Object> resolveBoundChain(String configId) {
        List<Resolver.ChainEntry> chain = new ArrayList<>();
        String current = configId;
        Set<String> seen = new HashSet<>();
        while (current != null && bindings.containsKey(current) && !seen.contains(current)) {
            seen.add(current);
            Map<String, Object> items = boundItemsToFlat(bindings.get(current));
            chain.add(new Resolver.ChainEntry(current, items, new HashMap<>()));
            current = boundParents.get(current);
        }
        return Resolver.resolve(chain, environment);
    }

    /** Flatten a bound POJO instance or map to {@code {dotted_key: value}}. */
    private static Map<String, Object> boundItemsToFlat(Object config) {
        List<Item> items = new ArrayList<>();
        iterTargetItemsInto(config, "", items);
        Map<String, Object> out = new HashMap<>();
        for (Item item : items) {
            out.put(item.key, item.value);
        }
        return out;
    }

    /**
     * Re-apply in-memory seeds for bound configs not yet present server-side.
     *
     * <p>A freshly-bound config lives only as a seed until it is flushed and
     * fetched; without this, any cache rebuild (a manual refresh, or a
     * WebSocket event for another config) would drop it. Server-present
     * configs are already in {@code newCache} and are authoritative — only
     * bound ids missing from it are re-seeded.</p>
     */
    private void mergePendingSeeds(Map<String, Map<String, Object>> newCache) {
        for (String boundId : bindings.keySet()) {
            if (!newCache.containsKey(boundId)) {
                newCache.put(boundId, resolveBoundChain(boundId));
            }
        }
    }

    private LiveConfigProxy cachedProxy(String id) {
        return proxies.computeIfAbsent(id, k -> new LiveConfigProxy(this, k));
    }

    /** Queue a config declaration with the owned discovery buffer. */
    void observeConfigDeclaration(String configId, String parent, String name, String description) {
        registerConfig(configId, service, environment, parent, name, description);
    }

    /** Queue a config item declaration with the owned discovery buffer. */
    void observeItemDeclaration(String configId, String itemKey, String itemType,
                                Object defaultValue, String description) {
        registerConfigItem(configId, itemKey, itemType, defaultValue, description);
    }

    /** A single flattened leaf in a bound target. */
    private record Item(String key, String type, Object value) {}

    /**
     * Map a runtime value (bind value or get default) to a config item type:
     * {@code boolean -> BOOLEAN}, any {@link Number} -> {@code NUMBER},
     * {@link CharSequence} -> {@code STRING}, everything else -> {@code STRING}
     * (safest universal fallback — admins can retype to {@code JSON},
     * {@code NUMBER}, or {@code BOOLEAN} in the console).
     *
     * <p>{@code Boolean} is checked before {@code Number} because in Python
     * {@code bool} is a subclass of {@code int}.</p>
     */
    private static String inferItemType(Object value) {
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof Number) {
            return "NUMBER";
        }
        if (value instanceof CharSequence) {
            return "STRING";
        }
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
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?>) {
            return true;
        }
        if (value instanceof CharSequence) {
            return false;
        }
        if (value instanceof Number) {
            return false;
        }
        if (value instanceof Boolean) {
            return false;
        }
        if (value instanceof Character) {
            return false;
        }
        if (value instanceof Enum<?>) {
            return false;
        }
        if (value instanceof Iterable<?>) {
            return false;
        }
        if (value instanceof java.time.temporal.Temporal) {
            return false;
        }
        if (value instanceof java.util.Date) {
            return false;
        }
        Class<?> c = value.getClass();
        if (c.isArray()) {
            return false;
        }
        // JDK built-ins (java.*, javax.*) are always leaves — only descend
        // into user-defined classes.
        String n = c.getName();
        if (n.startsWith("java.") || n.startsWith("javax.")) {
            return false;
        }
        return true;
    }

    private static List<Field> allInstanceFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
                    continue;
                }
                if (f.isSynthetic()) {
                    continue;
                }
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
     *
     * <p>The server has already enforced types and constraints, so the SDK
     * trusts the value as-is on either path.</p>
     */
    @SuppressWarnings("unchecked")
    static void applyChangeToTarget(Object target, String dottedKey, Object value) {
        String[] parts = dottedKey.split("\\.");
        Object current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            current = stepInto(current, parts[i]);
            if (current == null) {
                return;
            }
        }
        String last = parts[parts.length - 1];
        if (current instanceof Map<?, ?> leafMap) {
            ((Map<String, Object>) leafMap).put(last, value);
            return;
        }
        Field f = findField(current.getClass(), last);
        if (f == null) {
            return;
        }
        writeField(f, current, coerce(f.getType(), value));
    }

    /** Walk one step into a namespace. Returns {@code null} if the step cannot
     *  be taken (missing key/field) so the caller bails the dotted walk. */
    @SuppressWarnings("unchecked")
    private static Object stepInto(Object current, String part) {
        if (current instanceof Map<?, ?> mapNode) {
            if (!mapNode.containsKey(part)) {
                return null;
            }
            return ((Map<String, Object>) mapNode).get(part);
        }
        Field f = findField(current.getClass(), part);
        if (f == null) {
            return null;
        }
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
        if (value == null) {
            return null;
        }
        if (fieldType.isInstance(value)) {
            return value;
        }
        if (value instanceof Number n) {
            if (fieldType == int.class || fieldType == Integer.class) {
                return n.intValue();
            }
            if (fieldType == long.class || fieldType == Long.class) {
                return n.longValue();
            }
            if (fieldType == double.class || fieldType == Double.class) {
                return n.doubleValue();
            }
            if (fieldType == float.class || fieldType == Float.class) {
                return n.floatValue();
            }
            if (fieldType == short.class || fieldType == Short.class) {
                return n.shortValue();
            }
            if (fieldType == byte.class || fieldType == Byte.class) {
                return n.byteValue();
            }
        }
        if ((fieldType == boolean.class || fieldType == Boolean.class) && value instanceof Boolean) {
            return value;
        }
        if (fieldType == String.class) {
            return value.toString();
        }
        return value;
    }

    // -----------------------------------------------------------------------
    // Live surface: refresh / change listeners
    // -----------------------------------------------------------------------

    /**
     * Re-fetch all configs and update resolved values.
     *
     * <p>Fires change listeners for any values that differ from the previous
     * state. Connects lazily on first use — no explicit install step.</p>
     *
     * @throws com.smplkit.errors.ConnectionError If the fetch fails.
     */
    public void refresh() {
        ensureConnected();
        doRefresh("manual");
    }

    /**
     * Async variant of {@link #refresh()}.
     *
     * <p>Re-fetches all configs and updates resolved values on the JDK common
     * pool, firing change listeners for any values that differ from the previous
     * state.</p>
     *
     * @return a future that completes once the re-fetch finishes, or completes
     *     exceptionally with {@link com.smplkit.errors.ConnectionError} if the
     *     fetch fails
     */
    public java.util.concurrent.CompletableFuture<Void> refreshAsync() {
        return java.util.concurrent.CompletableFuture.runAsync(
                this::refresh, java.util.concurrent.ForkJoinPool.commonPool());
    }

    private void doRefresh(String source) {
        List<Config> configs = fetchAllConfigs();
        Map<String, Map<String, Object>> newCache = new HashMap<>();
        Map<String, Config> newRaw = new HashMap<>();
        for (Config cfg : configs) {
            List<Resolver.ChainEntry> chain = cfg.buildChain(configs);
            newCache.put(cfg.getId(), Resolver.resolve(chain, environment));
            newRaw.put(cfg.getId(), cfg);
        }
        mergePendingSeeds(newCache);
        Map<String, Map<String, Object>> oldCache;
        synchronized (cacheLock) {
            oldCache = configCache;
            configCache = newCache;
            rawConfigCache = newRaw;
        }
        fireChangeListeners(oldCache, newCache, source);
    }

    /**
     * Register a global change listener (fires on any change to any config).
     *
     * <p>Connects lazily on first use — no explicit install step.</p>
     *
     * @param listener invoked with a {@link ConfigChangeEvent} on every change
     */
    public void onChange(Consumer<ConfigChangeEvent> listener) {
        ensureConnected();
        listeners.add(new ListenerEntry(null, null, listener));
    }

    /**
     * Register a config-scoped change listener.
     *
     * <p>Fires only on changes to the named config. Connects lazily on first
     * use — no explicit install step.</p>
     *
     * @param configId the config id to scope the listener to
     * @param listener invoked with a {@link ConfigChangeEvent} when that config
     *     changes
     */
    public void onChange(String configId, Consumer<ConfigChangeEvent> listener) {
        ensureConnected();
        listeners.add(new ListenerEntry(configId, null, listener));
    }

    /**
     * Register an item-scoped change listener.
     *
     * <p>Fires only when the named item on the named config changes. Connects
     * lazily on first use — no explicit install step.</p>
     *
     * @param configId the config id to scope the listener to
     * @param itemKey  the item key within the config to restrict the listener to
     * @param listener invoked with a {@link ConfigChangeEvent} when that item
     *     changes
     */
    public void onChange(String configId, String itemKey, Consumer<ConfigChangeEvent> listener) {
        ensureConnected();
        listeners.add(new ListenerEntry(configId, itemKey, listener));
    }

    /**
     * Record a change listener WITHOUT triggering lazy connect — used by
     * {@link AsyncConfigClient#onChange}, which (mirroring python's async
     * {@code on_change}) only records the listener and leaves connecting to an
     * awaitable live method.
     */
    void addListenerNoConnect(String configId, String itemKey, Consumer<ConfigChangeEvent> listener) {
        listeners.add(new ListenerEntry(configId, itemKey, listener));
    }

    /** Diff two caches, apply changes to bound instances, fire listeners. */
    private void fireChangeListeners(
            Map<String, Map<String, Object>> oldCache,
            Map<String, Map<String, Object>> newCache,
            String source) {
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

            for (String iKey : allItemKeys) {
                Object oldVal = oldItems.get(iKey);
                Object newVal = newItems.get(iKey);
                if (Objects.equals(oldVal, newVal)) {
                    continue;
                }
                // Apply to bound target first so listeners reading the object
                // see the new value.
                if (target != null) {
                    applyChangeToTarget(target, iKey, newVal);
                }
                if (metrics != null) {
                    metrics.record("config.changes", "changes", Map.of("config", cfgId));
                }
                ConfigChangeEvent event = new ConfigChangeEvent(cfgId, iKey, oldVal, newVal, source);
                fireListenersFor(event);
            }
        }
    }

    private void fireListenersFor(ConfigChangeEvent event) {
        for (ListenerEntry entry : listeners) {
            if (entry.configId != null && !entry.configId.equals(event.configId())) {
                continue;
            }
            if (entry.itemKey != null && !entry.itemKey.equals(event.itemKey())) {
                continue;
            }
            try {
                entry.listener.accept(event);
            } catch (Exception e) {
                LOG.log(Level.SEVERE,
                        "Exception in on_change listener for "
                                + event.configId() + "." + event.itemKey(), e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal: event handlers (called by SharedWebSocket)
    // -----------------------------------------------------------------------

    /**
     * Re-resolve every config in {@code rawCache} and fire change listeners.
     *
     * <p>Inheritance means a single config change can shift descendants' resolved
     * values too — so whenever {@code rawCache} is mutated (config added,
     * updated, or deleted), every config gets re-resolved against the new
     * snapshot.</p>
     */
    private void rebuildResolvedCache(Map<String, Config> rawCache, String source) {
        List<Config> rawList = new ArrayList<>(rawCache.values());
        Map<String, Map<String, Object>> newCache = new HashMap<>();
        for (Map.Entry<String, Config> e : rawCache.entrySet()) {
            List<Resolver.ChainEntry> chain = e.getValue().buildChain(rawList);
            newCache.put(e.getKey(), Resolver.resolve(chain, environment));
        }
        mergePendingSeeds(newCache);
        Map<String, Map<String, Object>> oldCache;
        synchronized (cacheLock) {
            oldCache = configCache;
            configCache = newCache;
            rawConfigCache = rawCache;
        }
        fireChangeListeners(oldCache, newCache, source);
    }

    private void handleConfigChanged(Map<String, Object> data) {
        if (!connected) {
            return;
        }
        String key = data.get("id") instanceof String s ? s : null;
        if (key == null) {
            handleConfigsChanged(data);
            return;
        }
        Debug.log("websocket", "config_changed event received, key=" + key);

        Map<String, Config> rawCache;
        synchronized (cacheLock) {
            rawCache = new HashMap<>(rawConfigCache);
        }
        Config cfg;
        try {
            cfg = fetchConfig(key);
        } catch (Exception e) {
            WS_LOG.log(Level.SEVERE, "Failed to fetch config '" + key + "' after WS event", e);
            return;
        }
        if (cfg == null) {
            return;
        }
        rawCache.put(key, cfg);
        try {
            ensureAncestorsCached(rawCache);
            rebuildResolvedCache(rawCache, "websocket");
        } catch (Exception e) {
            WS_LOG.log(Level.SEVERE, "Failed to handle config_changed for '" + key + "' after WS event", e);
        }
    }

    /**
     * Pull any referenced-but-uncached parent (and ancestors) into {@code rawCache}.
     *
     * <p>A {@code config_changed} event fetches only the changed config. If that
     * config inherits from a parent that isn't already in the raw cache — e.g. a
     * parent created via discovery after the initial connect that never broadcast
     * its own event — the chain walk in {@link #rebuildResolvedCache} would stop
     * at the gap and the child would re-resolve missing its inherited values. Walk
     * every config's parent pointers and fetch each absent ancestor so the
     * inheritance chain resolves fully.</p>
     */
    private void ensureAncestorsCached(Map<String, Config> rawCache) {
        Deque<String> pending = new ArrayDeque<>();
        for (Config cfg : rawCache.values()) {
            if (cfg.getParent() != null) {
                pending.push(cfg.getParent());
            }
        }
        while (!pending.isEmpty()) {
            String parentId = pending.pop();
            if (rawCache.containsKey(parentId)) {
                continue;
            }
            Config parent = fetchConfig(parentId);
            if (parent == null) {
                continue;
            }
            rawCache.put(parentId, parent);
            if (parent.getParent() != null) {
                pending.push(parent.getParent());
            }
        }
    }

    private void handleConfigDeleted(Map<String, Object> data) {
        if (!connected) {
            return;
        }
        String key = data.get("id") instanceof String s ? s : null;
        if (key == null) {
            handleConfigsChanged(data);
            return;
        }
        Debug.log("websocket", "config_deleted event received, key=" + key);

        Map<String, Config> rawCache;
        synchronized (cacheLock) {
            rawCache = new HashMap<>(rawConfigCache);
        }
        if (rawCache.remove(key) == null) {
            return;
        }
        rebuildResolvedCache(rawCache, "websocket");
    }

    private void handleConfigsChanged(Map<String, Object> data) {
        if (!connected) {
            return;
        }
        Debug.log("websocket", "configs_changed event received");
        try {
            doRefresh("websocket");
        } catch (Exception e) {
            WS_LOG.log(Level.SEVERE, "Failed to refresh configs after WS event", e);
        }
    }

    // -----------------------------------------------------------------------
    // Package-private: cache access (for LiveConfigProxy) + test hooks
    // -----------------------------------------------------------------------

    /** Returns resolved values for a config id. */
    Map<String, Object> _getResolvedCache(String id) {
        return configCache.getOrDefault(id, Map.of());
    }

    /** Package-private: check if connected (for testing). */
    boolean isConnected() {
        return connected;
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

    // -----------------------------------------------------------------------
    // Internal: resource <-> model conversion
    // -----------------------------------------------------------------------

    /** Converts a server resource into the SDK's {@link Config} model. */
    private Config parseResource(ConfigResource resource) {
        String id = resource.getId();
        com.smplkit.internal.generated.config.model.Config attrs = resource.getAttributes();

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

        // The wire shape is flat: {env: {key: rawValue}}.
        Map<String, ConfigEnvironment> environments = new HashMap<>();
        Map<String, Map<String, Object>> rawEnvs = attrs.getEnvironments();
        if (rawEnvs != null) {
            for (Map.Entry<String, Map<String, Object>> envEntry : rawEnvs.entrySet()) {
                Map<String, Object> envValues = envEntry.getValue();
                environments.put(envEntry.getKey(), new ConfigEnvironment(envValues));
            }
        }

        Instant createdAt = attrs.getCreatedAt() != null ? attrs.getCreatedAt().toInstant() : null;
        Instant updatedAt = attrs.getUpdatedAt() != null ? attrs.getUpdatedAt().toInstant() : null;

        return new Config(this, id != null ? id : "", name != null ? name : "",
                description, parent, items, environments, createdAt, updatedAt);
    }

    /** Convert a typed items map ({@code {key: {value, type, description}}}) to generated item defs. */
    @SuppressWarnings("unchecked")
    private static Map<String, ConfigItemDefinition> makeItems(Map<String, Object> items) {
        Map<String, ConfigItemDefinition> wrapped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : items.entrySet()) {
            ConfigItemDefinition def = new ConfigItemDefinition();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m && m.containsKey("value")) {
                Map<String, Object> mm = (Map<String, Object>) m;
                def.setValue(mm.get("value"));
                Object type = mm.get("type");
                if (type != null) {
                    def.setType(toTypeEnum(String.valueOf(type)));
                }
                Object desc = mm.get("description");
                if (desc != null) {
                    def.setDescription(String.valueOf(desc));
                }
            } else {
                def.setValue(v);
                def.setType(inferType(v));
            }
            wrapped.put(e.getKey(), def);
        }
        return wrapped;
    }

    /**
     * Convert a typed environments map to the flat wire shape
     * {@code {env: {key: rawValue}}}, which is exactly what each
     * {@link ConfigEnvironment} stores.
     */
    private static Map<String, Map<String, Object>> makeEnvironments(
            Map<String, ConfigEnvironment> environments) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map.Entry<String, ConfigEnvironment> e : environments.entrySet()) {
            result.put(e.getKey(), e.getValue().values());
        }
        return result;
    }

    private static ConfigItemDefinition.TypeEnum toTypeEnum(String itemType) {
        if (itemType == null) {
            return null;
        }
        return switch (itemType.toUpperCase(java.util.Locale.ROOT)) {
            case "STRING" -> ConfigItemDefinition.TypeEnum.STRING;
            case "NUMBER" -> ConfigItemDefinition.TypeEnum.NUMBER;
            case "BOOLEAN" -> ConfigItemDefinition.TypeEnum.BOOLEAN;
            case "JSON" -> ConfigItemDefinition.TypeEnum.JSON;
            default -> null;
        };
    }

    /** Returns the type enum for a raw value (used for plain-value items). */
    private static ConfigItemDefinition.TypeEnum inferType(Object value) {
        if (value instanceof String) {
            return ConfigItemDefinition.TypeEnum.STRING;
        }
        if (value instanceof Number) {
            return ConfigItemDefinition.TypeEnum.NUMBER;
        }
        if (value instanceof Boolean) {
            return ConfigItemDefinition.TypeEnum.BOOLEAN;
        }
        return ConfigItemDefinition.TypeEnum.JSON;
    }

    static SmplError mapException(ApiException e) {
        if (e.getCode() == 0) {
            return ApiExceptionHandler.mapApiException(e);
        }
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Release resources — only those this client owns.
     *
     * <p>Tears down the owned WebSocket (opened by a standalone client on first
     * live use) and the owned HTTP transport (standalone construction). A wired
     * client borrows the parent's transport and WebSocket and closes neither.</p>
     */
    @Override
    public void close() {
        if (ownsWs && wsManager != null) {
            wsManager.close();
            wsManager = null;
            ownsWs = false;
        }
        // The owned java.net.http.HttpClient has no explicit close in JDK 17;
        // dropping the reference is sufficient for GC to reclaim its threads.
    }

    private record ListenerEntry(String configId, String itemKey,
                                 Consumer<ConfigChangeEvent> listener) {}
}
