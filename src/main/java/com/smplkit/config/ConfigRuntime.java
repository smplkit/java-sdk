package com.smplkit.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolved, locally-cached configuration for a single config + environment.
 *
 * <p>All value-access methods ({@link #get}, {@link #getString}, etc.) are
 * <strong>synchronous</strong> — they read from an in-process map and never touch the
 * network. The runtime is constructed by
 * {@link ConfigClient#connect(Config, String)}.</p>
 *
 * <p>A background daemon thread maintains a WebSocket connection to the config service
 * for real-time cache updates. If the WebSocket fails to connect, the runtime continues
 * to serve cached values.</p>
 *
 * <p>Implements {@link AutoCloseable} for use in try-with-resources.</p>
 */
public final class ConfigRuntime implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("smplkit.config.runtime");
    private static final Logger WS_LOG = Logger.getLogger("smplkit.config.ws");
    private static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 32, 60};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Chain entries: child-first order, mutable maps for in-place WebSocket updates
    private final List<ChainEntry> chain;
    private final String configId;
    private final String configKey;
    private final String environment;
    private final HttpClient httpClient;
    private final String wsUrl;
    private final Supplier<List<ChainEntry>> fetchChainFn;

    // Cache — replaced atomically; never mutated in place
    private volatile Map<String, Object> cache;
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

    private final AtomicInteger fetchCount;
    private final List<ListenerEntry> listeners = Collections.synchronizedList(new ArrayList<>());

    private volatile String connectionStatus = "disconnected";
    private volatile WebSocket webSocket;
    private volatile boolean closed = false;
    private final Thread wsThread;

    // -----------------------------------------------------------------------
    // Package-private factory (called by ConfigClient.connect)
    // -----------------------------------------------------------------------

    static ConfigRuntime create(
            List<ChainEntry> chain,
            String configId,
            String configKey,
            String environment,
            HttpClient httpClient,
            String apiKey,
            String wsBaseUrl,
            Supplier<List<ChainEntry>> fetchChainFn,
            int initialFetchCount
    ) {
        return new ConfigRuntime(
                chain, configId, configKey, environment,
                httpClient, apiKey, wsBaseUrl, fetchChainFn, initialFetchCount
        );
    }

    /** Package-private constructor for testing (no WebSocket started). */
    ConfigRuntime(Map<String, Object> resolvedValues) {
        this.chain = List.of();
        this.configId = "";
        this.configKey = "";
        this.environment = "";
        this.httpClient = null;
        this.wsUrl = null;
        this.fetchChainFn = null;
        this.fetchCount = new AtomicInteger(0);
        this.cache = Collections.unmodifiableMap(new HashMap<>(resolvedValues));
        this.wsThread = null;
        this.connectionStatus = "disconnected";
    }

    private ConfigRuntime(
            List<ChainEntry> chain,
            String configId,
            String configKey,
            String environment,
            HttpClient httpClient,
            String apiKey,
            String wsBaseUrl,
            Supplier<List<ChainEntry>> fetchChainFn,
            int initialFetchCount
    ) {
        this.chain = new ArrayList<>(chain);
        this.configId = configId;
        this.configKey = configKey;
        this.environment = environment;
        this.httpClient = httpClient;
        this.wsUrl = buildWsUrl(wsBaseUrl, apiKey);
        this.fetchChainFn = fetchChainFn;
        this.fetchCount = new AtomicInteger(initialFetchCount);
        this.cache = Collections.unmodifiableMap(resolve(chain, environment));

        this.wsThread = new Thread(this::wsThreadEntry, "smplkit-ws-" + configKey);
        this.wsThread.setDaemon(true);
        this.wsThread.start();
    }

    // -----------------------------------------------------------------------
    // WebSocket background thread
    // -----------------------------------------------------------------------

    private static String buildWsUrl(String baseUrl, String apiKey) {
        String ws;
        if (baseUrl.startsWith("https://")) {
            ws = "wss://" + baseUrl.substring("https://".length());
        } else if (baseUrl.startsWith("http://")) {
            ws = "ws://" + baseUrl.substring("http://".length());
        } else {
            ws = "wss://" + baseUrl;
        }
        ws = ws.stripTrailing();
        return ws + "/api/ws/v1/configs?api_key=" + apiKey;
    }

    private void wsThreadEntry() {
        try {
            connectAndSubscribe();
        } catch (Exception e) {
            if (!closed) {
                WS_LOG.log(Level.WARNING,
                        "WebSocket initial connect failed for " + configKey + ", running in cache-only mode", e);
                reconnect(0);
            }
        }
    }

    private void connectAndSubscribe() throws Exception {
        connectionStatus = "connecting";
        WS_LOG.fine("Connecting to WebSocket for " + configKey);

        CountDownLatch subscribedLatch = new CountDownLatch(1);
        WsListener listener = new WsListener(subscribedLatch);

        WebSocket ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), listener)
                .join();
        this.webSocket = ws;

        // Send subscribe for every config in the chain
        for (ChainEntry entry : chain) {
            String msg = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "config_id", entry.id,
                    "environment", environment
            ));
            ws.sendText(msg, true).join();
        }

        // Wait briefly for the first "subscribed" confirmation
        subscribedLatch.await(10, TimeUnit.SECONDS);

        connectionStatus = "connected";
        WS_LOG.fine("WebSocket connected and subscribed for " + configKey);

        // Block in receive loop (handled by listener callbacks)
        // Wait until closed or disconnected
        listener.awaitClose();
    }

    private void reconnect(int attempt) {
        while (!closed) {
            int delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
            WS_LOG.info("Reconnecting in " + delay + "s (attempt " + (attempt + 1) + ") for " + configKey);
            try {
                Thread.sleep(delay * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) return;
            try {
                // Re-sync cache from HTTP before reconnecting
                resyncCache();
                connectAndSubscribe();
                WS_LOG.info("Reconnected successfully for " + configKey);
                return;
            } catch (Exception e) {
                WS_LOG.log(Level.WARNING, "Reconnect attempt " + (attempt + 1) + " failed for " + configKey, e);
                attempt++;
            }
        }
    }

    private void resyncCache() {
        if (fetchChainFn == null) return;
        try {
            List<ChainEntry> newChain = fetchChainFn.get();
            Map<String, Object> newCache = resolve(newChain, environment);
            fetchCount.addAndGet(newChain.size());

            Map<String, Object> oldCache;
            cacheLock.writeLock().lock();
            try {
                oldCache = cache;
                // Update mutable chain entries in place
                synchronized (chain) {
                    chain.clear();
                    chain.addAll(newChain);
                }
                cache = Collections.unmodifiableMap(newCache);
            } finally {
                cacheLock.writeLock().unlock();
            }
            fireChangeListeners(oldCache, newCache, "websocket");
        } catch (Exception e) {
            WS_LOG.log(Level.SEVERE, "Failed to resync cache after reconnect for " + configKey, e);
        }
    }

    // -----------------------------------------------------------------------
    // WebSocket listener (inner class)
    // -----------------------------------------------------------------------

    private class WsListener implements WebSocket.Listener {

        private final CountDownLatch subscribedLatch;
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final StringBuilder textBuffer = new StringBuilder();

        WsListener(CountDownLatch subscribedLatch) {
            this.subscribedLatch = subscribedLatch;
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
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed) {
                connectionStatus = "connecting";
                WS_LOG.warning("WebSocket closed (code=" + statusCode + ") for " + configKey
                        + ", reconnecting...");
                closedLatch.countDown();
                Thread reconnectThread = new Thread(
                        () -> reconnect(0),
                        "smplkit-ws-reconnect-" + configKey
                );
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
                WS_LOG.log(Level.WARNING, "WebSocket error for " + configKey + ", reconnecting...", error);
                connectionStatus = "connecting";
                closedLatch.countDown();
                Thread reconnectThread = new Thread(
                        () -> reconnect(0),
                        "smplkit-ws-reconnect-" + configKey
                );
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            } else {
                closedLatch.countDown();
            }
        }

        void awaitClose() throws InterruptedException {
            closedLatch.await();
        }

        private void handleMessage(String raw) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = OBJECT_MAPPER.readValue(raw, Map.class);
                String type = (String) msg.get("type");
                if (type == null) return;

                switch (type) {
                    case "subscribed" -> {
                        subscribedLatch.countDown();
                        WS_LOG.fine("Subscription confirmed for " + configKey);
                    }
                    case "config_changed" -> handleConfigChanged(msg);
                    case "config_deleted" -> {
                        WS_LOG.warning("Config " + msg.get("config_id") + " was deleted");
                        closed = true;
                        connectionStatus = "disconnected";
                    }
                    case "error" -> WS_LOG.warning("Server error: " + msg.get("message"));
                }
            } catch (Exception e) {
                WS_LOG.log(Level.WARNING, "Failed to parse WebSocket message: " + raw, e);
            }
        }

        @SuppressWarnings("unchecked")
        private void handleConfigChanged(Map<String, Object> data) {
            String changedConfigId = (String) data.get("config_id");
            List<Map<String, Object>> changes = (List<Map<String, Object>>) data.getOrDefault("changes", List.of());
            if (changes.isEmpty()) return;

            // Find the chain entry for the changed config
            ChainEntry target = null;
            synchronized (chain) {
                for (ChainEntry entry : chain) {
                    if (entry.id.equals(changedConfigId)) {
                        target = entry;
                        break;
                    }
                }
            }
            if (target == null) {
                WS_LOG.fine("Received config_changed for unknown config_id=" + changedConfigId);
                return;
            }

            // Apply changes to chain entry (in-place) and re-resolve
            synchronized (target) {
                Map<String, Object> envData = target.environments.computeIfAbsent(
                        environment, k -> new HashMap<>());
                @SuppressWarnings("unchecked")
                Map<String, Object> envValues = (Map<String, Object>)
                        envData.computeIfAbsent("values", k -> new HashMap<>());

                for (Map<String, Object> change : changes) {
                    String key = (String) change.get("key");
                    Object newValue = change.get("new_value");
                    Object oldValue = change.get("old_value");
                    if (newValue == null && oldValue != null) {
                        target.values.remove(key);
                        envValues.remove(key);
                    } else {
                        target.values.put(key, newValue);
                        envValues.put(key, newValue);
                    }
                }
            }

            Map<String, Object> newCacheMap;
            synchronized (chain) {
                newCacheMap = resolve(chain, environment);
            }

            Map<String, Object> oldCache;
            cacheLock.writeLock().lock();
            try {
                oldCache = cache;
                cache = Collections.unmodifiableMap(newCacheMap);
            } finally {
                cacheLock.writeLock().unlock();
            }
            fireChangeListeners(oldCache, newCacheMap, "websocket");
        }
    }

    // -----------------------------------------------------------------------
    // Value resolution (static algorithm)
    // -----------------------------------------------------------------------

    /** Resolution algorithm: walk chain root-to-child, deep-merging values. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> resolve(List<ChainEntry> chain, String environment) {
        Map<String, Object> accumulated = new HashMap<>();
        // Walk root-first (chain is child-first, so iterate in reverse)
        for (int i = chain.size() - 1; i >= 0; i--) {
            ChainEntry entry = chain.get(i);
            Map<String, Object> base = entry.values != null ? entry.values : Map.of();
            Map<String, Object> envData = entry.environments != null
                    ? entry.environments.get(environment)
                    : null;
            Map<String, Object> envValues = Map.of();
            if (envData != null) {
                Object rawEnvValues = envData.get("values");
                if (rawEnvValues instanceof Map) {
                    envValues = (Map<String, Object>) rawEnvValues;
                }
            }
            Map<String, Object> configResolved = deepMerge(base, envValues);
            accumulated = deepMerge(accumulated, configResolved);
        }
        return accumulated;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new HashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object existing = result.get(key);
            if (existing instanceof Map && value instanceof Map) {
                result.put(key, deepMerge((Map<String, Object>) existing, (Map<String, Object>) value));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Value access — all synchronous, served from local cache
    // -----------------------------------------------------------------------

    /**
     * Returns the resolved value for {@code key}, or {@code null} if absent.
     *
     * @param key the config key
     * @return the resolved value, or {@code null}
     */
    public Object get(String key) {
        cacheLock.readLock().lock();
        try {
            return cache.get(key);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Returns the resolved value for {@code key}, or {@code defaultValue} if absent.
     *
     * @param key          the config key
     * @param defaultValue fallback value
     * @return the resolved value, or {@code defaultValue}
     */
    public Object get(String key, Object defaultValue) {
        cacheLock.readLock().lock();
        try {
            return cache.getOrDefault(key, defaultValue);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Returns the value for {@code key} as a {@link String}, or {@code defaultValue}
     * if absent or not a string.
     */
    public String getString(String key, String defaultValue) {
        Object val = get(key);
        return val instanceof String ? (String) val : defaultValue;
    }

    /**
     * Returns the value for {@code key} as an {@code int}, or {@code defaultValue}
     * if absent or not a number.
     */
    public int getInt(String key, int defaultValue) {
        Object val = get(key);
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    /**
     * Returns the value for {@code key} as a {@code boolean}, or {@code defaultValue}
     * if absent or not a boolean.
     */
    public boolean getBool(String key, boolean defaultValue) {
        Object val = get(key);
        return val instanceof Boolean ? (Boolean) val : defaultValue;
    }

    /**
     * Returns {@code true} if {@code key} is present in the resolved configuration.
     */
    public boolean exists(String key) {
        cacheLock.readLock().lock();
        try {
            return cache.containsKey(key);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Returns a copy of the full resolved configuration.
     *
     * @return unmodifiable map of all resolved key/value pairs
     */
    public Map<String, Object> getAll() {
        cacheLock.readLock().lock();
        try {
            return Collections.unmodifiableMap(new HashMap<>(cache));
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------

    /**
     * Returns diagnostic statistics for this runtime.
     *
     * @return a {@link ConfigStats} snapshot
     */
    public ConfigStats stats() {
        return new ConfigStats(fetchCount.get());
    }

    /**
     * Returns the current WebSocket connection status.
     *
     * @return {@code "connected"}, {@code "connecting"}, or {@code "disconnected"}
     */
    public String connectionStatus() {
        return connectionStatus;
    }

    // -----------------------------------------------------------------------
    // Change listeners
    // -----------------------------------------------------------------------

    /**
     * Registers a listener that fires when any config value changes.
     *
     * @param listener called with a {@link ChangeEvent} on each change
     */
    public void onChange(Consumer<ChangeEvent> listener) {
        listeners.add(new ListenerEntry(null, listener));
    }

    /**
     * Registers a listener that fires when the specified key's value changes.
     *
     * @param key      only fire for changes to this key
     * @param listener called with a {@link ChangeEvent} on each matching change
     */
    public void onChange(String key, Consumer<ChangeEvent> listener) {
        listeners.add(new ListenerEntry(key, listener));
    }

    private void fireChangeListeners(
            Map<String, Object> oldCache,
            Map<String, Object> newCache,
            String source
    ) {
        Set<String> allKeys = new java.util.HashSet<>();
        allKeys.addAll(oldCache.keySet());
        allKeys.addAll(newCache.keySet());

        for (String key : allKeys) {
            Object oldVal = oldCache.get(key);
            Object newVal = newCache.get(key);
            if (java.util.Objects.equals(oldVal, newVal)) continue;

            ChangeEvent event = new ChangeEvent(key, oldVal, newVal, source);
            for (ListenerEntry entry : listeners) {
                if (entry.key != null && !entry.key.equals(key)) continue;
                try {
                    entry.listener.accept(event);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Exception in onChange listener for key '" + key + "'", e);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Forces a manual refresh of the cached configuration.
     *
     * <p>Fetches the full config chain via HTTP, re-resolves values, updates the local
     * cache, and fires listeners for any changes with {@code source="manual"}.</p>
     */
    public void refresh() {
        if (fetchChainFn == null) return;
        List<ChainEntry> newChain = fetchChainFn.get();
        Map<String, Object> newCacheMap = resolve(newChain, environment);
        fetchCount.addAndGet(newChain.size());

        Map<String, Object> oldCache;
        cacheLock.writeLock().lock();
        try {
            oldCache = cache;
            synchronized (chain) {
                chain.clear();
                chain.addAll(newChain);
            }
            cache = Collections.unmodifiableMap(newCacheMap);
        } finally {
            cacheLock.writeLock().unlock();
        }
        fireChangeListeners(oldCache, newCacheMap, "manual");
        LOG.fine("Manual refresh completed for " + configKey);
    }

    /**
     * Closes the runtime, shutting down the WebSocket connection.
     *
     * <p>After closing, value-access methods still return cached values.</p>
     */
    @Override
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
        LOG.fine("ConfigRuntime closed for " + configKey);
    }

    // -----------------------------------------------------------------------
    // Package-private types
    // -----------------------------------------------------------------------

    /** Mutable chain entry — values/environments are updated in-place by WebSocket messages. */
    static final class ChainEntry {
        final String id;
        Map<String, Object> values;
        Map<String, Map<String, Object>> environments;

        ChainEntry(String id, Map<String, Object> values, Map<String, Map<String, Object>> environments) {
            this.id = id;
            this.values = values != null ? new HashMap<>(values) : new HashMap<>();
            this.environments = environments != null ? new HashMap<>(environments) : new HashMap<>();
        }
    }

    private record ListenerEntry(String key, Consumer<ChangeEvent> listener) {}
}
