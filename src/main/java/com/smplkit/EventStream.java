package com.smplkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smplkit.internal.Debug;

import org.jetbrains.annotations.ApiStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Real-time event connection to the smplkit platform, carried over a
 * Server-Sent Events (SSE) stream on the platform's HTTPS endpoint.
 *
 * <p>Delivers change events to registered listeners and invokes registered
 * refetch callbacks after every successful <em>re</em>connect so product
 * modules can recover state missed while disconnected. Thread-safe.</p>
 *
 * <p>Internal infrastructure — not part of the supported SDK surface.</p>
 *
 * @hidden
 */
@ApiStatus.Internal
public final class EventStream {

    private static final Logger LOG = Logger.getLogger("smplkit.events");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Reconnect delay base until the server supplies a {@code retry:} value. */
    static final long DEFAULT_RETRY_MS = 1000L;
    /** Reconnect backoff ceiling. */
    static final long MAX_BACKOFF_MS = 60_000L;
    /** Liveness read timeout — two missed 30-second server keepalives. */
    static final long DEFAULT_READ_TIMEOUT_MS = 45_000L;

    /**
     * Internal test seam: when {@code true}, {@link #start()} marks the
     * connection ready immediately instead of opening a real stream, so the
     * unit suite never dials the network. The dedicated EventStream tests reset
     * this to {@code false} to exercise the real connect/reconnect machinery.
     *
     * @hidden
     */
    @ApiStatus.Internal
    public static volatile boolean disableDialForTests = false;

    /**
     * Functional interface for opening the streaming connection.
     *
     * @hidden
     */
    @FunctionalInterface
    public interface Connector {
        Connection connect(URI uri) throws Exception;
    }

    /**
     * A live streaming response: status code, content type, and body.
     *
     * @hidden
     */
    public record Connection(int statusCode, String contentType, InputStream body) {}

    private final Connector connector;
    private final String eventsUrl;
    private final MetricsReporter metrics;
    private final ConcurrentHashMap<String, List<Consumer<Map<String, Object>>>> listeners = new ConcurrentHashMap<>();
    private final List<Runnable> refetchCallbacks = new CopyOnWriteArrayList<>();

    public volatile boolean closed = false;
    private volatile String connectionStatus = "disconnected";
    public volatile CountDownLatch connectedLatch;
    private Thread streamThread;
    private ScheduledExecutorService watchdog;
    private volatile InputStream currentBody;
    private volatile long lastActivityNanos;

    /** Reconnect delay base, seeded by the server's {@code retry:} field. */
    volatile long baseRetryMs = DEFAULT_RETRY_MS;
    /** Consecutive attempts since the last successful connect (drives doubling). */
    volatile int attempt = 0;
    /** {@code true} once any connect has succeeded (gates reconnect refetch). */
    volatile boolean everConnected = false;
    /** Liveness read timeout; package-private test seam, production default 45s. */
    volatile long readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    /** Package-private test seam: observes each computed reconnect delay. */
    volatile LongConsumer delayListenerForTests;

    public EventStream(HttpClient httpClient, String appBaseUrl, String apiKey) {
        this(httpClient, appBaseUrl, apiKey, null);
    }

