package com.smplkit.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * The Smpl Config client (async) — counterpart of {@link ConfigClient}.
 *
 * <p>Reads, CRUD, and discovery flush perform their network round-trips on the
 * supplied {@link Executor} and return a {@link CompletableFuture}. The live
 * surface that performs I/O ({@code bind} / {@code getValue} / {@code refresh})
 * also returns a future; {@code subscribe} / {@code onChange} stay synchronous
 * (they read the already-populated cache or only record a listener), mirroring
 * python's {@code AsyncConfigClient}.</p>
 *
 * <p>Thin wrapper: holds a synchronous {@link ConfigClient} and an
 * {@link Executor}; every async method schedules the corresponding synchronous
 * call on the executor.</p>
 */
public final class AsyncConfigClient implements AutoCloseable {

    private final ConfigClient sync;
    private final Executor executor;

    AsyncConfigClient(ConfigClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /** Creates a standalone async client resolving credentials from env / {@code ~/.smplkit}. */
    public static AsyncConfigClient create() {
        return new AsyncConfigClient(ConfigClient.create(), ForkJoinPool.commonPool());
    }

    /** Creates a standalone async client with an explicit API key. */
    public static AsyncConfigClient create(String apiKey) {
        return new AsyncConfigClient(ConfigClient.create(apiKey), ForkJoinPool.commonPool());
    }

    /** Wraps an existing synchronous {@link ConfigClient}; uses the common pool. */
    public static AsyncConfigClient wrap(ConfigClient sync) {
        return new AsyncConfigClient(sync, ForkJoinPool.commonPool());
    }

    /** Wraps an existing synchronous {@link ConfigClient} with a custom executor. */
    public static AsyncConfigClient wrap(ConfigClient sync, Executor executor) {
        return new AsyncConfigClient(sync, executor);
    }

    /**
     * Returns the underlying synchronous client.
     *
     * @return the {@link ConfigClient} this async client delegates to
     */
    public ConfigClient sync() {
        return sync;
    }

    /**
     * Returns the executor backing this async client.
     *
     * @return the {@link Executor} every async method schedules its work on
     */
    public Executor executor() {
        return executor;
    }

    // -----------------------------------------------------------------------
    // CRUD surface
    // -----------------------------------------------------------------------

    /**
     * Return a new unsaved {@link Config}. Call {@link Config#save} to persist.
     *
     * <p>Synchronous — no I/O. Nothing is sent to the server until the returned
     * config is saved.</p>
     *
     * @param id the config identifier (slug) the resource will be saved under
     * @return a new, unsaved {@link Config}
     */
    public Config new_(String id) {
        return sync.new_(id);
    }

    /**
     * Return a new unsaved {@link Config}. Call {@link Config#save} to persist.
     *
     * <p>Synchronous — no I/O.</p>
     *
     * @param id          the config identifier (slug) the resource will be saved under
     * @param name        display name (auto-generated from {@code id} if {@code null})
     * @param description optional human-readable description
     * @param parent      parent config id (slug) to inherit values from, or {@code null}
     * @return a new, unsaved {@link Config}
     */
    public Config new_(String id, String name, String description, String parent) {
        return sync.new_(id, name, description, parent);
    }

    /**
     * Return a new unsaved {@link Config}. Call {@link Config#save} to persist.
     *
     * <p>Synchronous — no I/O. Passing the parent {@link Config} instance lets
     * you skip naming the id explicitly when you already have it in scope.</p>
     *
     * @param id          the config identifier (slug) the resource will be saved under
     * @param name        display name (auto-generated from {@code id} if {@code null})
     * @param description optional human-readable description
     * @param parent      an existing {@link Config} to use as parent (uses its id),
     *                    or {@code null}
     * @return a new, unsaved {@link Config}
     */
    public Config new_(String id, String name, String description, Config parent) {
        return sync.new_(id, name, description, parent);
    }

    /**
     * Fetch the editable {@link Config} resource by id.
     *
     * @param id the config identifier (slug) to fetch
     * @return a future that completes with the editable {@link Config} resource,
     *     or completes exceptionally with {@link com.smplkit.errors.NotFoundError}
     *     if no config with that id exists
     */
    public CompletableFuture<Config> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    /**
     * List configs for the authenticated account.
     *
     * @return a future that completes with the configs on the server's default
     *     first page, or an empty list if there are none
     */
    public CompletableFuture<List<Config>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    /**
     * List configs for the authenticated account.
     *
     * @param pageNumber 1-based page to fetch. When {@code null}, the server's
     *     default first page is returned.
     * @param pageSize   number of configs per page. When {@code null}, the
     *     server's default page size is used.
     * @return a future that completes with the configs on the requested page, or
     *     an empty list if there are none
     */
    public CompletableFuture<List<Config>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    /**
     * Delete a config by id.
     *
     * @param id the config identifier (slug) to delete
     * @return a future that completes when the config has been deleted
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    /**
     * Queue a configuration declaration for bulk-discovery upload.
     *
     * <p>Synchronous — in-memory. The declaration is buffered and sent in the
     * background; it surfaces the config in the smplkit console even if no
     * values are set yet.</p>
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
        sync.registerConfig(configId, service, environment, parent, name, description);
    }

    /**
     * Queue a config item declaration. {@link #registerConfig} must run first.
     *
     * <p>Synchronous — in-memory. The declaration is buffered and sent in the
     * background, surfacing the item (with its type and default) in the smplkit
     * console.</p>
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
        sync.registerConfigItem(configId, itemKey, itemType, defaultValue, description);
    }

    /**
     * Number of pending config declarations awaiting flush.
     *
     * <p>Synchronous — reads the in-memory discovery buffer.</p>
     *
     * @return the count of buffered declarations not yet sent
     */
    public int pendingCount() {
        return sync.pendingCount();
    }

    /**
     * Send any queued config and item declarations to the server.
     *
     * <p>Discovery is best-effort — failures never propagate to your code.
     * Drained entries are not requeued; the SDK re-observes them on the next
     * process start.</p>
     *
     * @return a future that completes when the queued declarations have been sent
     */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(sync::flush, executor);
    }

