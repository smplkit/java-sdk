package com.smplkit.management;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link AccountSettingsClient}. */
public final class AsyncAccountSettingsClient {

    private final AccountSettingsClient sync;
    private final Executor executor;

    AsyncAccountSettingsClient(AccountSettingsClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    public CompletableFuture<AccountSettings> get() {
        return CompletableFuture.supplyAsync(sync::get, executor);
    }
}
