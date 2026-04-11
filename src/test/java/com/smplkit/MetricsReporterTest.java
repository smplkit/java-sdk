package com.smplkit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MetricsReporterTest {

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
                    // Capture the body by subscribing to the publisher
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
    // Counter accumulation
    // -----------------------------------------------------------------------

    @Test
    void recordAccumulatesValues() {
        reporter.record("flags.evaluations", 1, "evaluations", null);
        reporter.record("flags.evaluations", 1, "evaluations", null);
        reporter.record("flags.evaluations", 1, "evaluations", null);

        reporter.flush();

        assertEquals(1, capturedBodies.size());
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertEquals(1, data.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals("flags.evaluations", attrs.get("name"));
        assertEquals(3, attrs.get("value"));
        assertEquals("evaluations", attrs.get("unit"));
    }

    @Test
    void recordWithCustomValue() {
        reporter.record("logging.loggers_discovered", 5, "loggers");

        reporter.flush();

        assertEquals(1, capturedBodies.size());
        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals(5, attrs.get("value"));
    }

    @Test
    void differentDimensionsCreateSeparateCounters() {
        reporter.record("flags.evaluations", "evaluations", Map.of("flag_id", "a"));
        reporter.record("flags.evaluations", "evaluations", Map.of("flag_id", "b"));

        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertEquals(2, data.size());
    }

    @Test
    void sameDimensionsAccumulate() {
        reporter.record("flags.evaluations", "evaluations", Map.of("flag_id", "a"));
        reporter.record("flags.evaluations", "evaluations", Map.of("flag_id", "a"));

        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertEquals(1, data.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals(2, attrs.get("value"));
    }

    @Test
    void baseDimensionsAlwaysInjected() {
        reporter.record("flags.evaluations", "evaluations", Map.of("flag_id", "x"));

        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        @SuppressWarnings("unchecked")
        Map<String, String> dims = (Map<String, String>) attrs.get("dimensions");
        assertEquals("production", dims.get("environment"));
        assertEquals("my-service", dims.get("service"));
        assertEquals("x", dims.get("flag_id"));
    }

    @Test
    void unitFirstWriteWins() {
        reporter.record("flags.evaluations", 1, null, null);
        reporter.record("flags.evaluations", 1, "evaluations", null);
        reporter.record("flags.evaluations", 1, "other", null);

        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals("evaluations", attrs.get("unit"));
    }

    @Test
    void gaugeUnitFirstWriteWins() {
        reporter.recordGauge("platform.connections", 1, null, null);
        reporter.recordGauge("platform.connections", 2, "connections", null);
        reporter.recordGauge("platform.connections", 3, "other", null);

        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals("connections", attrs.get("unit"));
        assertEquals(3, attrs.get("value"));
    }

    // -----------------------------------------------------------------------
    // Gauge behavior
    // -----------------------------------------------------------------------

    @Test
    void gaugeReplacesValue() {
        reporter.recordGauge("platform.websocket_connections", 1, "connections");
        reporter.recordGauge("platform.websocket_connections", 0, "connections");

        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        // Gauge should have value 0 (last write wins)
        boolean found = false;
        for (Map<String, Object> item : data) {
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) item.get("attributes");
            if ("platform.websocket_connections".equals(attrs.get("name"))) {
                assertEquals(0, attrs.get("value"));
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void gaugeAndCountersSeparate() {
        reporter.record("flags.evaluations", "evaluations");
        reporter.recordGauge("platform.websocket_connections", 1, "connections");

        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertEquals(2, data.size());
    }

    // -----------------------------------------------------------------------
    // Flush behavior
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void emptyFlushSendsNoRequest() throws Exception {
        reporter.flush();
        verify(mockHttpClient, never()).send(any(), any());
    }

    @Test
    void flushResetsCounters() {
        reporter.record("flags.evaluations", "evaluations");
        reporter.flush();
        capturedBodies.clear();

        reporter.flush(); // Should be empty now
        assertEquals(0, capturedBodies.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpErrorsSwallowed() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new RuntimeException("Network error"));

        reporter.record("flags.evaluations", "evaluations");
        // Should not throw
        assertDoesNotThrow(() -> reporter.flush());
    }

    @Test
    void flushSendsCorrectEndpoint() throws Exception {
        reporter.record("flags.evaluations", "evaluations");
        reporter.flush();

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("https://app.smplkit.com/api/v1/metrics/bulk", request.uri().toString());
        assertEquals("POST", request.method());
        assertTrue(request.headers().firstValue("Authorization").orElse("").contains("test-key"));
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void payloadIncludesPeriodSeconds() {
        reporter.record("flags.evaluations", "evaluations");
        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals(60, attrs.get("period_seconds"));
        assertNotNull(attrs.get("recorded_at"));
    }

    @Test
    void payloadTypeIsMetric() {
        reporter.record("flags.evaluations", "evaluations");
        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertEquals("metric", data.get(0).get("type"));
    }

    // -----------------------------------------------------------------------
    // Timer
    // -----------------------------------------------------------------------

    @Test
    void timerStartsLazilyOnFirstRecord() throws Exception {
        // Create a reporter with a very short interval for testing
        MetricsReporter shortReporter = new MetricsReporter(
                mockHttpClient, "https://app.smplkit.com", "key", "prod", "svc", 1);
        try {
            shortReporter.record("test.metric", "units");
            // Wait for timer to fire
            Thread.sleep(1500);
            verify(mockHttpClient, atLeastOnce()).send(any(), any());
        } finally {
            shortReporter.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void timerDoesNotStartWithoutRecords() throws Exception {
        // No records made, timer should not have started
        Thread.sleep(100);
        verify(mockHttpClient, never()).send(any(), any());
    }

    // -----------------------------------------------------------------------
    // Close behavior
    // -----------------------------------------------------------------------

    @Test
    void closeFlushesBeforeClosing() {
        reporter.record("flags.evaluations", "evaluations");
        reporter.close();

        assertEquals(1, capturedBodies.size());
        reporter = null; // Prevent double-close in tearDown
    }

    @Test
    void closeIsIdempotent() {
        reporter.record("flags.evaluations", "evaluations");
        reporter.close();
        reporter.close(); // Should not throw or double-flush
        assertEquals(1, capturedBodies.size());
        reporter = null;
    }

    @Test
    void noRecordsAfterClose() {
        reporter.close();
        reporter.record("flags.evaluations", "evaluations");
        reporter.recordGauge("platform.connections", 1, "connections");
        // Both should be no-ops after close
        capturedBodies.clear();
        reporter.flush();
        assertEquals(0, capturedBodies.size());
        reporter = null;
    }

    // -----------------------------------------------------------------------
    // Thread safety
    // -----------------------------------------------------------------------

    @Test
    void concurrentRecordsAccumulateCorrectly() throws Exception {
        int threadCount = 10;
        int recordsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < recordsPerThread; j++) {
                    reporter.record("test.concurrent", "ops");
                }
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals(threadCount * recordsPerThread, attrs.get("value"));
    }

    // -----------------------------------------------------------------------
    // Convenience methods
    // -----------------------------------------------------------------------

    @Test
    void recordConvenienceWithUnit() {
        reporter.record("flags.cache_hits", "hits");
        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        assertEquals("flags.cache_hits", attrs.get("name"));
        assertEquals(1, attrs.get("value"));
        assertEquals("hits", attrs.get("unit"));
    }

    @Test
    void recordConvenienceWithDimensions() {
        reporter.record("config.resolutions", "resolutions", Map.of("config_id", "db"));
        reporter.flush();

        Map<String, Object> payload = parsePayload(capturedBodies.get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) data.get(0).get("attributes");
        @SuppressWarnings("unchecked")
        Map<String, String> dims = (Map<String, String>) attrs.get("dimensions");
        assertEquals("db", dims.get("config_id"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }
}
