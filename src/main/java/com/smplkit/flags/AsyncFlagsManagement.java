package com.smplkit.flags;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Async wrapper around {@link FlagsManagement}. */
public final class AsyncFlagsManagement {

    private final FlagsManagement sync;
    private final Executor executor;

    public AsyncFlagsManagement(FlagsManagement sync, Executor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    // Factory methods are synchronous (no I/O — they just create unsaved instances).

    public Flag<Boolean> newBooleanFlag(String id, boolean defaultValue) {
        return sync.newBooleanFlag(id, defaultValue);
    }

    public Flag<Boolean> newBooleanFlag(String id, boolean defaultValue,
                                        String name, String description) {
        return sync.newBooleanFlag(id, defaultValue, name, description);
    }

    public Flag<String> newStringFlag(String id, String defaultValue) {
        return sync.newStringFlag(id, defaultValue);
    }

    public Flag<String> newStringFlag(String id, String defaultValue,
                                      String name, String description) {
        return sync.newStringFlag(id, defaultValue, name, description);
    }

    public Flag<String> newStringFlag(String id, String defaultValue,
                                      String name, String description,
                                      List<Map<String, Object>> values) {
        return sync.newStringFlag(id, defaultValue, name, description, values);
    }

    public Flag<Number> newNumberFlag(String id, Number defaultValue) {
        return sync.newNumberFlag(id, defaultValue);
    }

    public Flag<Number> newNumberFlag(String id, Number defaultValue,
                                      String name, String description) {
        return sync.newNumberFlag(id, defaultValue, name, description);
    }

    public Flag<Number> newNumberFlag(String id, Number defaultValue,
                                      String name, String description,
                                      List<Map<String, Object>> values) {
        return sync.newNumberFlag(id, defaultValue, name, description, values);
    }

    public Flag<Object> newJsonFlag(String id, Object defaultValue) {
        return sync.newJsonFlag(id, defaultValue);
    }

    public Flag<Object> newJsonFlag(String id, Object defaultValue,
                                    String name, String description) {
        return sync.newJsonFlag(id, defaultValue, name, description);
    }

    public Flag<Object> newJsonFlag(String id, Object defaultValue,
                                    String name, String description,
                                    List<Map<String, Object>> values) {
        return sync.newJsonFlag(id, defaultValue, name, description, values);
    }

    // CRUD methods are async — return CompletableFuture<T>.

    public CompletableFuture<Flag<?>> get(String id) {
        return CompletableFuture.supplyAsync(() -> sync.get(id), executor);
    }

    public CompletableFuture<List<Flag<?>>> list() {
        return CompletableFuture.supplyAsync(sync::list, executor);
    }

    public CompletableFuture<Void> delete(String id) {
        return CompletableFuture.runAsync(() -> sync.delete(id), executor);
    }
}
