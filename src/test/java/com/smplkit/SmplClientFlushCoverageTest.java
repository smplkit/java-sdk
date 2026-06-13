package com.smplkit;

import com.smplkit.config.ConfigClient;
import com.smplkit.flags.FlagsClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.logging.LoggersClient;
import com.smplkit.logging.LoggingClient;
import com.smplkit.platform.ContextsClient;
import com.smplkit.platform.PlatformClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Drives the SmplClient deferred-machinery internals that the normal lifecycle
 * never exercises synchronously: the periodic-flush {@link TimerTask} body, the
 * per-buffer {@code finalFlush} catch arms on {@link SmplClient#close()}, and
 * the {@code registerServiceContext} env-only / nothing-to-register branches.
 */
class SmplClientFlushCoverageTest {

    private static SmplClient newClient(String environment, String service) {
        return newClient(environment, service, null);
    }

    private static SmplClient newClient(String environment, String service, ContextsApi contextsApi) {
        HttpClient http = mock(HttpClient.class);
        return new SmplClient(http, "test-key", environment, service,
                Duration.ofSeconds(5), contextsApi);
    }

    /** Reflectively replace a (final) field on an app-class instance. */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Pull the scheduled periodic-flush TimerTask out of the client's Timer. */
    private static TimerTask extractScheduledTask(SmplClient client) throws Exception {
        Field timerField = SmplClient.class.getDeclaredField("flushTimer");
        timerField.setAccessible(true);
        Timer timer = (Timer) timerField.get(client);
        assertNotNull(timer, "flushTimer should be set after ensureStarted()");

        Field queueField = Timer.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        Object taskQueue = queueField.get(timer);

        Field arrField = taskQueue.getClass().getDeclaredField("queue");
        arrField.setAccessible(true);
        TimerTask[] tasks = (TimerTask[]) arrField.get(taskQueue);
        // Index 0 is unused; the scheduled task sits at index 1.
        assertNotNull(tasks[1], "periodic-flush task should be scheduled");
        return tasks[1];
    }

    @Test
    void periodicFlushTask_normalRun_drainsAllBuffers() throws Exception {
        try (SmplClient client = newClient("staging", "svc")) {
            client.setContext(List.of()); // triggers ensureStarted -> schedules the timer
            TimerTask task = extractScheduledTask(client);
            // Buffers are empty, so each flush is a no-op; the body still runs.
            assertDoesNotThrow(task::run);
        }
    }

    @Test
    void periodicFlushTask_whenClosed_returnsEarly() throws Exception {
        SmplClient client = newClient("staging", "svc");
        client.setContext(List.of());
        TimerTask task = extractScheduledTask(client);
        client.close(); // sets closed = true and cancels the timer
        // The task's first statement is `if (closed) return;`.
        assertDoesNotThrow(task::run);
    }

    @Test
    void periodicFlushTask_flushFailure_isSwallowedAndLogged() throws Exception {
        SmplClient client = newClient("staging", "svc");
        try {
            client.setContext(List.of());
            TimerTask task = extractScheduledTask(client);

            // Replace config with a mock whose flush() throws, so the task's
            // try/catch swallows it.
            ConfigClient throwingConfig = mock(ConfigClient.class);
            doThrow(new RuntimeException("flush boom")).when(throwingConfig).flush();
            setField(client, "config", throwingConfig);

            assertDoesNotThrow(task::run);
            verify(throwingConfig).flush();
        } finally {
            client.close();
        }
    }

    @Test
    void close_finalFlush_swallowsEachBufferFailure() throws Exception {
        SmplClient client = newClient("staging", "svc");

        // platform.contexts.flush() — replace platform with a mock carrying a
        // throwing ContextsClient on its public `contexts` field.
        PlatformClient throwingPlatform = mock(PlatformClient.class);
        ContextsClient throwingContexts = mock(ContextsClient.class);
        doThrow(new RuntimeException("ctx boom")).when(throwingContexts).flush();
        setField(throwingPlatform, "contexts", throwingContexts);
        setField(client, "platform", throwingPlatform);

        // flags.flush()
        FlagsClient throwingFlags = mock(FlagsClient.class);
        doThrow(new RuntimeException("flags boom")).when(throwingFlags).flush();
        setField(client, "flags", throwingFlags);

        // logging.loggers.flush()
        LoggingClient throwingLogging = mock(LoggingClient.class);
        LoggersClient throwingLoggers = mock(LoggersClient.class);
        doThrow(new RuntimeException("loggers boom")).when(throwingLoggers).flush();
        setField(throwingLogging, "loggers", throwingLoggers);
        setField(client, "logging", throwingLogging);

        // config.flush()
        ConfigClient throwingConfig = mock(ConfigClient.class);
        doThrow(new RuntimeException("config boom")).when(throwingConfig).flush();
        setField(client, "config", throwingConfig);

        // close() runs finalFlush(): each flush throws and is caught + logged.
        assertDoesNotThrow(client::close);

        verify(throwingContexts).flush();
        verify(throwingFlags).flush();
        verify(throwingLoggers).flush();
        verify(throwingConfig).flush();
    }

    @Test
    void registerServiceContext_nothingToRegister_returnsBeforePost() throws Exception {
        // No environment and no service -> items list is empty -> the POST is
        // skipped (the `if (items.isEmpty()) return;` arm).
        ContextsApi contextsApi = mock(ContextsApi.class);
        try (SmplClient client = newClient(null, null, contextsApi)) {
            invokeRegisterServiceContext(client);
            verify(contextsApi, never()).bulkRegisterContexts(any());
        }
    }

    @Test
    void registerServiceContext_environmentOnly_registersSingleItem() throws Exception {
        // Environment set, service null -> only the environment item is built.
        ContextsApi contextsApi = mock(ContextsApi.class);
        when(contextsApi.bulkRegisterContexts(any())).thenReturn(null);
        try (SmplClient client = newClient("production", null, contextsApi)) {
            invokeRegisterServiceContext(client);
            verify(contextsApi).bulkRegisterContexts(any());
        }
    }

    @Test
    void registerServiceContext_failure_isSwallowed() throws Exception {
        ContextsApi contextsApi = mock(ContextsApi.class);
        when(contextsApi.bulkRegisterContexts(any()))
                .thenThrow(new ApiException(500, "boom"));
        try (SmplClient client = newClient("production", "svc", contextsApi)) {
            assertDoesNotThrow(() -> invokeRegisterServiceContext(client));
            verify(contextsApi).bulkRegisterContexts(any());
        }
    }

    private static void invokeRegisterServiceContext(SmplClient client) throws Exception {
        var m = SmplClient.class.getDeclaredMethod("registerServiceContext");
        m.setAccessible(true);
        m.invoke(client);
    }
}
