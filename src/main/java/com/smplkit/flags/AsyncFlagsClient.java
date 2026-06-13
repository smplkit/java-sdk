package com.smplkit.flags;

import com.smplkit.flags.types.FlagDeclaration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * The Smpl Flags client (async) — counterpart of {@link FlagsClient}.
 *
 * <p>Reads, CRUD, and discovery flush perform their network round-trips on the
 * wrapped executor and return a {@link CompletableFuture}. The live surface
 * connects lazily — {@link #refresh()} opens the connection: it flushes
 * discovery, fetches all flag definitions, and opens the WebSocket. The
 * synchronous helpers ({@link #booleanFlag} / {@link #stringFlag} /
 * {@link #numberFlag} / {@link #jsonFlag} / {@link #stats} / {@link #onChange}
 * and a handle's {@code .get()}) operate against whatever cache state exists, so
 * warm the cache first via an awaitable live method. No explicit install step is
 * required.</p>
 *
 * <p>Thin wrapper around a synchronous {@link FlagsClient}: it holds the sync
 * client and an {@link Executor} (default {@link ForkJoinPool#commonPool()}) and
 * offloads each blocking call.</p>
 */
public final class AsyncFlagsClient implements AutoCloseable {

    private final FlagsClient sync;
    private final Executor executor;

    private AsyncFlagsClient(FlagsClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /** Construct a standalone {@link AsyncFlagsClient}, resolving credentials from the standard sources. */
    public static AsyncFlagsClient create() {
        return wrap(FlagsClient.create());
    }

    /** Construct a standalone {@link AsyncFlagsClient} with the given API key. */
    public static AsyncFlagsClient create(String apiKey) {
        return wrap(FlagsClient.create(apiKey));
    }

    /** Wrap an existing synchronous {@link FlagsClient}, using the common pool. */
    public static AsyncFlagsClient wrap(FlagsClient sync) {
        return wrap(sync, ForkJoinPool.commonPool());
    }

    /** Wrap an existing synchronous {@link FlagsClient} with a custom executor. */
    public static AsyncFlagsClient wrap(FlagsClient sync, Executor executor) {
        return new AsyncFlagsClient(sync, executor);
    }

    /** The underlying synchronous client. */
    public FlagsClient sync() {
        return sync;
    }

    /** The executor backing async calls. */
    public Executor executor() {
        return executor;
    }

    // -----------------------------------------------------------------------
    // Management surface: factory methods (synchronous — no I/O)
    // -----------------------------------------------------------------------

    /**
     * Return a new unsaved boolean {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<Boolean> newBooleanFlag(String id, boolean defaultValue) {
        return sync.newBooleanFlag(id, defaultValue);
    }

    /**
     * Return a new unsaved boolean {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<Boolean> newBooleanFlag(String id, boolean defaultValue, String name, String description) {
        return sync.newBooleanFlag(id, defaultValue, name, description);
    }

    /**
     * Return a new unsaved string {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<String> newStringFlag(String id, String defaultValue) {
        return sync.newStringFlag(id, defaultValue);
    }

    /**
     * Return a new unsaved string {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<String> newStringFlag(String id, String defaultValue, String name, String description) {
        return sync.newStringFlag(id, defaultValue, name, description);
    }

    /**
     * Return a new unsaved string {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @param values       optional list of allowed values constraining what the flag may
     *                     serve; when omitted, the flag is unconstrained
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<String> newStringFlag(String id, String defaultValue, String name, String description,
                                      List<Map<String, Object>> values) {
        return sync.newStringFlag(id, defaultValue, name, description, values);
    }

    /**
     * Return a new unsaved numeric {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<Number> newNumberFlag(String id, Number defaultValue) {
        return sync.newNumberFlag(id, defaultValue);
    }

    /**
     * Return a new unsaved numeric {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<Number> newNumberFlag(String id, Number defaultValue, String name, String description) {
        return sync.newNumberFlag(id, defaultValue, name, description);
    }

    /**
     * Return a new unsaved numeric {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @param values       optional list of allowed values constraining what the flag may
     *                     serve; when omitted, the flag is unconstrained
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<Number> newNumberFlag(String id, Number defaultValue, String name, String description,
                                      List<Map<String, Object>> values) {
        return sync.newNumberFlag(id, defaultValue, name, description, values);
    }

    /**
     * Return a new unsaved JSON {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<Object> newJsonFlag(String id, Object defaultValue) {
        return sync.newJsonFlag(id, defaultValue);
    }

    /**
     * Return a new unsaved JSON {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<Object> newJsonFlag(String id, Object defaultValue, String name, String description) {
        return sync.newJsonFlag(id, defaultValue, name, description);
    }

    /**
     * Return a new unsaved JSON {@link Flag}. Call {@code saveAsync()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @param values       optional list of allowed values constraining what the flag may
     *                     serve; when omitted, the flag is unconstrained
     * @return an unsaved {@link Flag}; call {@code saveAsync()} to persist it
     */
    public Flag<Object> newJsonFlag(String id, Object defaultValue, String name, String description,
                                    List<Map<String, Object>> values) {
        return sync.newJsonFlag(id, defaultValue, name, description, values);
    }

    // -----------------------------------------------------------------------
    // Management surface: CRUD (async)
    // -----------------------------------------------------------------------

    /**
     * Fetch the editable {@link Flag} resource by id (async).
     *
     * @param id identifier of the flag to fetch
     * @return a future yielding the {@link Flag}, ready to mutate and {@code saveAsync()};
     *         completes exceptionally with {@link com.smplkit.errors.NotFoundError} when
     *         no flag with that id exists for the account
     */
    public CompletableFuture<Flag<?>> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    /**
     * List flags for the authenticated account (async).
     *
     * @return a future yielding the flags on the first server-default page as a list of
     *         {@link Flag}
     */
    public CompletableFuture<List<Flag<?>>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    /**
     * List a single page of flags (async).
     *
     * @param pageNumber 1-based page index to fetch; when {@code null}, the server
     *                   default applies
     * @param pageSize   number of flags per page; when {@code null}, the server default
     *                   applies
     * @return a future yielding the flags on the requested page as a list of {@link Flag}
     */
    public CompletableFuture<List<Flag<?>>> list(Integer pageNumber, Integer pageSize) {
        return CompletableFuture.supplyAsync(() -> sync.list(pageNumber, pageSize), executor);
    }

    /**
     * Delete a flag by id (async).
     *
     * @param id identifier of the flag to delete
     * @return a future that completes when the flag has been deleted; completes
     *         exceptionally with {@link com.smplkit.errors.NotFoundError} when no flag
     *         with that id exists for the account
     */
    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }

    // -----------------------------------------------------------------------
    // Management surface: discovery buffer
    // -----------------------------------------------------------------------

    /**
     * Buffer a flag declaration for bulk-discovery upload. Call {@link #flush()} to send.
     *
     * <p>The declaration stays buffered and is sent on the next flush — automatic once the
     * buffer reaches its batch size, or on the first live call.</p>
     *
     * @param item the {@link FlagDeclaration} to queue
     */
    public void register(FlagDeclaration item) {
        sync.register(item);
    }

    /**
     * Buffer flag declarations for bulk-discovery upload. Call {@link #flush()} to send.
     *
     * <p>The declarations stay buffered and are sent on the next flush — automatic once the
     * buffer reaches its batch size, or on the first live call.</p>
     *
     * @param items the {@link FlagDeclaration} list to queue
     */
    public void register(List<FlagDeclaration> items) {
        sync.register(items);
    }

    /**
     * POST pending declarations to the flags bulk endpoint (async).
     *
     * <p>Items remain in the buffer until the request succeeds; failed flushes are retried
     * by the next flush call.</p>
     *
     * @return a future that completes when the buffered declarations have been sent, or
     *         completes exceptionally if the POST fails
     */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(sync::flush, executor);
    }

    /**
     * Number of pending flag declarations awaiting flush.
     *
     * @return the count of buffered flag declarations not yet sent
     */
    public int pendingCount() {
        return sync.pendingCount();
    }

    // -----------------------------------------------------------------------
    // Live surface: typed flag handles (synchronous)
    // -----------------------------------------------------------------------

    /**
     * Declare a boolean flag handle for live evaluation.
     *
     * <p>Synchronous; warm the cache via {@link #refresh()} for live values.</p>
     *
     * @param id           identifier of the flag to evaluate
     * @param defaultValue value returned by {@code handle.get()} when the flag is unknown
     *                     or no environment override or rule applies
     * @return a {@link Flag} handle whose {@code get()} evaluates against the live cache
     */
    public Flag<Boolean> booleanFlag(String id, boolean defaultValue) {
        return sync.booleanFlag(id, defaultValue);
    }

    /**
     * Declare a string flag handle for live evaluation.
     *
     * <p>Synchronous; warm the cache via {@link #refresh()} for live values.</p>
     *
     * @param id           identifier of the flag to evaluate
     * @param defaultValue value returned by {@code handle.get()} when the flag is unknown
     *                     or no environment override or rule applies
     * @return a {@link Flag} handle whose {@code get()} evaluates against the live cache
     */
    public Flag<String> stringFlag(String id, String defaultValue) {
        return sync.stringFlag(id, defaultValue);
    }

    /**
     * Declare a numeric flag handle for live evaluation.
     *
     * <p>Synchronous; warm the cache via {@link #refresh()} for live values.</p>
     *
     * @param id           identifier of the flag to evaluate
     * @param defaultValue value returned by {@code handle.get()} when the flag is unknown
     *                     or no environment override or rule applies
     * @return a {@link Flag} handle whose {@code get()} evaluates against the live cache
     */
    public Flag<Number> numberFlag(String id, Number defaultValue) {
        return sync.numberFlag(id, defaultValue);
    }

    /**
     * Declare a JSON flag handle for live evaluation.
     *
     * <p>Synchronous; warm the cache via {@link #refresh()} for live values.</p>
     *
     * @param id           identifier of the flag to evaluate
     * @param defaultValue value returned by {@code handle.get()} when the flag is unknown
     *                     or no environment override or rule applies
     * @return a {@link Flag} handle whose {@code get()} evaluates against the live cache
     */
    public Flag<Object> jsonFlag(String id, Object defaultValue) {
        return sync.jsonFlag(id, defaultValue);
    }

    // -----------------------------------------------------------------------
    // Live surface: refresh / stats / change listeners
    // -----------------------------------------------------------------------

    /**
     * Re-fetch all flag definitions and clear cache (async).
     *
     * <p>Connects lazily on first use — no explicit install step.</p>
     *
     * @return a future that completes once the definitions have been re-fetched and the
     *         cache cleared, or completes exceptionally if the connect/fetch fails
     */
    public CompletableFuture<Void> refresh() {
        return CompletableFuture.runAsync(sync::refresh, executor);
    }

    /**
     * Return evaluation statistics.
     *
     * <p>Synchronous; reflects evaluations performed so far.</p>
     *
     * @return a {@link FlagStats} snapshot of cache hit/miss counters
     */
    public FlagStats stats() {
        return sync.stats();
    }

    /**
     * Register a global change listener.
     *
     * <p>Synchronous; only records the listener. Open the live connection first
     * via an awaitable live method so events flow.</p>
     *
     * @param listener the callback invoked with a {@link FlagChangeEvent} on every change
     */
    public void onChange(Consumer<FlagChangeEvent> listener) {
        sync.onChange(listener);
    }

    /**
     * Register an id-scoped change listener.
     *
     * <p>Synchronous; only records the listener. Open the live connection first
     * via an awaitable live method so events flow.</p>
     *
     * @param id       identifier of the flag whose changes the listener is scoped to
     * @param listener the callback invoked with a {@link FlagChangeEvent} when that flag changes
     */
    public void onChange(String id, Consumer<FlagChangeEvent> listener) {
        sync.onChange(id, listener);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Release resources owned by the wrapped synchronous client. */
    @Override
    public void close() {
        sync.close();
    }
}
