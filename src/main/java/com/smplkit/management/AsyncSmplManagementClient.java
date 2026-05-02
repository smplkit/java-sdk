package com.smplkit.management;

import com.smplkit.config.AsyncConfigManagement;
import com.smplkit.flags.AsyncFlagsManagement;
import com.smplkit.logging.AsyncLogGroupsClient;
import com.smplkit.logging.AsyncLoggersClient;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Async wrapper around {@link SmplManagementClient}.
 *
 * <p>Mirrors Python rule 12: async surface peers the sync surface. Java's
 * idiom is method-level — each namespace's CRUD methods return
 * {@code CompletableFuture<T>} via this wrapper, while the underlying
 * {@link SmplManagementClient} stays the single source of truth for state.</p>
 *
 * <p>Use {@link #create()} for default credentials and the common-pool
 * executor; use {@link #wrap(SmplManagementClient, Executor)} to override
 * the executor (recommended for production: a bounded I/O thread pool).</p>
 *
 * <pre>{@code
 * try (AsyncSmplManagementClient mgmt = AsyncSmplManagementClient.create()) {
 *     mgmt.environments.list().thenAccept(envs -> envs.forEach(System.out::println));
 * }
 * }</pre>
 */
public final class AsyncSmplManagementClient implements AutoCloseable {

    private final SmplManagementClient delegate;
    private final Executor executor;

    /** Async context entity CRUD ({@code asyncMgmt.contexts}). */
    public final AsyncContextsClient contexts;
    /** Async context-type schemas. */
    public final AsyncContextTypesClient contextTypes;
    /** Async environment CRUD. */
    public final AsyncEnvironmentsClient environments;
    /** Async account-level settings. */
    public final AsyncAccountSettingsClient accountSettings;
    /** Async config CRUD ({@code asyncMgmt.config}). */
    public final AsyncConfigManagement config;
    /** Async flag CRUD. */
    public final AsyncFlagsManagement flags;
    /** Async single-logger CRUD. */
    public final AsyncLoggersClient loggers;
    /** Async log-group CRUD. */
    public final AsyncLogGroupsClient logGroups;

    private AsyncSmplManagementClient(SmplManagementClient delegate, Executor executor) {
        this.delegate = delegate;
        this.executor = executor;
        this.contexts = new AsyncContextsClient(delegate.contexts, executor);
        this.contextTypes = new AsyncContextTypesClient(delegate.contextTypes, executor);
        this.environments = new AsyncEnvironmentsClient(delegate.environments, executor);
        this.accountSettings = new AsyncAccountSettingsClient(delegate.accountSettings, executor);
        this.config = new AsyncConfigManagement(delegate.config, executor);
        this.flags = new AsyncFlagsManagement(delegate.flags, executor);
        this.loggers = new AsyncLoggersClient(delegate.loggers, executor);
        this.logGroups = new AsyncLogGroupsClient(delegate.logGroups, executor);
    }

    /** Create with default credentials and the common-pool executor. */
    public static AsyncSmplManagementClient create() {
        return wrap(SmplManagementClient.create(), ForkJoinPool.commonPool());
    }

    /** Create with the given API key and the common-pool executor. */
    public static AsyncSmplManagementClient create(String apiKey) {
        return wrap(SmplManagementClient.create(apiKey), ForkJoinPool.commonPool());
    }

    /** Wrap an existing {@link SmplManagementClient}, using the common-pool executor. */
    public static AsyncSmplManagementClient wrap(SmplManagementClient delegate) {
        return wrap(delegate, ForkJoinPool.commonPool());
    }

    /**
     * Wrap an existing {@link SmplManagementClient} with a custom executor.
     *
     * <p>For production use, supply a bounded I/O thread pool rather than the
     * common pool — management calls are blocking I/O and shouldn't compete with
     * compute work on the common pool.</p>
     */
    public static AsyncSmplManagementClient wrap(SmplManagementClient delegate, Executor executor) {
        return new AsyncSmplManagementClient(delegate, executor);
    }

    /** Returns the underlying sync client. */
    public SmplManagementClient sync() { return delegate; }

    /** Returns the executor used to schedule async work. */
    public Executor executor() { return executor; }

    @Override
    public void close() {
        delegate.close();
    }
}
