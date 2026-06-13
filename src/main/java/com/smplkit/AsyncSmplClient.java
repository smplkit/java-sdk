package com.smplkit;

import com.smplkit.account.AsyncAccountClient;
import com.smplkit.audit.AsyncAuditClient;
import com.smplkit.config.AsyncConfigClient;
import com.smplkit.flags.AsyncFlagsClient;
import com.smplkit.jobs.AsyncJobsClient;
import com.smplkit.logging.AsyncLoggingClient;
import com.smplkit.platform.AsyncPlatformClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Asynchronous entry point for the smplkit SDK.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (AsyncSmplClient client = AsyncSmplClient.create()) {
 *     Flag<Boolean> checkoutV2 = client.flags.booleanFlag("checkout-v2", false);
 *     if (checkoutV2.get()) { ... }
 * }
 * }</pre>
 *
 * <p>All parameters are optional. When omitted, the SDK resolves each one in
 * precedence order, lowest to highest: built-in defaults, then the
 * {@code ~/.smplkit} configuration file, then {@code SMPLKIT_*} environment
 * variables, then the explicit builder arguments (a value supplied at a higher
 * level overrides the lower ones).</p>
 *
 * <p>Java's {@code CompletableFuture} idiom is method-level rather than
 * class-shaped, so this thin wrapper holds a sync {@link SmplClient} delegate
 * and exposes async product clients (whose I/O methods return
 * {@code CompletableFuture}) on the public fields below. Active-record models
 * ({@code Config}, {@code Flag}, {@code Logger}, etc.) expose {@code save()} /
 * {@code saveAsync()} side-by-side on the same instance.</p>
 */
public final class AsyncSmplClient implements AutoCloseable {

    private final SmplClient delegate;
    private final Executor executor;

    /** Platform's cross-cutting CRUD on one client. */
    public final AsyncPlatformClient platform;
    /** Account-level settings on one client. */
    public final AsyncAccountClient account;
    /** Config's full surface on one client. */
    public final AsyncConfigClient config;
    /** Flags' full surface on one client. */
    public final AsyncFlagsClient flags;
    /** Logging's full surface on one client. */
    public final AsyncLoggingClient logging;
    /** Audit's full surface on one client. */
    public final AsyncAuditClient audit;
    /** Jobs' full surface on one client. */
    public final AsyncJobsClient jobs;

    private AsyncSmplClient(SmplClient delegate, Executor executor) {
        this.delegate = delegate;
        this.executor = executor;
        this.platform = AsyncPlatformClient.wrap(delegate.platform, executor);
        this.account = AsyncAccountClient.wrap(delegate.account, executor);
        this.config = AsyncConfigClient.wrap(delegate.config, executor);
        this.flags = AsyncFlagsClient.wrap(delegate.flags, executor);
        this.logging = AsyncLoggingClient.wrap(delegate.logging, executor);
        this.audit = AsyncAuditClient.wrap(delegate.audit, executor);
        this.jobs = AsyncJobsClient.wrap(delegate.jobs, executor);
    }

    /**
     * Creates a new {@link AsyncSmplClient}, resolving all settings from the
     * {@code ~/.smplkit} configuration file and {@code SMPLKIT_*} environment
     * variables, and running blocking I/O on the {@link ForkJoinPool#commonPool()
     * common pool}.
     *
     * @return a new async client
     * @throws com.smplkit.errors.SmplError if the environment, service, or API
     *     key cannot be resolved
     */
    public static AsyncSmplClient create() {
        return wrap(SmplClient.create(), ForkJoinPool.commonPool());
    }

    /**
     * Wraps an existing sync client, running blocking I/O on the
     * {@link ForkJoinPool#commonPool() common pool}.
     *
     * @param delegate the sync client to wrap
     * @return an async client backed by {@code delegate}
     */
    public static AsyncSmplClient wrap(SmplClient delegate) {
        return wrap(delegate, ForkJoinPool.commonPool());
    }

