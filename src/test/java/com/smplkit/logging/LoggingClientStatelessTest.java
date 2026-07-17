package com.smplkit.logging;

import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.logging.adapters.LoggingAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stateless live surface for {@link LoggingClient} — {@code streaming(false)}.
 *
 * <p>{@code install()} still loads adapters, discovers loggers, flushes the
 * registration buffer, and fetches + applies levels once, blocking — but no
 * WebSocket is opened, and registration threshold flushes run inline on the
 * calling thread instead of on a one-shot background thread. Mock
 * verifications run immediately after the triggering call — no latch, no
 * sleep — proving no background thread sits in the path.</p>
 */
class LoggingClientStatelessTest {

    private static LoggersApi mockLoggersApi() throws ApiException {
        LoggersApi loggersApi = mock(LoggersApi.class);
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>());
        when(loggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        return loggersApi;
    }

    private static LogGroupsApi mockLogGroupsApi() throws ApiException {
        LogGroupsApi logGroupsApi = mock(LogGroupsApi.class);
        LogGroupListResponse groups = new LogGroupListResponse();
        groups.setData(new ArrayList<>());
        when(logGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groups);
        return logGroupsApi;
    }

    private static LoggingClient statelessClient(LoggersApi loggersApi, LogGroupsApi logGroupsApi) {
        return LoggingClient.standalone(loggersApi, logGroupsApi,
                HttpClient.newHttpClient(), "sk_test", "http://127.0.0.1:1", false);
    }

    @Test
    void statelessInstall_fetchesAndAppliesButSkipsWebSocket() throws Exception {
        LoggersApi loggersApi = mockLoggersApi();
        LogGroupsApi logGroupsApi = mockLogGroupsApi();
        LoggingClient client = statelessClient(loggersApi, logGroupsApi);

        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of());
        client.registerAdapter(adapter);

        client.install();
        assertTrue(client.isInstalled());

        // Levels were fetched once (blocking) during install.
        verify(loggersApi, times(1)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
        verify(logGroupsApi, times(1)).listLogGroups(isNull(), any(), any(), isNull());

        // No WebSocket was created or owned despite the non-null app base URL.
        assertNull(field(client, "wsManager"));
        assertFalse((boolean) field(client, "ownsWs"));

        // refresh() re-fetches on demand.
        client.refresh();
        verify(loggersApi, times(2)).listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());

        client.close();
        assertFalse(client.isInstalled());
    }

    @Test
    void register_thresholdFlushRunsInline() throws Exception {
        LoggersApi loggersApi = mockLoggersApi();
        LoggingClient client = statelessClient(loggersApi, mockLogGroupsApi());

        List<LoggerSource> sources = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            sources.add(new LoggerSource("logger." + i, null, null, "svc", "staging"));
        }
        client.loggers.register(sources);

        // Inline: the bulk POST already happened on this thread.
        verify(loggersApi, times(1)).bulkRegisterLoggers(any());
        assertEquals(0, client.loggers.pendingCount());
        client.close();
    }

    @Test
    void register_thresholdFlushInline_swallowsFailure() throws Exception {
        LoggersApi loggersApi = mockLoggersApi();
        when(loggersApi.bulkRegisterLoggers(any())).thenThrow(new ApiException(503, "unavailable"));
        LoggingClient client = statelessClient(loggersApi, mockLogGroupsApi());

        List<LoggerSource> sources = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            sources.add(new LoggerSource("logger." + i, null, null, "svc", "staging"));
        }
        assertDoesNotThrow(() -> client.loggers.register(sources));
        verify(loggersApi, times(1)).bulkRegisterLoggers(any());
        client.close();
    }

    private static Object field(LoggingClient client, String name) throws Exception {
        Field f = LoggingClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(client);
    }
}
