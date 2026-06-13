package com.smplkit.platform;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * The Smpl Platform client (async) — counterpart of {@link PlatformClient}.
 *
 * <p>Reads and CRUD perform their network round-trips on a background executor
 * and return {@code CompletableFuture<T>}. Pure CRUD; no {@code install()}
 * required.</p>
 *
 * <p>Java's idiom is method-level — each sub-client's CRUD methods return
 * {@code CompletableFuture<T>} via the async wrapper, while the underlying
 * {@link PlatformClient} stays the single source of truth for state.</p>
 *
 * <pre>{@code
 * try (AsyncPlatformClient platform = AsyncPlatformClient.create("sk_...")) {
 *     platform.environments.list().thenAccept(envs -> envs.forEach(System.out::println));
 * }
 * }</pre>
 */
public final class AsyncPlatformClient implements AutoCloseable {

    private final PlatformClient delegate;
    private final Executor executor;

    /** Async environment CRUD ({@code platform.environments}). */
    public final AsyncEnvironmentsClient environments;
    /** Async service CRUD ({@code platform.services}). */
    public final AsyncServicesClient services;
    /** Async context registration + read/delete ({@code platform.contexts}). */
    public final AsyncContextsClient contexts;
    /** Async context-type CRUD ({@code platform.contextTypes}). */
    public final AsyncContextTypesClient contextTypes;

    private AsyncPlatformClient(PlatformClient delegate, Executor executor) {
        this.delegate = delegate;
        this.executor = executor;
        this.environments = new AsyncEnvironmentsClient(delegate.environments, executor);
        this.services = new AsyncServicesClient(delegate.services, executor);
        this.contexts = new AsyncContextsClient(delegate.contexts, executor);
        this.contextTypes = new AsyncContextTypesClient(delegate.contextTypes, executor);
    }

    /** Create with default credentials and the common-pool executor. */
    public static AsyncPlatformClient create() {
        return wrap(PlatformClient.create(), ForkJoinPool.commonPool());
    }

    /** Create with the given API key and the common-pool executor. */
    public static AsyncPlatformClient create(String apiKey) {
        return wrap(PlatformClient.create(apiKey), ForkJoinPool.commonPool());
    }

    /** Wrap an existing {@link PlatformClient}, using the common-pool executor. */
    public static AsyncPlatformClient wrap(PlatformClient delegate) {
        return wrap(delegate, ForkJoinPool.commonPool());
    }

    /**
     * Wrap an existing {@link PlatformClient} with a custom executor.
     *
     * <p>For production use, supply a bounded I/O thread pool rather than the
     * common pool — platform calls are blocking I/O and shouldn't compete with
     * compute work on the common pool.</p>
     */
    public static AsyncPlatformClient wrap(PlatformClient delegate, Executor executor) {
        return new AsyncPlatformClient(delegate, executor);
    }

    /** Returns the underlying sync client. */
    public PlatformClient sync() { return delegate; }

    /** Returns the executor used to schedule async work. */
    public Executor executor() { return executor; }

    /** Close the underlying sync client, releasing its owned transport if any. */
    @Override
    public void close() {
        delegate.close();
    }
}
