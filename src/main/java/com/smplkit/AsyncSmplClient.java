package com.smplkit;

import com.smplkit.management.AsyncSmplManagementClient;
import com.smplkit.management.SmplManagementClient;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Async wrapper around {@link SmplClient}.
 *
 * <p>Mirrors Python rule 12. Java's {@code CompletableFuture} idiom is method-
 * level rather than class-shaped, so this thin wrapper exposes:</p>
 * <ul>
 *   <li>{@link #manage()} — an {@link AsyncSmplManagementClient} whose CRUD methods return {@code CompletableFuture<T>}</li>
 *   <li>{@link #sync()} — the underlying {@link SmplClient} for direct runtime access (flag eval is in-memory cache after init, so it's already non-blocking after warm-up).</li>
 * </ul>
 *
 * <p>Active-record models ({@code Config}, {@code Flag}, {@code Logger}, etc.)
 * expose {@code save()} / {@code saveAsync()} side-by-side: customer picks at
 * the call site on the same instance.</p>
 *
 * <pre>{@code
 * try (AsyncSmplClient client = AsyncSmplClient.create()) {
 *     client.manage().environments.list()
 *         .thenAccept(envs -> envs.forEach(System.out::println));
 *
 *     // Runtime evaluation is sync (in-memory cache); use sync() to access it.
 *     boolean v2 = client.sync().flags().booleanFlag("checkout-v2", false).get();
 * }
 * }</pre>
 */
public final class AsyncSmplClient implements AutoCloseable {

    private final SmplClient delegate;
    private final AsyncSmplManagementClient asyncManage;

    private AsyncSmplClient(SmplClient delegate, Executor executor) {
        this.delegate = delegate;
        // The runtime SmplClient already constructed its own SmplManagementClient
        // via sharedWith() — wrap that in the async facade rather than building a
        // new sync mgmt client (otherwise the registration buffer would be doubled).
        SmplManagementClient sharedMgmt = delegate.manage();
        this.asyncManage = AsyncSmplManagementClient.wrap(sharedMgmt, executor);
    }

    public static AsyncSmplClient create() {
        return wrap(SmplClient.create(), ForkJoinPool.commonPool());
    }

    public static AsyncSmplClient wrap(SmplClient delegate) {
        return wrap(delegate, ForkJoinPool.commonPool());
    }

    public static AsyncSmplClient wrap(SmplClient delegate, Executor executor) {
        return new AsyncSmplClient(delegate, executor);
    }

    /** Returns the async management entry point. */
    public AsyncSmplManagementClient manage() { return asyncManage; }

    /** Returns the underlying sync runtime client (flag eval, config reads, etc.). */
    public SmplClient sync() { return delegate; }

    @Override
    public void close() {
        delegate.close();
    }
}
