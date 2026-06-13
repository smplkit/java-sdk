package com.smplkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Mirrors Python rule 12 — the async runtime client wraps the sync one. */
class AsyncSmplClientTest {

    /** Inline executor so wrapped futures resolve on the calling thread. */
    private static final Executor INLINE = Runnable::run;

    private static SmplClient newSync() {
        return SmplClient.builder()
                .apiKey("k").environment("test").service("svc").disableTelemetry(true).build();
    }

    @Test
    void wrap_exposesAsyncSurfaceAndSyncRuntime() {
        try (SmplClient sync = newSync();
             AsyncSmplClient async = AsyncSmplClient.wrap(sync)) {
            assertSame(sync, async.sync());
            // All seven async namespaces are exposed as fields.
            assertNotNull(async.platform);
            assertNotNull(async.account);
            assertNotNull(async.config);
            assertNotNull(async.flags);
            assertNotNull(async.logging);
            assertNotNull(async.audit);
            assertNotNull(async.jobs);
            // Sub-clients reach through to the platform CRUD resources.
            assertNotNull(async.platform.environments);
            assertEquals("test", async.environment());
            assertEquals("svc", async.service());
        }
    }

    @Test
    void wrap_acceptsCustomExecutor() {
        AtomicInteger ran = new AtomicInteger();
        Executor exec = r -> { ran.incrementAndGet(); r.run(); };
        try (SmplClient sync = newSync();
             AsyncSmplClient async = AsyncSmplClient.wrap(sync, exec)) {
            assertSame(exec, async.executor());
        }
    }

    @Test
    void close_delegatesToSync() {
        SmplClient sync = newSync();
        AsyncSmplClient async = AsyncSmplClient.wrap(sync);
        async.close();
        // Closes cleanly.
    }

    @Test
    void create_noArg_resolvesFromEnvAndWrapsCommonPool() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_async_create");
        setEnv("SMPLKIT_ENVIRONMENT", "async-env");
        setEnv("SMPLKIT_SERVICE", "async-svc");
        setEnv("SMPLKIT_DISABLE_TELEMETRY", "1");
        try (AsyncSmplClient async = AsyncSmplClient.create()) {
            assertNotNull(async);
            assertEquals("async-env", async.environment());
            assertEquals("async-svc", async.service());
            assertNotNull(async.executor());
        } finally {
            clearEnv("SMPLKIT_API_KEY");
            clearEnv("SMPLKIT_ENVIRONMENT");
            clearEnv("SMPLKIT_SERVICE");
            clearEnv("SMPLKIT_DISABLE_TELEMETRY");
        }
    }

    @Test
    void waitUntilReady_withTimeout_completesOnInlineExecutor() {
        try (SmplClient sync = newSync();
             AsyncSmplClient async = AsyncSmplClient.wrap(sync, INLINE)) {
            // disableDialForTests (WsFreeTestExtension) marks the socket
            // connected immediately, so the wait returns without blocking.
            CompletableFuture<Void> future = async.waitUntilReady(Duration.ofSeconds(1));
            assertDoesNotThrow(() -> future.get());
            assertTrue(future.isDone());
            assertFalse(future.isCompletedExceptionally());
        }
    }

    @Test
    void waitUntilReady_noArg_usesDefaultTimeout() {
        try (SmplClient sync = newSync();
             AsyncSmplClient async = AsyncSmplClient.wrap(sync, INLINE)) {
            CompletableFuture<Void> future = async.waitUntilReady();
            assertDoesNotThrow(() -> future.get());
            assertTrue(future.isDone());
        }
    }

    @Test
    void waitUntilReady_interrupted_completesExceptionallyAndReinterrupts() throws Exception {
        // Spy a real sync client (so its sub-client fields are non-null) and
        // stub waitUntilReady to throw InterruptedException, driving the catch
        // arm of the async wrapper.
        SmplClient spySync = spy(newSync());
        doThrow(new InterruptedException("boom")).when(spySync).waitUntilReady(any(Duration.class));

        try (AsyncSmplClient async = AsyncSmplClient.wrap(spySync, INLINE)) {
            CompletableFuture<Void> future = async.waitUntilReady(Duration.ofMillis(10));

            assertTrue(future.isCompletedExceptionally());
            CompletionException ce = assertThrows(CompletionException.class, future::join);
            assertInstanceOf(InterruptedException.class, ce.getCause());
            // The catch arm re-sets the interrupt flag on the worker thread; the
            // inline executor ran on this thread, so clear it to avoid leaking a
            // dangling interrupt into the rest of the suite.
            assertTrue(Thread.interrupted(), "interrupt flag should have been set and is now cleared");
        }
    }

    @Test
    void setContextAndClearContext_delegateToSync() {
        SmplClient spySync = spy(newSync());
        try (AsyncSmplClient async = AsyncSmplClient.wrap(spySync, INLINE)) {
            List<Context> contexts = List.of(new Context("user", "u-1"));
            async.setContext(contexts);
            async.clearContext();

            verify(spySync).setContext(contexts);
            verify(spySync).clearContext();
        }
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(env)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearEnv(String key) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(env)).remove(key);
    }
}