    /**
     * Wraps an existing sync client, running blocking I/O on the given executor.
     *
     * @param delegate the sync client to wrap
     * @param executor the executor used to run blocking I/O off the calling thread
     * @return an async client backed by {@code delegate}
     */
    public static AsyncSmplClient wrap(SmplClient delegate, Executor executor) {
        return new AsyncSmplClient(delegate, executor);
    }

    /**
     * Returns the underlying sync runtime client (flag eval, config reads, etc.).
     *
     * @return the wrapped sync client
     */
    public SmplClient sync() {
        return delegate;
    }

    /**
     * Returns the executor used to run blocking I/O off the calling thread.
     *
     * @return the executor backing this client's async methods
     */
    public Executor executor() {
        return executor;
    }

    /**
     * Optionally pre-warm the SDK and wait until the live socket is up.
     *
     * <p>Eagerly opens the live-updates WebSocket and waits for the handshake
     * to complete. After the returned future completes, any {@code onChange}
     * listeners receive every server event from this point forward — including
     * events triggered by writes the caller fires immediately afterward.</p>
     *
     * <p>Optional: config and flags connect lazily on first live use, so this
     * is purely a pre-warm / WebSocket-ready barrier. Logging integration is
     * <em>not</em> connected here — call {@code client.logging.install()}
     * separately if you want it (it installs adapters and hooks into your
     * application's logger, which should be opt-in).</p>
     *
     * @param timeout the maximum time to wait for the live-updates WebSocket
     *     handshake before giving up
     * @return a future that completes when the live socket is up; it completes
     *     exceptionally with a {@link java.util.concurrent.CompletionException}
     *     wrapping a {@link com.smplkit.errors.TimeoutError} if the WebSocket
     *     fails to connect within {@code timeout}, or wrapping an
     *     {@link InterruptedException} if the waiting thread is interrupted
     */
    public CompletableFuture<Void> waitUntilReady(Duration timeout) {
        return CompletableFuture.runAsync(() -> {
            try {
                delegate.waitUntilReady(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    /**
     * Pre-warms the SDK and waits until the live socket is up, using the
     * default 10-second timeout. Equivalent to
     * {@code waitUntilReady(Duration.ofSeconds(10))}.
     *
     * @return a future that completes when the live socket is up; it completes
     *     exceptionally with a {@link java.util.concurrent.CompletionException}
     *     wrapping a {@link com.smplkit.errors.TimeoutError} if the WebSocket
     *     fails to connect within the default timeout, or wrapping an
     *     {@link InterruptedException} if the waiting thread is interrupted
     */
    public CompletableFuture<Void> waitUntilReady() {
        return waitUntilReady(Duration.ofSeconds(10));
    }

    /**
     * Stashes {@code contexts} as the current request's evaluation context.
     *
     * <p>Typical use is from middleware — set the context once at request entry
     * and every {@code flag.get()} (and other context-sensitive evaluations)
     * inside that request automatically picks it up. Per-thread isolation keeps
     * concurrent requests from cross-contaminating.</p>
     *
     * <p>Each unique {@code (type, key)} is also registered with the platform,
     * deduplicated via an LRU and sent in the background. An empty list clears
     * any registration step.</p>
     *
     * @param contexts the contexts to make active for the current thread (e.g.
     *     the request's user and account); an empty list clears any
     *     registration step
     */
    public void setContext(List<Context> contexts) {
        delegate.setContext(contexts);
    }

    /**
     * Clears the current thread's evaluation context, so subsequent
     * evaluations on this thread carry no context until the next
     * {@link #setContext}.
     */
    public void clearContext() {
        delegate.clearContext();
    }

    /**
     * Returns the configured environment.
     *
     * @return the environment this client connects to (e.g. {@code "production"}),
     *     or {@code null} if none was resolved
     */
    public String environment() {
        return delegate.environment();
    }

    /**
     * Returns the configured service name.
     *
     * @return the service name this client identifies as (e.g.
     *     {@code "user-service"}), or {@code null} if none was resolved
     */
    public String service() {
        return delegate.service();
    }

    /** Releases all resources held by this client (and its underlying sync delegate). */
    @Override
    public void close() {
        delegate.close();
    }
}
