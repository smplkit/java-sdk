package com.smplkit.logging.adapters;

import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.logging.LoggingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for adapter auto-loading and registration in LoggingClient.
 */
class AutoLoadTest {

    private LoggersApi mockLoggersApi;
    private LogGroupsApi mockLogGroupsApi;
    private LoggingClient client;

    @BeforeEach
    void setUp() {
        mockLoggersApi = mock(LoggersApi.class);
        mockLogGroupsApi = mock(LogGroupsApi.class);
        client = new LoggingClient(mockLoggersApi, mockLogGroupsApi,
                HttpClient.newHttpClient(), "test-api-key");
        client.setEnvironment("production");
        client.setService("test-service");
    }

    private void stubEmptyResponses() throws ApiException {
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(new ArrayList<>());
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(groupResp);
    }

    @Test
    void autoLoad_findsJul() throws ApiException {
        stubEmptyResponses();
        client.start();

        List<LoggingAdapter> adapters = client.getAdapters();
        assertTrue(adapters.stream().anyMatch(a -> a.name().equals("jul")),
                "JUL adapter should always be auto-loaded");
    }

    @Test
    void autoLoad_findsLogback() throws ApiException {
        stubEmptyResponses();
        client.start();

        List<LoggingAdapter> adapters = client.getAdapters();
        assertTrue(adapters.stream().anyMatch(a -> a.name().equals("slf4j-logback")),
                "Logback adapter should be auto-loaded when on classpath");
    }

    @Test
    void autoLoad_findsLog4j2() throws ApiException {
        stubEmptyResponses();
        client.start();

        List<LoggingAdapter> adapters = client.getAdapters();
        assertTrue(adapters.stream().anyMatch(a -> a.name().equals("log4j2")),
                "Log4j2 adapter should be auto-loaded when on classpath");
    }

    @Test
    void registerAdapter_disablesAutoLoad() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        when(mockAdapter.name()).thenReturn("custom");
        when(mockAdapter.discover()).thenReturn(List.of());

        client.registerAdapter(mockAdapter);
        client.start();

