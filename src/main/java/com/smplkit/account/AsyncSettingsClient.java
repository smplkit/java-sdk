package com.smplkit.account;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Async account-settings get/save ({@code client.account.settings}).
 *
 * <p>Thin wrapper around {@link SettingsClient}; reads perform their network
 * round-trip on the supplied executor. The returned {@link AccountSettings}
 * exposes {@link AccountSettings#saveAsync()} for the async save round-trip.</p>
 */
public final class AsyncSettingsClient {

    private final SettingsClient sync;
    private final Executor executor;

    AsyncSettingsClient(SettingsClient sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    /**
     * Fetch the authenticated account's current settings.
     *
     * @return a future completing with an {@link AccountSettings} active record;
     *     mutate its fields and call {@link AccountSettings#saveAsync()} to
     *     persist the changes
     */
    public CompletableFuture<AccountSettings> get() {
        return CompletableFuture.supplyAsync(sync::get, executor);
    }
}
