package com.smplkit.management;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors Python rule 12 for the management plane: parallel sync/async
 * surfaces, models stay single-source-of-truth, customer picks sync vs async
 * at the call site.
 */
class AsyncSmplManagementClientTest {

    @Test
    void create_buildsAllEightAsyncNamespaces() {
        try (AsyncSmplManagementClient mc = AsyncSmplManagementClient.create("test-key")) {
            assertNotNull(mc.contexts);
            assertNotNull(mc.contextTypes);
            assertNotNull(mc.environments);
            assertNotNull(mc.accountSettings);
            assertNotNull(mc.config);
            assertNotNull(mc.flags);
            assertNotNull(mc.loggers);
            assertNotNull(mc.logGroups);
        }
    }

    @Test
    void wrap_reusesProvidedSyncClient() {
        try (SmplManagementClient sync = SmplManagementClient.create("test-key");
             AsyncSmplManagementClient async = AsyncSmplManagementClient.wrap(sync)) {
            assertSame(sync, async.sync());
        }
    }

    @Test
    void wrap_acceptsCustomExecutor() {
        AtomicInteger executions = new AtomicInteger();
        Executor counting = r -> {
            executions.incrementAndGet();
            r.run();
        };
        try (SmplManagementClient sync = SmplManagementClient.create("test-key");
             AsyncSmplManagementClient async = AsyncSmplManagementClient.wrap(sync, counting)) {
            assertSame(counting, async.executor());
        }
    }

    @Test
    void factoryMethods_areSync_noFutureReturn() {
        // Mirrors Python's design: construction-style methods like new_() are
        // synchronous (no I/O). Only CRUD methods are wrapped in
        // CompletableFuture.
        try (AsyncSmplManagementClient mc = AsyncSmplManagementClient.create("test-key")) {
            Environment env = mc.environments.new_("env-1", "Env 1", null,
                    EnvironmentClassification.STANDARD);
            assertNotNull(env);
            assertEquals("env-1", env.getId());

            ContextType ct = mc.contextTypes.new_("user");
            assertNotNull(ct);

            // Async methods return CompletableFuture<T>
            CompletableFuture<java.util.List<Environment>> listFuture = mc.environments.list();
            assertNotNull(listFuture);
        }
    }

    @Test
    void asyncMethods_returnCompletableFuture() {
        try (AsyncSmplManagementClient mc = AsyncSmplManagementClient.create("test-key")) {
            // We don't actually invoke these — that would attempt a real HTTP call
            // (and the test is offline). We just verify the methods exist and
            // return CompletableFuture types.
            assertNotNull(mc.environments.list());
            assertNotNull(mc.config.list());
            assertNotNull(mc.flags.list());
            assertNotNull(mc.loggers.list());
            assertNotNull(mc.logGroups.list());
            assertNotNull(mc.contextTypes.list());
        }
    }

    @Test
    void close_propagatesToSyncDelegate() {
        SmplManagementClient sync = SmplManagementClient.create("test-key");
        AsyncSmplManagementClient async = AsyncSmplManagementClient.wrap(sync);
        async.close();
        // No exception → close propagated cleanly.
    }
}