    // -----------------------------------------------------------------------
    // Live surface
    // -----------------------------------------------------------------------

    /**
     * Bind a Java object or {@link Map} to a config id; return it live.
     *
     * <p>Declarative, code-first API with no parent. On first use the schema and
     * values are registered with the server, the local cache is seeded, and the
     * bound object is mutated in place on every WebSocket-delivered change so
     * readers always see the current resolved value. Connects lazily on first
     * use — no explicit install step.</p>
     *
     * @param id     the config id to register under
     * @param config a populated POJO instance or a {@link Map}. Both supply the
     *               schema (via the POJO's fields or the map's keys) and the
     *               in-code defaults.
     * @param <T>    inferred from {@code config}; the completed value is the same
     *               reference passed in
     * @return a future that completes with the same {@code config} object,
     *     registered and live, or completes exceptionally with
     *     {@link IllegalArgumentException} if {@code config} is {@code null}
     */
    public <T> CompletableFuture<T> bind(String id, T config) {
        return CompletableFuture.supplyAsync(() -> sync.bind(id, config), executor);
    }

    /**
     * Bind a Java object or {@link Map} to a config id with a parent; return it
     * live.
     *
     * <p>Same as {@link #bind(String, Object)} but with parent-chain
     * inheritance: fields the caller omits inherit from the bound parent. On
     * first use the schema and values are registered, the local cache is seeded,
     * and the bound object is mutated in place on every change. Idempotent —
     * repeated calls with the same {@code id} complete with the
     * originally-bound object. Connects lazily on first use — no explicit
     * install step.</p>
     *
     * @param id     the config id to register under
     * @param config a populated POJO instance or a {@link Map}. Both supply the
     *               schema (via the POJO's fields or the map's keys) and the
     *               in-code defaults.
     * @param parent optional parent — any object previously returned from a
     *               {@link #bind} call (POJO or map). Activates parent-chain
     *               inheritance for fields the caller omitted.
     * @param <T>    inferred from {@code config}; the completed value is the same
     *               reference passed in
     * @return a future that completes with the same {@code config} object,
     *     registered and live, or completes exceptionally with
     *     {@link IllegalArgumentException} if {@code config} is {@code null}, or
     *     if {@code parent} is non-null but was not previously bound via
     *     {@link #bind}
     */
    public <T> CompletableFuture<T> bind(String id, T config, Object parent) {
        return CompletableFuture.supplyAsync(() -> sync.bind(id, config, parent), executor);
    }

