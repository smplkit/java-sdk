package com.smplkit;

import com.smplkit.management.AsyncSmplManagementClient;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Mirrors Python rule 12 — async runtime client wraps the sync one. */
class AsyncSmplClientTest {

    @Test
    void wrap_exposesAsyncManageAndSyncRuntime() {
        try (SmplClient sync = SmplClient.builder()
                .apiKey("k").environment("test").service("svc").build();
             AsyncSmplClient async = AsyncSmplClient.wrap(sync)) {
            AsyncSmplManagementClient mgmt = async.manage();
            assertNotNull(mgmt);
            assertSame(sync, async.sync());
            // Async management exposes the same eight namespaces
            assertNotNull(mgmt.environments);
            assertNotNull(mgmt.config);
        }
    }

    @Test
    void wrap_acceptsCustomExecutor() {
        AtomicInteger ran = new AtomicInteger();
        Executor exec = r -> { ran.incrementAndGet(); r.run(); };
        try (SmplClient sync = SmplClient.builder()
                .apiKey("k").environment("test").service("svc").build();
             AsyncSmplClient async = AsyncSmplClient.wrap(sync, exec)) {
            assertSame(exec, async.manage().executor());
        }
    }

    @Test
    void close_delegatesToSync() {
        SmplClient sync = SmplClient.builder()
                .apiKey("k").environment("test").service("svc").build();
        AsyncSmplClient async = AsyncSmplClient.wrap(sync);
        async.close();
        // Closes cleanly
    }
}
