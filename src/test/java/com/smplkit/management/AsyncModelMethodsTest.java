package com.smplkit.management;

import com.smplkit.config.Config;
import com.smplkit.flags.Flag;
import com.smplkit.logging.LogGroup;
import com.smplkit.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors Python rule 12: active-record models expose both {@code save()}
 * and {@code saveAsync()} on the same instance. Customer picks sync or async
 * at the call site.
 *
 * <p>Models are constructed via the management factories (the constructors
 * are package-private). Save calls in this test will fail because we have no
 * real backend — we verify the {@code CompletableFuture} shape and the
 * executor wiring rather than the success path.</p>
 */
class AsyncModelMethodsTest {

    @Test
    void config_saveAsync_returnsCompletableFuture() {
        try (SmplManagementClient mc = SmplManagementClient.create("k")) {
            Config cfg = mc.config.new_("test-cfg");
            CompletableFuture<Void> f = cfg.saveAsync();
            assertNotNull(f);
            // We don't await — would attempt I/O.
        }
    }

    @Test
    void config_saveAsync_runsOnProvidedExecutor() {
        try (SmplManagementClient mc = SmplManagementClient.create("k")) {
            Config cfg = mc.config.new_("test-cfg");
            AtomicInteger ran = new AtomicInteger();
            java.util.concurrent.Executor counting = r -> { ran.incrementAndGet(); r.run(); };
            // Save will fail (no real backend) — we only verify the executor ran.
            cfg.saveAsync(counting).exceptionally(t -> null).join();
            assertEquals(1, ran.get());
        }
    }

    @Test
    void flag_saveAsync_returnsCompletableFuture() {
        try (SmplManagementClient mc = SmplManagementClient.create("k")) {
            Flag<Boolean> f = mc.flags.newBooleanFlag("test-flag", false);
            CompletableFuture<Void> save = f.saveAsync();
            assertNotNull(save);
        }
    }

    @Test
    void logger_saveAsync_returnsCompletableFuture() {
        try (SmplManagementClient mc = SmplManagementClient.create("k")) {
            Logger lg = mc.loggers.new_("test.logger");
            CompletableFuture<Void> f = lg.saveAsync();
            assertNotNull(f);
        }
    }

    @Test
    void logGroup_saveAsync_returnsCompletableFuture() {
        try (SmplManagementClient mc = SmplManagementClient.create("k")) {
            LogGroup grp = mc.logGroups.new_("test-group");
            CompletableFuture<Void> f = grp.saveAsync();
            assertNotNull(f);
        }
    }

    @Test
    void environment_saveAsync_returnsCompletableFuture() {
        try (SmplManagementClient mc = SmplManagementClient.create("k")) {
            Environment env = mc.environments.new_("env-id", "Name", null,
                    EnvironmentClassification.STANDARD);
            CompletableFuture<Void> f = env.saveAsync();
            assertNotNull(f);
        }
    }
}
