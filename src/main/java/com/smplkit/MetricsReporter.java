package com.smplkit;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Accumulates SDK telemetry metrics and periodically flushes them to the platform.
 *
 * <p><b>Internal</b> — this class is not part of the public API and may change without notice.</p>
 */
public final class MetricsReporter implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("smplkit.metrics");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long DEFAULT_FLUSH_INTERVAL_SECONDS = 60;

    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String environment;
    private final String service;
    private final long flushIntervalSeconds;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Counter> counters = new HashMap<>();
    private final Map<String, Gauge> gauges = new HashMap<>();

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> flushFuture;
    private volatile boolean closed = false;
    private volatile boolean timerStarted = false;

    MetricsReporter(HttpClient httpClient, String appBaseUrl, String apiKey,
                    String environment, String service) {
        this(httpClient, appBaseUrl, apiKey, environment, service, DEFAULT_FLUSH_INTERVAL_SECONDS);
    }

    MetricsReporter(HttpClient httpClient, String appBaseUrl, String apiKey,
                    String environment, String service, long flushIntervalSeconds) {
        this.httpClient = httpClient;
        this.endpoint = appBaseUrl + "/api/v1/metrics/bulk";
        this.apiKey = apiKey;
        this.environment = environment;
        this.service = service;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smplkit-metrics-flush");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Records a counter metric. Accumulates value over the flush window.
     *
     * @param name       metric name (e.g. "flags.evaluations")
     * @param value      value to add (typically 1)
     * @param unit       unit label (e.g. "evaluations")
     * @param dimensions additional dimensions beyond environment/service
     */
    public void record(String name, long value, String unit, Map<String, String> dimensions) {
        if (closed) return;
        Map<String, String> fullDims = buildDimensions(dimensions);
        String key = buildKey(name, fullDims);
        lock.lock();
        try {
            Counter counter = counters.get(key);
            if (counter == null) {
                counter = new Counter(name, fullDims, unit);
                counters.put(key, counter);
            } else if (counter.unit == null && unit != null) {
                counter.unit = unit;
            }
            counter.value = counter.value.add(BigDecimal.valueOf(value));
        } finally {
            lock.unlock();
        }
        ensureTimerStarted();
    }

    /** Convenience: record with value=1 and no extra dimensions. */
    public void record(String name, String unit) {
        record(name, 1, unit, null);
    }

    /** Convenience: record with value=1 and dimensions. */
    public void record(String name, String unit, Map<String, String> dimensions) {
        record(name, 1, unit, dimensions);
    }

    /** Convenience: record with a specific value and no extra dimensions. */
    public void record(String name, long value, String unit) {
        record(name, value, unit, null);
    }

    /**
     * Records a gauge metric. Replaces any previous value.
     *
     * @param name       metric name
     * @param value      current gauge value
     * @param unit       unit label
     * @param dimensions additional dimensions
     */
    public void recordGauge(String name, long value, String unit, Map<String, String> dimensions) {
        if (closed) return;
        Map<String, String> fullDims = buildDimensions(dimensions);
        String key = buildKey(name, fullDims);
        lock.lock();
        try {
            Gauge gauge = gauges.get(key);
            if (gauge == null) {
                gauge = new Gauge(name, fullDims, unit);
                gauges.put(key, gauge);
            } else if (gauge.unit == null && unit != null) {
                gauge.unit = unit;
            }
            gauge.value = BigDecimal.valueOf(value);
        } finally {
            lock.unlock();
        }
        ensureTimerStarted();
    }

    /** Convenience: recordGauge with no extra dimensions. */
    public void recordGauge(String name, long value, String unit) {
        recordGauge(name, value, unit, null);
    }

    /**
     * Flushes accumulated metrics to the server. Resets counters after flush.
     * Swallows all exceptions (fire-and-forget).
     */
    public void flush() {
        List<Map<String, Object>> payload;
        lock.lock();
        try {
            if (counters.isEmpty() && gauges.isEmpty()) return;
            payload = buildPayload();
            counters.clear();
            // Gauges are NOT cleared — they represent current state
        } finally {
            lock.unlock();
        }

        try {
            Map<String, Object> body = Map.of("data", payload);
            String json = OBJECT_MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOG.log(Level.FINE, "Metrics flush failed", e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (flushFuture != null) {
            flushFuture.cancel(false);
        }
        scheduler.shutdown();
        flush();
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void ensureTimerStarted() {
        if (timerStarted || closed) return;
        synchronized (this) {
            if (timerStarted || closed) return;
            timerStarted = true;
            flushFuture = scheduler.scheduleAtFixedRate(
                    this::tick, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    private void tick() {
        flush();
    }

    private Map<String, String> buildDimensions(Map<String, String> extra) {
        Map<String, String> dims = new HashMap<>();
        dims.put("environment", environment);
        dims.put("service", service);
        if (extra != null) {
            dims.putAll(extra);
        }
        return dims;
    }

    private static String buildKey(String name, Map<String, String> dimensions) {
        // Sort dimension keys for consistent hashing
        List<String> keys = new ArrayList<>(dimensions.keySet());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder(name);
        for (String k : keys) {
            sb.append('\0').append(k).append('=').append(dimensions.get(k));
        }
        return sb.toString();
    }

    private List<Map<String, Object>> buildPayload() {
        List<Map<String, Object>> items = new ArrayList<>();
        Instant now = Instant.now();

        for (Counter counter : counters.values()) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("name", counter.name);
            attrs.put("value", counter.value);
            attrs.put("unit", counter.unit);
            attrs.put("period_seconds", flushIntervalSeconds);
            attrs.put("dimensions", counter.dimensions);
            attrs.put("recorded_at", now.toString());

            items.add(Map.of("type", "metric", "attributes", attrs));
        }

        for (Gauge gauge : gauges.values()) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("name", gauge.name);
            attrs.put("value", gauge.value);
            attrs.put("unit", gauge.unit);
            attrs.put("period_seconds", flushIntervalSeconds);
            attrs.put("dimensions", gauge.dimensions);
            attrs.put("recorded_at", now.toString());

            items.add(Map.of("type", "metric", "attributes", attrs));
        }

        return items;
    }

    // -----------------------------------------------------------------------
    // Internal data holders
    // -----------------------------------------------------------------------

    private static final class Counter {
        final String name;
        final Map<String, String> dimensions;
        String unit;
        BigDecimal value = BigDecimal.ZERO;
        final Instant windowStart = Instant.now();

        Counter(String name, Map<String, String> dimensions, String unit) {
            this.name = name;
            this.dimensions = dimensions;
            this.unit = unit;
        }
    }

    private static final class Gauge {
        final String name;
        final Map<String, String> dimensions;
        String unit;
        BigDecimal value = BigDecimal.ZERO;

        Gauge(String name, Map<String, String> dimensions, String unit) {
            this.name = name;
            this.dimensions = dimensions;
            this.unit = unit;
        }
    }
}