        List<LoggingAdapter> adapters = client.getAdapters();
        assertEquals(1, adapters.size());
        assertEquals("custom", adapters.get(0).name());
    }

    @Test
    void registerAdapter_afterStartThrows() throws ApiException {
        stubEmptyResponses();
        client.start();

        LoggingAdapter mockAdapter = mock(LoggingAdapter.class);
        assertThrows(IllegalStateException.class, () -> client.registerAdapter(mockAdapter));
    }

    @Test
    void multipleAdapters_allCalled() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter adapter1 = mock(LoggingAdapter.class);
        when(adapter1.name()).thenReturn("mock1");
        when(adapter1.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme", "INFO")
        ));

        LoggingAdapter adapter2 = mock(LoggingAdapter.class);
        when(adapter2.name()).thenReturn("mock2");
        when(adapter2.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme", "DEBUG")
        ));

        client.registerAdapter(adapter1);
        client.registerAdapter(adapter2);
        client.start();

        // Both should have discover() called
        verify(adapter1).discover();
        verify(adapter2).discover();

        // Both should have installHook() called
        verify(adapter1).installHook(any());
        verify(adapter2).installHook(any());
    }

    @Test
    void close_callsUninstallHookOnAllAdapters() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter adapter1 = mock(LoggingAdapter.class);
        when(adapter1.name()).thenReturn("mock1");
        when(adapter1.discover()).thenReturn(List.of());

        LoggingAdapter adapter2 = mock(LoggingAdapter.class);
        when(adapter2.name()).thenReturn("mock2");
        when(adapter2.discover()).thenReturn(List.of());

        client.registerAdapter(adapter1);
        client.registerAdapter(adapter2);
        client.start();
        client.close();

        verify(adapter1).uninstallHook();
        verify(adapter2).uninstallHook();
        assertFalse(client.isStarted());
    }

    @Test
    void adapterDiscoverFailure_doesNotBlockOtherAdapters() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter failAdapter = mock(LoggingAdapter.class);
        when(failAdapter.name()).thenReturn("fail");
        when(failAdapter.discover()).thenThrow(new RuntimeException("discover failed"));

        LoggingAdapter goodAdapter = mock(LoggingAdapter.class);
        when(goodAdapter.name()).thenReturn("good");
        when(goodAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme", "INFO")
        ));

        client.registerAdapter(failAdapter);
        client.registerAdapter(goodAdapter);
        client.start();

        // Good adapter should still be called
        verify(goodAdapter).discover();
        assertTrue(client.isStarted());
    }

    @Test
    void adapterApplyLevelFailure_doesNotBlockOtherAdapters() throws ApiException {
        // Set up a managed logger that will trigger applyLevel
        java.util.logging.Logger.getLogger("com.acme.multi");

        var loggerResp = new LoggerListResponse();
        var lr = new com.smplkit.internal.generated.logging.model.LoggerResource();
        var attrs = new com.smplkit.internal.generated.logging.model.Logger();
        attrs.setKey("com.acme.multi");
        attrs.setName("Multi");
        attrs.setLevel("DEBUG");
        attrs.setManaged(true);
        lr.setId("id-1");
        lr.setType(com.smplkit.internal.generated.logging.model.LoggerResource.TypeEnum.LOGGER);
        lr.setAttributes(attrs);
        loggerResp.setData(new ArrayList<>(List.of(lr)));
        when(mockLoggersApi.listLoggers(null, null)).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(new ArrayList<>());
        when(mockLogGroupsApi.listLogGroups()).thenReturn(groupResp);

        LoggingAdapter failAdapter = mock(LoggingAdapter.class);
        when(failAdapter.name()).thenReturn("fail");
        when(failAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.multi", "INFO")
        ));
        doThrow(new RuntimeException("apply failed")).when(failAdapter).applyLevel(any(), any());

        LoggingAdapter goodAdapter = mock(LoggingAdapter.class);
        when(goodAdapter.name()).thenReturn("good");
        when(goodAdapter.discover()).thenReturn(List.of(
                new DiscoveredLogger("com.acme.multi", "INFO")
        ));

        client.registerAdapter(failAdapter);
        client.registerAdapter(goodAdapter);
        client.start();

        // Good adapter should still receive applyLevel
        verify(goodAdapter).applyLevel("com.acme.multi", "DEBUG");
    }

    @Test
    void installHookFailure_doesNotBlockOtherAdapters() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter failAdapter = mock(LoggingAdapter.class);
        when(failAdapter.name()).thenReturn("fail");
        when(failAdapter.discover()).thenReturn(List.of());
        doThrow(new RuntimeException("hook failed")).when(failAdapter).installHook(any());

        LoggingAdapter goodAdapter = mock(LoggingAdapter.class);
        when(goodAdapter.name()).thenReturn("good");
        when(goodAdapter.discover()).thenReturn(List.of());

        client.registerAdapter(failAdapter);
        client.registerAdapter(goodAdapter);
        client.start();

        // Good adapter should still have installHook called
        verify(goodAdapter).installHook(any());
        assertTrue(client.isStarted());
    }

    @Test
    void close_handlesUninstallHookFailure() throws ApiException {
        stubEmptyResponses();

        LoggingAdapter failAdapter = mock(LoggingAdapter.class);
        when(failAdapter.name()).thenReturn("fail");
        when(failAdapter.discover()).thenReturn(List.of());
        doThrow(new RuntimeException("uninstall failed")).when(failAdapter).uninstallHook();

        LoggingAdapter goodAdapter = mock(LoggingAdapter.class);
        when(goodAdapter.name()).thenReturn("good");
        when(goodAdapter.discover()).thenReturn(List.of());

        client.registerAdapter(failAdapter);
        client.registerAdapter(goodAdapter);
        client.start();

        // close should not throw even if uninstallHook fails
        client.close();

        verify(failAdapter).uninstallHook();
        verify(goodAdapter).uninstallHook();
        assertFalse(client.isStarted());
    }
}