    public EventStream(HttpClient httpClient, String appBaseUrl, String apiKey, MetricsReporter metrics) {
        this.eventsUrl = buildEventsUrl(appBaseUrl);
        this.metrics = metrics;
        this.connector = uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", com.smplkit.internal.UserAgent.VALUE)
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Connection(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.body());
        };
    }

    /** Test constructor. */
    public EventStream() {
        this.connector = null;
        this.eventsUrl = null;
        this.metrics = null;
    }

    /** Test constructor with injectable connector. */
    public EventStream(Connector connector, String eventsUrl) {
        this(connector, eventsUrl, null);
    }

    /** Test constructor with injectable connector and metrics. */
    public EventStream(Connector connector, String eventsUrl, MetricsReporter metrics) {
        this.connector = connector;
        this.eventsUrl = eventsUrl;
        this.metrics = metrics;
    }

    /** Builds the event-stream URL from a service base URL. */
    public static String buildEventsUrl(String baseUrl) {
        String base = baseUrl.stripTrailing();
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            base = "https://" + base;
        }
        return base + "/api/v1/events";
    }

    /**
     * Registers a listener for the given event type.
     */
    public void on(String event, Consumer<Map<String, Object>> callback) {
        listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    /**
     * Unregisters a listener.
     */
    public void off(String event, Consumer<Map<String, Object>> callback) {
        List<Consumer<Map<String, Object>>> list = listeners.get(event);
        if (list != null) {
            list.remove(callback);
        }
    }

    /**
     * Registers a callback invoked after every successful <em>re</em>connect
     * (never on the initial connect). Product modules use this to run their
     * bulk refetch so state missed while disconnected is recovered.
     */
    public void onReconnect(Runnable callback) {
        refetchCallbacks.add(callback);
    }

    /**
     * Unregisters a reconnect refetch callback.
     */
    public void offReconnect(Runnable callback) {
        refetchCallbacks.remove(callback);
    }

    /**
     * Ensures the event connection is established, blocking up to the given timeout.
     */
    public void ensureConnected(Duration timeout) {
        if (connectionStatus.equals("connected")) return;
        if (streamThread == null || !streamThread.isAlive()) {
            start();
        }
        CountDownLatch latch = this.connectedLatch;
        if (latch != null) {
            try {
                latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Starts the event connection.
     */
    public void start() {
        if (closed || connector == null) return;
        if (streamThread != null && streamThread.isAlive()) return;
        connectedLatch = new CountDownLatch(1);
        if (disableDialForTests) {
            // Unit-test no-op: report ready without opening a real stream.
            connectionStatus = "connected";
            connectedLatch.countDown();
            return;
        }
        Debug.log("events", "starting event stream");
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smplkit-events-watchdog");
            t.setDaemon(true);
            return t;
        });
        streamThread = new Thread(this::runLoop, "smplkit-events");
        streamThread.setDaemon(true);
        streamThread.start();
    }

    /**
     * Closes the event connection and releases resources.
     */
    public void close() {
        closed = true;
        connectionStatus = "disconnected";
        if (metrics != null) {
            metrics.recordGauge("platform.event_connections", 0, "connections");
        }
        // Closing the HTTP stream is all the shutdown the protocol needs; it
        // also unblocks the reader thread if it is mid-read.
        closeQuietly(currentBody);
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        if (streamThread != null && streamThread.isAlive()) {
            streamThread.interrupt();
            try {
                streamThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public String connectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(String status) {
        this.connectionStatus = status;
    }

    // -----------------------------------------------------------------------
    // Background thread
    // -----------------------------------------------------------------------

    /** Stream-thread entry point: connect, consume, back off, repeat. */
    void runLoop() {
        while (!closed) {
            try {
                if (connectOnce()) {
                    // Successful connect (HTTP 200 + text/event-stream):
                    // reset the backoff to the base delay.
                    attempt = 0;
                }
            } catch (Exception e) {
                if (!closed) {
                    LOG.warning("EventStream connect failed: " + e.getMessage());
                    Debug.log("events", "connect failed: " + e);
                }
            }
            if (closed) {
                return;
            }
            long delay = nextDelayMs();
            connectionStatus = "reconnecting";
            Debug.log("events", "reconnecting in " + delay + "ms (attempt " + attempt + ")");
            LOG.info("EventStream reconnecting in " + delay + "ms (attempt " + attempt + ")");
            LongConsumer observer = delayListenerForTests;
            if (observer != null) {
                observer.accept(delay);
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Delay before the next connect attempt: the server-seeded base doubled
     * per consecutive failure, capped at {@link #MAX_BACKOFF_MS}. No jitter.
     */
    long nextDelayMs() {
        long delay = Math.min(baseRetryMs << Math.min(attempt, 20), MAX_BACKOFF_MS);
        attempt++;
        return delay;
    }

    /**
     * Opens one streaming connection and consumes it until it drops.
     *
     * @return {@code true} when the connect was successful (HTTP 200 with a
     *     {@code text/event-stream} content type), regardless of how long the
     *     stream subsequently lived
     */
    boolean connectOnce() throws Exception {
        connectionStatus = "connecting";
        Debug.log("events", "connecting to " + eventsUrl);
        Connection conn = connector.connect(URI.create(eventsUrl));
        String contentType = conn.contentType() != null ? conn.contentType() : "";
        if (conn.statusCode() != 200 || !contentType.contains("text/event-stream")) {
            LOG.warning("EventStream connect rejected: HTTP " + conn.statusCode()
                    + " (content-type: " + contentType + ")");
            closeQuietly(conn.body());
            return false;
        }
        boolean reconnect = everConnected;
        everConnected = true;
        if (reconnect) {
            Debug.log("events", "reconnected — refetching all subscribed modules");
            for (Runnable refetch : refetchCallbacks) {
                try {
                    refetch.run();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "EventStream refetch callback failed", e);
                }
            }
        }
        readStream(conn.body());
        return true;
    }

    /** Consumes the stream until EOF, error, or a watchdog-forced close. */
    void readStream(InputStream body) {
        currentBody = body;
        lastActivityNanos = System.nanoTime();
        ScheduledFuture<?> watchdogTask = scheduleWatchdog(body);
        Parser parser = new Parser();
        byte[] buf = new byte[8192];
        try {
            int n;
            while (!closed && (n = body.read(buf)) != -1) {
                lastActivityNanos = System.nanoTime();
                parser.feed(buf, n);
            }
            if (!closed) {
                LOG.warning("EventStream disconnected, reconnecting...");
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warning("EventStream read failed, reconnecting...: " + e.getMessage());
                Debug.log("events", "read failed: " + e);
            }
        } finally {
            currentBody = null;
            if (watchdogTask != null) {
                watchdogTask.cancel(false);
            }
            closeQuietly(body);
            if (!closed && metrics != null) {
                metrics.recordGauge("platform.event_connections", 0, "connections");
            }
        }
    }

    /**
     * Arms the liveness watchdog for one connection: when no bytes (data and
     * keepalive comments alike count) arrive within the read timeout, the
     * stream is force-closed so the reader falls into the reconnect loop.
     */
    private ScheduledFuture<?> scheduleWatchdog(InputStream body) {
        ScheduledExecutorService wd = this.watchdog;
        if (wd == null) {
            return null; // direct-call test paths that never start()ed
        }
        long period = Math.max(readTimeoutMs / 4, 10);
        return wd.scheduleAtFixedRate(() -> {
            long idleMs = (System.nanoTime() - lastActivityNanos) / 1_000_000L;
            if (idleMs >= readTimeoutMs) {
                LOG.warning("EventStream idle for " + idleMs + "ms, forcing reconnect");
                Debug.log("events", "liveness timeout after " + idleMs + "ms — closing stream");
                closeQuietly(body);
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    /** Marks the stream connected — the server delivered its {@code connected} event. */
    void markConnected() {
        connectionStatus = "connected";
        if (metrics != null) {
            metrics.recordGauge("platform.event_connections", 1, "connections");
        }
        CountDownLatch latch = connectedLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /** Delivers an event to all registered listeners. */
    public void dispatch(String event, Map<String, Object> data) {
        List<Consumer<Map<String, Object>>> eventListeners = listeners.get(event);
        if (eventListeners == null || eventListeners.isEmpty()) {
            Debug.log("events", "no handler registered for event: \"" + event + "\"");
            return;
        }
        Debug.log("events", "routing \"" + event + "\" to " + eventListeners.size() + " handler(s)");
        for (Consumer<Map<String, Object>> callback : eventListeners) {
            try {
                callback.accept(data);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Exception in EventStream listener for event '" + event + "'", e);
            }
        }
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException e) {
            // Ignore close errors
        }
    }

    /** Package-private test seam: a fresh parser bound to this stream. */
    Parser newParser() {
        return new Parser();
    }

    // -----------------------------------------------------------------------
    // SSE parser
    // -----------------------------------------------------------------------

    /**
     * Incremental SSE frame parser: UTF-8; {@code \n}, {@code \r\n}, and
     * {@code \r} line terminators; leading-BOM strip; field values split
     * across read-buffer boundaries; comment lines; multi-line {@code data:}
     * joined with {@code \n}; unknown fields and event names ignored.
     */
    final class Parser {
        private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        private boolean skipNextNewline = false;
        private boolean firstLine = true;
        private String eventName = "";
        private final StringBuilder dataBuf = new StringBuilder();
        private boolean hasData = false;

        /** Feeds a chunk of raw bytes; lines and characters may span chunks. */
        void feed(byte[] buf, int len) {
            for (int i = 0; i < len; i++) {
                byte b = buf[i];
                if (skipNextNewline) {
                    skipNextNewline = false;
                    if (b == '\n') {
                        continue; // the \n of a \r\n pair — already terminated
                    }
                }
                if (b == '\r') {
                    skipNextNewline = true;
                    completeLine();
                } else if (b == '\n') {
                    completeLine();
                } else {
                    lineBuf.write(b);
                }
            }
        }

        private void completeLine() {
            String line = new String(lineBuf.toByteArray(), StandardCharsets.UTF_8);
            lineBuf.reset();
            if (firstLine) {
                firstLine = false;
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
            }
            handleLine(line);
        }

        /** Processes one decoded line per the SSE grammar. */
        void handleLine(String line) {
            if (line.isEmpty()) {
                dispatchPending();
                return;
            }
            if (line.charAt(0) == ':') {
                return; // comment frame (e.g. ": keepalive") — liveness only
            }
            String field;
            String value;
            int colon = line.indexOf(':');
            if (colon < 0) {
                field = line;
                value = "";
            } else {
                field = line.substring(0, colon);
                value = line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
            }
            switch (field) {
                case "event" -> eventName = value;
                case "data" -> {
                    dataBuf.append(value).append('\n');
                    hasData = true;
                }
                case "retry" -> {
                    if (isAsciiDigits(value)) {
                        baseRetryMs = Long.parseLong(value);
                        Debug.log("events", "server retry interval set to " + baseRetryMs + "ms");
                    }
                }
                default -> {
                    // Unknown fields (including "id") are ignored silently.
                }
            }
        }

        /** Dispatches the accumulated frame on a blank line. */
        private void dispatchPending() {
            String event = eventName.isEmpty() ? "message" : eventName;
            boolean fire = hasData;
            String data = hasData ? dataBuf.substring(0, dataBuf.length() - 1) : "";
            eventName = "";
            dataBuf.setLength(0);
            hasData = false;
            if (!fire) {
                return; // no data accumulated — nothing to dispatch
            }
            Map<String, Object> payload;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(data, Map.class);
                payload = parsed;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to parse EventStream payload: " + data, e);
                return;
            }
            if ("connected".equals(event)) {
                markConnected();
            }
            dispatch(event, payload);
        }

        private boolean isAsciiDigits(String value) {
            if (value.isEmpty() || value.length() > 9) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            return true;
        }
    }
}
