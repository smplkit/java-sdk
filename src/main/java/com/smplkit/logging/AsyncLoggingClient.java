package com.smplkit.logging;

import com.smplkit.logging.adapters.LoggingAdapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * The Smpl Logging client (async) — counterpart of {@link LoggingClient}.
 *
 * <p>Reads, CRUD, and discovery flush perform their network round-trips on a
 * background executor and return {@code CompletableFuture<T>}.
 * {@link #registerAdapter} is a pre-install configuration call; the live surface
 * ({@link #install} / {@code onChange} / {@link #refresh}) requires
 * {@link #install} first; calling {@code onChange} / {@link #refresh} earlier
 * raises {@link com.smplkit.errors.NotInstalledError}.</p>
 *
 * <p>Java's idiom is method-level — each sub-client's CRUD methods return
 * {@code CompletableFuture<T>} via the async wrapper, while the underlying
 * {@link LoggingClient} stays the single source of truth for state.</p>
 *
 * <pre>{@code
 * try (AsyncLoggingClient logging = AsyncLoggingClient.create("sk_...")) {
 *     logging.loggers.new_("sqlalchemy.engine").saveAsync().join();
 *     logging.install().join();
 * }
 * }</pre>
 */
public final class AsyncLoggingClient implements AutoCloseable {

    private final LoggingClient delegate;
    private final Executor executor;

    /** Async logger CRUD + discovery ({@code logging.loggers}). */
    public final AsyncLoggersClient loggers;
    /** Async log-group CRUD ({@code logging.logGroups}). */
    public final AsyncLogGroupsClient logGroups;

    private AsyncLoggingClient(LoggingClient delegate, Executor executor) {
        this.delegate = delegate;
        this.executor = executor;
        this.loggers = new AsyncLoggersClient(delegate.loggers, executor);
        this.logGroups = new AsyncLogGroupsClient(delegate.logGroups, executor);
    }

    /** Create with default credentials and the common-pool executor. */
    public static AsyncLoggingClient create() {
        return wrap(LoggingClient.create(), ForkJoinPool.commonPool());
    }

    /** Create with the given API key and the common-pool executor. */
    public static AsyncLoggingClient create(String apiKey) {
        return wrap(LoggingClient.create(apiKey), ForkJoinPool.commonPool());
    }

    /** Wrap an existing {@link LoggingClient}, using the common-pool executor. */
    public static AsyncLoggingClient wrap(LoggingClient delegate) {
        return wrap(delegate, ForkJoinPool.commonPool());
    }

    /**
     * Wrap an existing {@link LoggingClient} with a custom executor.
     *
     * <p>For production use, supply a bounded I/O thread pool rather than the
     * common pool — logging calls are blocking I/O and shouldn't compete with
     * compute work on the common pool.</p>
     */
    public static AsyncLoggingClient wrap(LoggingClient delegate, Executor executor) {
        return new AsyncLoggingClient(delegate, executor);
    }

    /**
     * Returns the underlying sync client.
     *
     * @return the {@link LoggingClient} this async client delegates to
     */
    public LoggingClient sync() {
        return delegate;
    }

    /**
     * Returns the executor used to schedule async work.
     *
     * @return the executor backing this client's {@code CompletableFuture}s
     */
    public Executor executor() {
        return executor;
    }

    // --- Adapter registration (pre-install, ungated) — stays synchronous ---

    /**
     * Register a logging adapter. Must be called before install().
     *
     * <p>If called at least once, auto-loading is disabled — only explicitly
     * registered adapters are used. This is a pre-install configuration
     * call: it is intentionally NOT gated by {@link #install}.</p>
     *
     * @param adapter the logging-framework adapter to use for discovering
     *     loggers and applying levels
     */
    public void registerAdapter(LoggingAdapter adapter) {
        delegate.registerAdapter(adapter);
    }

    // --- Live surface ---

    /**
     * Hook smplkit into the application's logging machinery (async).
     *
     * <p>See {@link LoggingClient#install} for the full contract. Idempotent.
     * This IS the explicit consent gate for {@code onChange} / {@link #refresh}.</p>
     *
     * @return a future that completes when install finishes
     */
    public CompletableFuture<Void> install() {
        return CompletableFuture.runAsync(delegate::install, executor);
    }

    /**
     * Register a global change listener that fires for any logger change.
     *
     * <p>Requires {@link #install} first; raises
     * {@link com.smplkit.errors.NotInstalledError} otherwise. Stays synchronous —
     * it only mutates in-memory listener state.</p>
     *
     * @param listener the callback invoked with a {@link LoggerChangeEvent}
     *     whenever a logger's effective level changes
     * @throws com.smplkit.errors.NotInstalledError if called before {@link #install}
     */
    public void onChange(Consumer<LoggerChangeEvent> listener) {
        delegate.onChange(listener);
    }

    /**
     * Register a key-scoped change listener that fires only for the given logger id.
     *
     * <p>Requires {@link #install} first; raises
     * {@link com.smplkit.errors.NotInstalledError} otherwise.</p>
     *
     * @param id       the logger id to watch
     * @param listener the callback invoked with a {@link LoggerChangeEvent}
     *     whenever this logger's effective level changes
     * @throws com.smplkit.errors.NotInstalledError if called before {@link #install}
     */
    public void onChange(String id, Consumer<LoggerChangeEvent> listener) {
        delegate.onChange(id, listener);
    }

    /**
     * Re-fetch all loggers and groups and fire listener events for any deltas.
     *
     * <p>Requires {@link #install} first; raises
     * {@link com.smplkit.errors.NotInstalledError} otherwise.</p>
     *
     * @return a future that completes when the re-fetch finishes
     * @throws com.smplkit.errors.NotInstalledError if called before {@link #install}
     */
    public CompletableFuture<Void> refresh() {
        return CompletableFuture.runAsync(delegate::refresh, executor);
    }

    /**
     * Returns whether {@link #install} has been called.
     *
     * @return {@code true} once {@link #install} has run, {@code false} otherwise
     */
    public boolean isInstalled() {
        return delegate.isInstalled();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
