package com.smplkit.logging;

import com.smplkit.SharedWebSocket;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.logging.adapters.LoggingAdapter;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the standalone {@link LoggingClient} construction paths — the
 * {@link LoggingClientBuilder} (every setter + {@code build()}), the
 * {@code create} factories, and the owned-transport / owned-WebSocket lifecycle.
 *
 * <p>The owned-WebSocket lines that {@link LoggingClient#install()} reaches for a
 * standalone client (creating and later tearing down its own
 * {@link SharedWebSocket}) execute synchronously on the calling thread; the
 * actual socket dial happens on a daemon thread pointed at a refused localhost
 * address, so no real connection is ever made and no listener races a coverage
 * read.</p>
 */
class LoggingClientStandaloneTest {

    // -------------------------------------------------------- builder

    @Test
    void builder_allSettersThenBuild() {
        LoggingClient client = LoggingClient.builder()
                .profile("default")
                .apiKey("sk_test")
                .environment("staging")
                .baseDomain("example.test")
                .scheme("http")
                .debug(true)
                .extraHeaders(java.util.Map.of("X-Trace", "1"))
                .timeout(Duration.ofSeconds(10))
                .build();
        assertNotNull(client);
        assertNotNull(client.loggers);
        assertNotNull(client.logGroups);
        client.close();
    }

    @Test
    void builder_explicitBaseUrl_isHonored() {
        LoggingClient client = LoggingClient.builder()
                .apiKey("sk_test")
                .baseUrl("http://localhost:9/logging")
                .build();
        assertNotNull(client);
        client.close();
    }

    @Test
    void builder_nullArguments_rejected() {
        LoggingClientBuilder b = LoggingClient.builder();
        assertThrows(NullPointerException.class, () -> b.profile(null));
        assertThrows(NullPointerException.class, () -> b.apiKey(null));
        assertThrows(NullPointerException.class, () -> b.environment(null));
        assertThrows(NullPointerException.class, () -> b.baseUrl(null));
        assertThrows(NullPointerException.class, () -> b.baseDomain(null));
        assertThrows(NullPointerException.class, () -> b.scheme(null));
        assertThrows(NullPointerException.class, () -> b.timeout(null));
        // null extraHeaders is tolerated (treated as empty)
        assertDoesNotThrow(() -> b.extraHeaders(null));
    }

    @Test
    void create_withApiKey_buildsStandalone() {
        try (LoggingClient client = LoggingClient.create("sk_test")) {
            assertNotNull(client.loggers);
            assertFalse(client.isInstalled());
        }
    }

    // -------------------------------------------------------- owned WS lifecycle

    @Test
    void standalone_install_createsOwnedWs_closeTearsItDown() throws ApiException {
        LoggersApi loggersApi = mock(LoggersApi.class);
        LogGroupsApi logGroupsApi = mock(LogGroupsApi.class);
        // No real fetch: mock the list endpoints.
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>());
        when(loggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        LogGroupListResponse groups = new LogGroupListResponse();
        groups.setData(new ArrayList<>());
        when(logGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groups);

        // Refused localhost port -> WS daemon dies instantly without a real connection.
        LoggingClient client = LoggingClient.standalone(
                loggersApi, logGroupsApi, HttpClient.newHttpClient(), "sk_test", "http://127.0.0.1:1");

        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of());
        client.registerAdapter(adapter);

        client.install();
        assertTrue(client.isInstalled());

        // close() tears down the owned WS and resets installed state.
        client.close();
        assertFalse(client.isInstalled());
    }

    @Test
    void wired_install_borrowsWs_closeDoesNotCloseIt() throws ApiException {
        LoggersApi loggersApi = mock(LoggersApi.class);
        LogGroupsApi logGroupsApi = mock(LogGroupsApi.class);
        LoggerListResponse loggers = new LoggerListResponse();
        loggers.setData(new ArrayList<>());
        when(loggersApi.listLoggers(isNull(), isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(loggers);
        LogGroupListResponse groups = new LogGroupListResponse();
        groups.setData(new ArrayList<>());
        when(logGroupsApi.listLogGroups(isNull(), any(), any(), isNull())).thenReturn(groups);

        LoggingClient client = new LoggingClient(loggersApi, logGroupsApi,
                HttpClient.newHttpClient(), "sk_test");
        SharedWebSocket borrowed = mock(SharedWebSocket.class);
        client.setSharedWs(borrowed);

        LoggingAdapter adapter = mock(LoggingAdapter.class);
        when(adapter.name()).thenReturn("noop");
        when(adapter.discover()).thenReturn(List.of());
        client.registerAdapter(adapter);

        client.install();
        client.close();

        // Borrowed WS is unsubscribed but never closed.
        verify(borrowed, never()).close();
    }
}
