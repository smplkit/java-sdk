package com.smplkit.account;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * The Smpl Account client (async) — counterpart of {@link AccountClient}.
 *
 * <p>Reads and saves perform their network round-trips on the supplied
 * executor. Pure CRUD; no {@code install()} required.</p>
 *
 * <p>Wraps a sync {@link AccountClient}, exposing the {@code settings}
 * sub-client in its async form. Construct via {@link #create()} /
 * {@link #create(String)} for standalone use, or {@link #wrap(AccountClient)}
 * to adapt an existing sync client.</p>
 */
public final class AsyncAccountClient {

    private final AccountClient sync;
    private final Executor executor;

    /** Async account-settings get/save ({@code client.account.settings}). */
    public final AsyncSettingsClient settings;

    private AsyncAccountClient(AccountClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
        this.settings = new AsyncSettingsClient(sync.settings, executor);
    }

    /**
     * Construct an {@link AsyncAccountClient} resolving credentials from the
     * standard sources, using the common ForkJoinPool.
     */
    public static AsyncAccountClient create() {
        return wrap(AccountClient.create());
    }

    /** Construct an {@link AsyncAccountClient} with the given API key. */
    public static AsyncAccountClient create(String apiKey) {
        return wrap(AccountClient.create(apiKey));
    }

    /** Wrap an existing sync {@link AccountClient}, using the common ForkJoinPool. */
    public static AsyncAccountClient wrap(AccountClient sync) {
        return wrap(sync, ForkJoinPool.commonPool());
    }

    /** Wrap an existing sync {@link AccountClient} with a custom executor. */
    public static AsyncAccountClient wrap(AccountClient sync, Executor executor) {
        return new AsyncAccountClient(sync, executor);
    }

    /** Returns the wrapped sync client. */
    public AccountClient sync() {
        return sync;
    }

    /** Returns the executor used for async round-trips. */
    public Executor executor() {
        return executor;
    }
}
