package com.smplkit.flags;

import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Shared WebSocket connection to the smplkit event gateway.
 *
 * <p>Connects to {@code wss://app.smplkit.com/api/ws/v1/events?api_key={key}}
 * and dispatches events to registered listeners. Both config and flags modules
 * share this connection.</p>
 */
public final class SharedWebSocket {

    private static final Logger LOG = Logger.getLogger("smplkit.ws");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 32, 60};

    private final HttpClient httpClient;
    private final String wsUrl;
    private final ConcurrentHashMap<String, List<Consumer<Map<String, Object>>>> listeners = new ConcurrentHashMap<>();

    volatile WebSocket webSocket;
    volatile boolean closed = false;
    private volatile String connectionStatus = "disconnected";
    volatile CountDownLatch connectedLatch;
    private Thread wsThread;

    /** Functional interface for WebSocket creation, injectable for testing. */
    @FunctionalInterface
    interface WsConnector {
        WebSocket connect(URI uri, WebSocket.Listener listener) throws Exception;
    }

    private final WsConnector wsConnector;

    public SharedWebSocket(HttpClient httpClient, String appBaseUrl, String apiKey) {
        this.httpClient = httpClient;
        this.wsUrl = buildWsUrl(appBaseUrl, apiKey);
        this.wsConnector = (uri, listener) -> httpClient.newWebSocketBuilder()
                .buildAsync(uri, listener).join();
    }

    /** Package-private test constructor that doesn't auto-connect. */
    SharedWebSocket() {
        this.httpClient = null;
        this.wsUrl = null;
        this.wsConnector = null;
    }

    /** Package-private test constructor with injectable connector. */
    SharedWebSocket(WsConnector connector, String wsUrl) {
        this.httpClient = null;
        this.wsUrl = wsUrl;
        this.wsConnector = connector;
    }

    /** Package-private for testing. */
    static String buildWsUrl(String baseUrl, String apiKey) {
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
     * Ensures the WebSocket is connected, starting the background thread if needed.
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
     * Starts the WebSocket connection in a background daemon thread.
     */
    public void start() {
        if (closed || wsConnector == null) return;
        connectedLatch = new CountDownLatch(1);
        wsThread = new Thread(this::wsThreadEntry, "smplkit-shared-ws");
        wsThread.setDaemon(true);
        wsThread.start();
    }

    /**
     * Closes the WebSocket connection.
     */
    public void close() {
        closed = true;
        connectionStatus = "disconnected";
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

    /** Package-private for testing: simulates receiving a raw WS message. */
    void simulateMessage(String rawMessage) {
        if ("ping".equals(rawMessage)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = OBJECT_MAPPER.readValue(rawMessage, Map.class);
            String type = (String) msg.get("type");
            if ("connected".equals(type)) {
                connectionStatus = "connected";
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

    void setConnectionStatus(String status) {
        this.connectionStatus = status;
    }

    // -----------------------------------------------------------------------
    // Background thread
    // -----------------------------------------------------------------------

    /** Package-private for testing. */
    void wsThreadEntry() {
        try {
            connectToServer();
        } catch (Exception e) {
            if (!closed) {
                LOG.log(Level.WARNING, "SharedWebSocket initial connect failed", e);
                reconnect(0);
            }
        }
    }

    /** Package-private for testing. */
    void connectToServer() throws Exception {
        connectionStatus = "connecting";
        LOG.fine("Connecting shared WebSocket");

        CountDownLatch closedLatch = new CountDownLatch(1);
        WsListener listener = new WsListener(closedLatch);

        WebSocket ws = wsConnector.connect(URI.create(wsUrl), listener);
        this.webSocket = ws;

        // Block until closed (WsListener will count down on close/error)
        closedLatch.await();
    }

    /** Package-private for testing. */
    void reconnect(int attempt) {
        while (!closed) {
            connectionStatus = "reconnecting";
            int delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
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
                LOG.log(Level.WARNING, "SharedWebSocket reconnect attempt " + (attempt + 1) + " failed", e);
                attempt++;
            }
        }
    }

    /** Package-private for testing. */
    void dispatch(String event, Map<String, Object> data) {
        List<Consumer<Map<String, Object>>> eventListeners = listeners.get(event);
        if (eventListeners == null) return;
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

    /** Package-private for testing. */
    class WsListener implements WebSocket.Listener {
        private final CountDownLatch closedLatch;
        private final StringBuilder textBuffer = new StringBuilder();

        /** Package-private for testing. */
        WsListener(CountDownLatch closedLatch) {
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
                LOG.log(Level.WARNING, "SharedWebSocket error, reconnecting...", error);
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
