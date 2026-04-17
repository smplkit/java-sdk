package com.smplkit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smplkit.config.ConfigClient;
import com.smplkit.flags.FlagsClient;
import com.smplkit.flags.Flag;
import com.smplkit.logging.LoggingClient;
import com.smplkit.logging.adapters.DiscoveredLogger;
import com.smplkit.logging.adapters.LoggingAdapter;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.Config;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResource;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Integration tests verifying telemetry instrumentation in product clients.
 */
class TelemetryIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpClient mockHttpClient;
    private MetricsReporter reporter;
    private final List<String> capturedBodies = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        mockHttpClient = mock(HttpClient.class);
        HttpResponse<Void> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    req.bodyPublisher().ifPresent(pub -> {
                        var subscriber = new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            final StringBuilder sb = new StringBuilder();
                            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                                subscription.request(Long.MAX_VALUE);
                            }
                            @Override public void onNext(java.nio.ByteBuffer item) {
                                sb.append(new String(item.array(), item.position(), item.remaining()));
                            }
                            @Override public void onError(Throwable throwable) {}
                            @Override public void onComplete() {
                                capturedBodies.add(sb.toString());
                            }
                        };
                        pub.subscribe(subscriber);
                    });
                    return mockResponse;
                });

        reporter = new MetricsReporter(mockHttpClient, "https://app.smplkit.com",
                "test-key", "production", "my-service", 60);
    }

    @AfterEach
    void tearDown() {
        if (reporter != null) {
            reporter.close();
        }
    }

    // -----------------------------------------------------------------------
    // FlagsClient telemetry
    // -----------------------------------------------------------------------

    @Test
    void flagsEvaluationRecordsMetricsOnCacheMiss() throws Exception {
        FlagsApi flagsApi = mock(FlagsApi.class);
        ContextsApi contextsApi = mock(ContextsApi.class);

        when(flagsApi.listFlags(nullable(String.class), nullable(Boolean.class))).thenReturn(
                makeFlagListResponse("test-flag", "production"));

        FlagsClient flags = new FlagsClient(flagsApi, contextsApi,
                mockHttpClient, "key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        flags.setMetrics(reporter);
        flags.setEnvironment("production");
        flags.setParentService("my-service");

        SharedWebSocket ws = new SharedWebSocket();
        ws.setConnectionStatus("connected");
        flags.setSharedWs(ws);

        Flag<Boolean> handle = flags.booleanFlag("test-flag", false);
        handle.get(); // First eval = cache miss

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");

        assertTrue(hasMetric(data, "flags.evaluations"));
        assertTrue(hasMetric(data, "flags.cache_misses"));
    }

    @Test
    void flagsEvaluationRecordsCacheHitOnSecondCall() throws Exception {
        FlagsApi flagsApi = mock(FlagsApi.class);
        ContextsApi contextsApi = mock(ContextsApi.class);

        when(flagsApi.listFlags(nullable(String.class), nullable(Boolean.class))).thenReturn(
                makeFlagListResponse("test-flag", "production"));

        FlagsClient flags = new FlagsClient(flagsApi, contextsApi,
                mockHttpClient, "key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        flags.setMetrics(reporter);
        flags.setEnvironment("production");
        flags.setParentService("my-service");

        SharedWebSocket ws = new SharedWebSocket();
        ws.setConnectionStatus("connected");
        flags.setSharedWs(ws);

        Flag<Boolean> handle = flags.booleanFlag("test-flag", false);
        handle.get(); // miss
        handle.get(); // hit

        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");

        assertTrue(hasMetric(data, "flags.cache_hits"));
        assertTrue(hasMetric(data, "flags.cache_misses"));
        assertTrue(hasMetric(data, "flags.evaluations"));
    }

    @Test
    void flagsNoMetricsWhenDisabled() throws Exception {
        FlagsApi flagsApi = mock(FlagsApi.class);
        ContextsApi contextsApi = mock(ContextsApi.class);

        when(flagsApi.listFlags(nullable(String.class), nullable(Boolean.class))).thenReturn(
                makeFlagListResponse("test-flag", "production"));

        FlagsClient flags = new FlagsClient(flagsApi, contextsApi,
                mockHttpClient, "key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        // metrics NOT set (null)
        flags.setEnvironment("production");
        flags.setParentService("my-service");

        SharedWebSocket ws = new SharedWebSocket();
        ws.setConnectionStatus("connected");
        flags.setSharedWs(ws);

        Flag<Boolean> handle = flags.booleanFlag("test-flag", false);
        assertDoesNotThrow(() -> handle.get());
    }

    // -----------------------------------------------------------------------
    // ConfigClient telemetry
    // -----------------------------------------------------------------------

    @Test
    void configResolveRecordsMetric() throws Exception {
        ConfigsApi configsApi = mock(ConfigsApi.class);
        ConfigListResponse listResp = new ConfigListResponse();
        listResp.setData(List.of());
        when(configsApi.listConfigs(any())).thenReturn(listResp);

        ConfigClient config = new ConfigClient(configsApi, mockHttpClient, "key");
        config.setEnvironment("production");
        config.setMetrics(reporter);

        config.get("my-config");

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertTrue(hasMetric(data, "config.resolutions"));
        assertMetricHasDimension(data, "config.resolutions", "config", "my-config");
    }

    @Test
    void configResolveWithModelRecordsMetric() throws Exception {
        ConfigsApi configsApi = mock(ConfigsApi.class);
        ConfigListResponse listResp = new ConfigListResponse();
        listResp.setData(List.of());
        when(configsApi.listConfigs(any())).thenReturn(listResp);

        ConfigClient config = new ConfigClient(configsApi, mockHttpClient, "key");
        config.setEnvironment("production");
        config.setMetrics(reporter);

        config.get("my-config", Map.class);

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertTrue(hasMetric(data, "config.resolutions"));
    }

    @Test
    void configChangesRecordedOnRefresh() throws Exception {
        ConfigsApi configsApi = mock(ConfigsApi.class);

        // First call (for _connectInternal): return config with value "old"
        ConfigListResponse firstResp = new ConfigListResponse();
        ConfigResource cr1 = new ConfigResource();
        cr1.setId("my-config");
        var attrs1 = new Config();
        attrs1.setName("My Config");
        var items1 = new HashMap<String, com.smplkit.internal.generated.config.model.ConfigItemDefinition>();
        var item1 = new com.smplkit.internal.generated.config.model.ConfigItemDefinition();
        item1.setValue("old-host");
        item1.setType(com.smplkit.internal.generated.config.model.ConfigItemDefinition.TypeEnum.STRING);
        items1.put("host", item1);
        attrs1.setItems(items1);
        cr1.setAttributes(attrs1);
        firstResp.setData(List.of(cr1));

        // Second call (for refresh): return config with value "new"
        ConfigListResponse secondResp = new ConfigListResponse();
        ConfigResource cr2 = new ConfigResource();
        cr2.setId("my-config");
        var attrs2 = new Config();
        attrs2.setName("My Config");
        var items2 = new HashMap<String, com.smplkit.internal.generated.config.model.ConfigItemDefinition>();
        var item2 = new com.smplkit.internal.generated.config.model.ConfigItemDefinition();
        item2.setValue("new-host");
        item2.setType(com.smplkit.internal.generated.config.model.ConfigItemDefinition.TypeEnum.STRING);
        items2.put("host", item2);
        attrs2.setItems(items2);
        cr2.setAttributes(attrs2);
        secondResp.setData(List.of(cr2));

        when(configsApi.listConfigs(nullable(String.class)))
                .thenReturn(firstResp)
                .thenReturn(secondResp);

        ConfigClient config = new ConfigClient(configsApi, mockHttpClient, "key");
        config.setEnvironment("production");
        config.setMetrics(reporter);

        // Initial connect
        config.get("my-config");
        // Refresh triggers diff
        config.refresh();

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertTrue(hasMetric(data, "config.changes"));
        assertMetricHasDimension(data, "config.changes", "config", "my-config");
    }

    // -----------------------------------------------------------------------
    // LoggingClient telemetry
    // -----------------------------------------------------------------------

    @Test
    void loggingDiscoveredRecordsMetric() throws Exception {
        LoggersApi loggersApi = mock(LoggersApi.class);
        LogGroupsApi logGroupsApi = mock(LogGroupsApi.class);

        // Empty responses for fetch
        LoggerListResponse loggerResp = new LoggerListResponse();
        loggerResp.setData(List.of());
        when(loggersApi.listLoggers(nullable(Boolean.class), nullable(String.class), nullable(String.class))).thenReturn(loggerResp);
        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(List.of());
        when(logGroupsApi.listLogGroups()).thenReturn(groupResp);

        LoggingClient logging = new LoggingClient(loggersApi, logGroupsApi, mockHttpClient, "key");
        logging.setEnvironment("production");
        logging.setService("my-service");
        logging.setMetrics(reporter);

        // Register a mock adapter that discovers loggers
        logging.registerAdapter(new LoggingAdapter() {
            @Override public String name() { return "test"; }
            @Override public List<DiscoveredLogger> discover() {
                return List.of(
                        new DiscoveredLogger("com.myapp.service", "INFO"),
                        new DiscoveredLogger("com.myapp.repo", "DEBUG")
                );
            }
            @Override public void applyLevel(String name, String level) {}
            @Override public void installHook(java.util.function.BiConsumer<String, String> cb) {}
            @Override public void uninstallHook() {}
        });

        logging.start();

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertTrue(hasMetric(data, "logging.loggers_discovered"));
        // Should have value 2
        for (Map<String, Object> item : data) {
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) item.get("attributes");
            if ("logging.loggers_discovered".equals(attrs.get("name"))) {
                assertEquals(2, attrs.get("value"));
            }
        }

        logging.close();
    }

    @Test
    void loggingLevelChangesRecordsMetric() throws Exception {
        LoggersApi loggersApi = mock(LoggersApi.class);
        LogGroupsApi logGroupsApi = mock(LogGroupsApi.class);

        // Return a managed logger
        LoggerListResponse loggerResp = new LoggerListResponse();
        LoggerResource lr = new LoggerResource();
        lr.setId("com.myapp.service");
        var attrs = new Logger();
        attrs.setName("Service Logger");
        attrs.setLevel("DEBUG");
        attrs.setManaged(true);
        lr.setAttributes(attrs);
        loggerResp.setData(List.of(lr));
        when(loggersApi.listLoggers(nullable(Boolean.class), nullable(String.class), nullable(String.class))).thenReturn(loggerResp);

        LogGroupListResponse groupResp = new LogGroupListResponse();
        groupResp.setData(List.of());
        when(logGroupsApi.listLogGroups()).thenReturn(groupResp);

        LoggingClient logging = new LoggingClient(loggersApi, logGroupsApi, mockHttpClient, "key");
        logging.setEnvironment("production");
        logging.setService("my-service");
        logging.setMetrics(reporter);

        // Register a mock adapter that discovers the same logger
        logging.registerAdapter(new LoggingAdapter() {
            @Override public String name() { return "test"; }
            @Override public List<DiscoveredLogger> discover() {
                return List.of(new DiscoveredLogger("com.myapp.service", "INFO"));
            }
            @Override public void applyLevel(String name, String level) {}
            @Override public void installHook(java.util.function.BiConsumer<String, String> cb) {}
            @Override public void uninstallHook() {}
        });

        logging.start();

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertTrue(hasMetric(data, "logging.level_changes"));
        assertMetricHasDimension(data, "logging.level_changes", "logger", "com.myapp.service");

        logging.close();
    }

    // -----------------------------------------------------------------------
    // SharedWebSocket telemetry
    // -----------------------------------------------------------------------

    @Test
    void websocketConnectionGaugeOnConnect() {
        SharedWebSocket ws = new SharedWebSocket(null, null, reporter);
        ws.simulateMessage("{\"type\":\"connected\"}");

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertTrue(hasMetric(data, "platform.websocket_connections"));
        for (Map<String, Object> item : data) {
            @SuppressWarnings("unchecked")
            Map<String, Object> itemAttrs = (Map<String, Object>) item.get("attributes");
            if ("platform.websocket_connections".equals(itemAttrs.get("name"))) {
                assertEquals(1, itemAttrs.get("value"));
            }
        }
    }

    @Test
    void websocketConnectionGaugeOnClose() {
        SharedWebSocket ws = new SharedWebSocket(null, null, reporter);
        ws.simulateMessage("{\"type\":\"connected\"}");
        ws.close();

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        // After close, gauge should be 0
        for (Map<String, Object> item : data) {
            @SuppressWarnings("unchecked")
            Map<String, Object> itemAttrs = (Map<String, Object>) item.get("attributes");
            if ("platform.websocket_connections".equals(itemAttrs.get("name"))) {
                assertEquals(0, itemAttrs.get("value"));
            }
        }
    }

    @Test
    void websocketNoMetricsWhenReporterNull() {
        SharedWebSocket ws = new SharedWebSocket();
        // Should not throw
        assertDoesNotThrow(() -> ws.simulateMessage("{\"type\":\"connected\"}"));
        assertDoesNotThrow(() -> ws.close());
    }

    @Test
    void websocketGaugeOnListenerClose() throws Exception {
        // Create a WS with a connector that gives us a mock WebSocket
        java.util.concurrent.atomic.AtomicReference<java.net.http.WebSocket.Listener> listenerRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        SharedWebSocket.WsConnector connector = (uri, listener) -> {
            listenerRef.set(listener);
            return mock(java.net.http.WebSocket.class);
        };
        SharedWebSocket ws = new SharedWebSocket(connector, "wss://test/ws", reporter);

        // Start connection in background
        Thread t = new Thread(ws::wsThreadEntry);
        t.setDaemon(true);
        t.start();
        Thread.sleep(100);

        // Simulate connected
        ws.simulateMessage("{\"type\":\"connected\"}");

        // Now trigger onClose on the listener
        java.net.http.WebSocket.Listener listener = listenerRef.get();
        assertNotNull(listener);
        listener.onClose(mock(java.net.http.WebSocket.class), 1000, "bye");

        // Give reconnect thread time to start
        Thread.sleep(100);
        ws.close();

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        // The gauge should be 0 after close
        assertTrue(hasMetric(data, "platform.websocket_connections"));
    }

    @Test
    void websocketGaugeOnListenerError() throws Exception {
        java.util.concurrent.atomic.AtomicReference<java.net.http.WebSocket.Listener> listenerRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        SharedWebSocket.WsConnector connector = (uri, listener) -> {
            listenerRef.set(listener);
            return mock(java.net.http.WebSocket.class);
        };
        SharedWebSocket ws = new SharedWebSocket(connector, "wss://test/ws", reporter);

        Thread t = new Thread(ws::wsThreadEntry);
        t.setDaemon(true);
        t.start();
        Thread.sleep(100);

        ws.simulateMessage("{\"type\":\"connected\"}");

        java.net.http.WebSocket.Listener listener = listenerRef.get();
        assertNotNull(listener);
        listener.onError(mock(java.net.http.WebSocket.class), new RuntimeException("test error"));

        Thread.sleep(100);
        ws.close();

        reporter.flush();

        assertTrue(capturedBodies.size() > 0);
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertTrue(hasMetric(data, "platform.websocket_connections"));
    }

    // -----------------------------------------------------------------------
    // SmplClient integration
    // -----------------------------------------------------------------------

    @Test
    void disableTelemetryTrueSkipsMetrics() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void disableTelemetryDefaultIsFalse() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .build()) {
            assertNotNull(client);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private boolean hasMetric(List<Map<String, Object>> data, String metricName) {
        for (Map<String, Object> item : data) {
            Map<String, Object> attrs = (Map<String, Object>) item.get("attributes");
            if (metricName.equals(attrs.get("name"))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void assertMetricHasDimension(List<Map<String, Object>> data, String metricName,
                                           String dimKey, String dimValue) {
        for (Map<String, Object> item : data) {
            Map<String, Object> attrs = (Map<String, Object>) item.get("attributes");
            if (metricName.equals(attrs.get("name"))) {
                Map<String, String> dims = (Map<String, String>) attrs.get("dimensions");
                assertEquals(dimValue, dims.get(dimKey));
                return;
            }
        }
        fail("Metric " + metricName + " not found");
    }

    private FlagListResponse makeFlagListResponse(String key, String env) {
        FlagResource resource = new FlagResource();
        resource.setId(key);
        var flagAttrs = new com.smplkit.internal.generated.flags.model.Flag();
        flagAttrs.setName("Test Flag");
        flagAttrs.setType("BOOLEAN");
        flagAttrs.setDefault(true);
        var envMap = new HashMap<String, com.smplkit.internal.generated.flags.model.FlagEnvironment>();
        var flagEnv = new com.smplkit.internal.generated.flags.model.FlagEnvironment();
        flagEnv.setEnabled(true);
        flagEnv.setDefault(true);
        envMap.put(env, flagEnv);
        flagAttrs.setEnvironments(envMap);
        resource.setAttributes(flagAttrs);
        FlagListResponse resp = new FlagListResponse();
        resp.setData(List.of(resource));
        return resp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }
}