    /**
     * Return a live, dict-like {@link LiveConfigProxy} for a config id.
     *
     * <p>The proxy always reflects the latest resolved values; reads happen
     * through it. Subscribing also registers the config so the reference appears
     * in the smplkit console. Synchronous on the async client (it reads the
     * already-populated cache); call an awaitable live method first if the cache
     * is not yet warm.</p>
     *
     * @param id the config identifier (slug) to subscribe to
     * @return a live {@link LiveConfigProxy} whose reads always see the current
     *     resolved values
     * @throws com.smplkit.errors.NotFoundError if the config is unknown
     */
    public LiveConfigProxy subscribe(String id) {
        return sync.subscribeNoConnect(id);
    }

    /**
     * Read a single resolved config value (inheritance-aware), throwing when the
     * config or key is missing.
     *
     * <p>The value comes from the locally-cached resolved chain, so parent
     * configs are already folded in.</p>
     *
     * @param id  the config identifier (slug) to read from
     * @param key the item key within the config
     * @return a future that completes with the resolved value, or completes
     *     exceptionally with {@link com.smplkit.errors.NotFoundError} if the
     *     config is unknown or the key is absent
     */
    public CompletableFuture<Object> getValue(String id, String key) {
        return CompletableFuture.supplyAsync(() -> sync.getValue(id, key), executor);
    }

    /**
     * Read a single resolved config value (inheritance-aware), falling back to
     * {@code defaultValue} if the config or key is missing.
     *
     * <p>Supplying {@code defaultValue} also registers the config (if new) and
     * the key — with its type inferred and {@code defaultValue} as its value —
     * for console observability.</p>
     *
     * @param id           the config identifier (slug) to read from
     * @param key          the item key within the config
     * @param defaultValue value the future completes with when the config or key
     *     is missing
     * @return a future that completes with the resolved value, or
     *     {@code defaultValue} if the config or key is missing
     */
    public CompletableFuture<Object> getValue(String id, String key, Object defaultValue) {
        return CompletableFuture.supplyAsync(() -> sync.getValue(id, key, defaultValue), executor);
    }

    /**
     * Re-fetch all configs and update resolved values.
     *
     * <p>Fires change listeners for any values that differ from the previous
     * state. Connects lazily on first use — no explicit install step.</p>
     *
     * @return a future that completes once the re-fetch finishes, or completes
     *     exceptionally with {@link com.smplkit.errors.ConnectionError} if the
     *     fetch fails
     */
    public CompletableFuture<Void> refresh() {
        return CompletableFuture.runAsync(sync::refresh, executor);
    }

    /**
     * Register a global change listener (fires on any change to any config).
     *
     * <p>Synchronous — it only records the listener. Open the live connection
     * first via an awaitable live method so events flow.</p>
     *
     * @param listener invoked with a {@link ConfigChangeEvent} on every change
     */
    public void onChange(Consumer<ConfigChangeEvent> listener) {
        sync.addListenerNoConnect(null, null, listener);
    }

    /**
     * Register a config-scoped change listener.
     *
     * <p>Fires only on changes to the named config. Synchronous — it only
     * records the listener; open the live connection first via an awaitable
     * live method so events flow.</p>
     *
     * @param configId the config id to scope the listener to
     * @param listener invoked with a {@link ConfigChangeEvent} when that config
     *     changes
     */
    public void onChange(String configId, Consumer<ConfigChangeEvent> listener) {
        sync.addListenerNoConnect(configId, null, listener);
    }

    /**
     * Register an item-scoped change listener.
     *
     * <p>Fires only when the named item on the named config changes.
     * Synchronous — it only records the listener; open the live connection
     * first via an awaitable live method so events flow.</p>
     *
     * @param configId the config id to scope the listener to
     * @param itemKey  the item key within the config to restrict the listener to
     * @param listener invoked with a {@link ConfigChangeEvent} when that item
     *     changes
     */
    public void onChange(String configId, String itemKey, Consumer<ConfigChangeEvent> listener) {
        sync.addListenerNoConnect(configId, itemKey, listener);
    }

    /**
     * Release resources owned by the underlying synchronous client.
     *
     * <p>Delegates to {@link ConfigClient#close()} — tears down the owned
     * WebSocket and HTTP transport for a standalone client; a wired client
     * closes neither.</p>
     */
    @Override
    public void close() {
        sync.close();
    }
}
