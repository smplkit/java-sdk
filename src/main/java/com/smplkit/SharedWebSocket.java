package com.smplkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smplkit.internal.Debug;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Real-time event connection to the smplkit platform.
 *
 * <p>Delivers change events to registered listeners. Thread-safe.</p>
 */
public final class SharedWebSocket {

    private static final Logger LOG = Logger.getLogger("smplkit.ws");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 32, 60};

    private final HttpClient httpClient;
    private final String wsUrl;
    private final ConcurrentHashMap<String, List<Consumer<Map<String, Object>>>> listeners = new ConcurrentHashMap<>();
    private final MetricsReporter metrics;

    public volatile WebSocket webSocket;
    public volatile boolean closed = false;
    private volatile String connectionStatus = "disconnected";
    public volatile CountDownLatch connectedLatch;
    private Thread wsThread;

    /** Functional interface for connection creation. */
    @FunctionalInterface
    public interface WsConnector {
        WebSocket connect(URI uri, WebSocket.Listener listener) throws Exception;
    }

    private final WsConnector wsConnector;

    public SharedWebSocket(HttpClient httpClient, String appBaseUrl, String apiKey) {
        this(httpClient, appBaseUrl, apiKey, null);
    }

    /**
     * Mirrors the User-Agent the HTTP transport sends. CloudFront's WAF
     * blocks WebSocket upgrades that omit a User-Agent header; the JDK
     * WebSocket builder does not set one by default, so we inject it
     * explicitly. Without this, the upgrade is rejected with HTTP 403
     * before reaching our backend.
     */
    static final String WS_USER_AGENT = "smplkit-java-sdk/0.0.0";

    public SharedWebSocket(HttpClient httpClient, String appBaseUrl, String apiKey, MetricsReporter metrics) {
        this.httpClient = httpClient;
        this.wsUrl = buildWsUrl(appBaseUrl, apiKey);
        this.metrics = metrics;
        this.wsConnector = (uri, listener) -> httpClient.newWebSocketBuilder()
                .header("User-Agent", WS_USER_AGENT)
                .buildAsync(uri, listener).join();
    }

    /** Test constructor. */
    public SharedWebSocket() {
        this.httpClient = null;
        this.wsUrl = null;
        this.wsConnector = null;
        this.metrics = null;
    }

    /** Test constructor with injectable connector. */
    public SharedWebSocket(WsConnector connector, String wsUrl) {
        this(connector, wsUrl, null);
    }

    /** Test constructor with injectable connector and metrics. */
    public SharedWebSocket(WsConnector connector, String wsUrl, MetricsReporter metrics) {
        this.httpClient = null;
        this.wsUrl = wsUrl;
        this.wsConnector = connector;
        this.metrics = metrics;
    }

    /** Builds the event connection URL from a base URL and API key. */
    public static String buildWsUrl(String baseUrl, String apiKey) {
        String ws;
        if (baseUrl.startsWith("https://")) {
            ws = "wss://" + baseUrl.substring("https://".length());
        } else if (baseUrl.startsWith("http://")) {
            ws = "ws://" + baseUrl.substring("http://".length());
        } else {
            ws = "wss://" + baseUrl;
        }
        ws = ws.stripTrailing();
        return ws + "/api/ws/v1/events?api_key=" + apiKey;
    }

    /**
     * Registers a listener for the given event type.
     */
    public void on(String event, Consumer<Map<String, Object>> callback) {
        listeners.computeIfAbsent(event, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(callback);
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
     * Ensures the event connection is established, blocking up to the given timeout.
     */
    public void ensureConnected(Duration timeout) {
        if (connectionStatus.equals("connected")) return;
        if (wsThread == null || !wsThread.isAlive()) {
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
        if (closed || wsConnector == null) return;
        Debug.log("websocket", "starting WebSocket connection");
        connectedLatch = new CountDownLatch(1);
        wsThread = new Thread(this::wsThreadEntry, "smplkit-shared-ws");
        wsThread.setDaemon(true);
        wsThread.start();
    }

    /**
     * Closes the event connection and releases resources.
     */
    public void close() {
        closed = true;
        connectionStatus = "disconnected";
        if (metrics != null) {
            metrics.recordGauge("platform.websocket_connections", 0, "connections");
        }
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
        if (wsThread != null && wsThread.isAlive()) {
            wsThread.interrupt();
            try {
                wsThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public String connectionStatus() {
        return connectionStatus;
    }

    /** Processes a raw event message. */
    public void simulateMessage(String rawMessage) {
        if ("ping".equals(rawMessage)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = OBJECT_MAPPER.readValue(rawMessage, Map.class);
            String type = (String) msg.get("type");
            if ("connected".equals(type)) {
                connectionStatus = "connected";
                if (metrics != null) {
                    metrics.recordGauge("platform.websocket_connections", 1, "connections");
                }
                CountDownLatch latch = connectedLatch;
                if (latch != null) latch.countDown();
                return;
            }
            if ("error".equals(type)) {
                LOG.warning("SharedWebSocket server error: " + msg.get("message"));
                return;
            }
            String event = (String) msg.get("event");
            if (event != null) {
                dispatch(event, msg);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse SharedWebSocket message: " + rawMessage, e);
        }
    }

    public void setConnectionStatus(String status) {
        this.connectionStatus = status;
    }

    // -----------------------------------------------------------------------
    // Background thread
    // -----------------------------------------------------------------------

    /** Entry point for the connection thread. */
    public void wsThreadEntry() {
        try {
            connectToServer();
        } catch (Exception e) {
            if (!closed) {
                LOG.warning("SharedWebSocket initial connect failed: " + e.getMessage());
                Debug.log("websocket", "SharedWebSocket initial connect failed: " + e);
                reconnect(0);
            }
        }
    }

    /** Establishes the connection. Blocks until the connection closes. */
    public void connectToServer() throws Exception {
        // Log a sanitized URL (strip api_key query param).
        String sanitizedUrl = wsUrl != null && wsUrl.contains("?") ? wsUrl.substring(0, wsUrl.indexOf('?')) : wsUrl;
        Debug.log("websocket", "connecting to " + sanitizedUrl);
        connectionStatus = "connecting";
        Debug.log("websocket", "connecting shared WebSocket");

        CountDownLatch closedLatch = new CountDownLatch(1);
        WsListener listener = new WsListener(closedLatch);

        WebSocket ws = wsConnector.connect(URI.create(wsUrl), listener);
        this.webSocket = ws;

        // Block until closed (WsListener will count down on close/error)
        closedLatch.await();
    }

    /** Attempts to re-establish the connection after a failure. */
    public void reconnect(int attempt) {
        while (!closed) {
            connectionStatus = "reconnecting";
            int delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
            Debug.log("websocket", "reconnecting in " + delay + "s (attempt " + (attempt + 1) + ")");
            LOG.info("SharedWebSocket reconnecting in " + delay + "s (attempt " + (attempt + 1) + ")");
            try {
                Thread.sleep(delay * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) return;
            try {
                connectToServer();
                LOG.info("SharedWebSocket reconnected successfully");
                return;
            } catch (Exception e) {
                LOG.warning("SharedWebSocket reconnect attempt " + (attempt + 1) + " failed: " + e.getMessage());
                Debug.log("websocket", "SharedWebSocket reconnect attempt " + (attempt + 1) + " failed: " + e);
                attempt++;
            }
        }
    }

    /** Delivers an event to all registered listeners. */
    public void dispatch(String event, Map<String, Object> data) {
        List<Consumer<Map<String, Object>>> eventListeners = listeners.get(event);
        if (eventListeners == null || eventListeners.isEmpty()) {
            Debug.log("websocket", "no handler registered for event: \"" + event + "\"");
            return;
        }
        Debug.log("websocket", "routing \"" + event + "\" to " + eventListeners.size() + " handler(s)");
        for (Consumer<Map<String, Object>> callback : eventListeners) {
            try {
                callback.accept(data);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Exception in SharedWebSocket listener for event '" + event + "'", e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // WebSocket listener
    // -----------------------------------------------------------------------

    /** Listener for incoming event messages. */
    public class WsListener implements WebSocket.Listener {
        private final CountDownLatch closedLatch;
        private final StringBuilder textBuffer = new StringBuilder();

        public WsListener(CountDownLatch closedLatch) {
            this.closedLatch = closedLatch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);

                // Handle ping/pong
                if ("ping".equals(message)) {
                    webSocket.sendText("pong", true);
                    webSocket.request(1);
                    return null;
                }

                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed) {
                if (metrics != null) {
                    metrics.recordGauge("platform.websocket_connections", 0, "connections");
                }
                LOG.warning("SharedWebSocket closed (code=" + statusCode + "), reconnecting...");
                closedLatch.countDown();
                Thread reconnectThread = new Thread(
                        () -> reconnect(0), "smplkit-ws-reconnect");
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            } else {
                closedLatch.countDown();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!closed) {
                if (metrics != null) {
                    metrics.recordGauge("platform.websocket_connections", 0, "connections");
                }
                LOG.warning("SharedWebSocket error, reconnecting...: " + error.getMessage());
                Debug.log("websocket", "SharedWebSocket error, reconnecting: " + error);
                closedLatch.countDown();
                Thread reconnectThread = new Thread(
                        () -> reconnect(0), "smplkit-ws-reconnect");
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            } else {
                closedLatch.countDown();
            }
        }

        private void handleMessage(String raw) {
            simulateMessage(raw);
        }
    }
}
