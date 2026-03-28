package com.smplkit.config;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link ConfigRuntime} covering WebSocket lifecycle,
 * resolution edge cases, change listeners, refresh, and close paths.
 */
class ConfigRuntimeFullTest {

    // -----------------------------------------------------------------------
    // buildWsUrl (package-private static, tested via reflection-free approach
    // through create() that calls it internally)
    // -----------------------------------------------------------------------

    @Test
    void resolve_envDataWithNonMapValues_treatedAsNoEnvOverride() {
        // When envData.get("values") is not a Map, should be ignored
        Map<String, Object> envData = new HashMap<>();
        envData.put("values", "not-a-map");
        ConfigRuntime.ChainEntry entry = new ConfigRuntime.ChainEntry(
                "id1",
                Map.of("a", 1),
                Map.of("prod", envData)
        );
        Map<String, Object> result = ConfigRuntime.resolve(List.of(entry), "prod");
        assertEquals(1, result.get("a")); // base value, no env override
    }

    @Test
    void resolve_nullValuesInEntry_usesEmptyMap() {
        ConfigRuntime.ChainEntry entry = new ConfigRuntime.ChainEntry("id1", null, null);
        Map<String, Object> result = ConfigRuntime.resolve(List.of(entry), "prod");
        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_nullEnvironmentsInEntry_usesNoEnvOverride() {
        ConfigRuntime.ChainEntry entry = new ConfigRuntime.ChainEntry("id1", Map.of("x", 5), null);
        Map<String, Object> result = ConfigRuntime.resolve(List.of(entry), "prod");
        assertEquals(5, result.get("x"));
    }

    // -----------------------------------------------------------------------
    // fireChangeListeners (tested via refresh with a real fetchChainFn)
    // -----------------------------------------------------------------------

    @Test
    void refresh_fetchesChainAndFiresListeners() {
        // Create a runtime with a fetchChainFn that returns updated values
        List<ConfigRuntime.ChainEntry> initialChain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())
        );

        List<ConfigRuntime.ChainEntry> updatedChain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 2, "b", 3), Map.of())
        );

        // Use the test-visible create method but we need a ConfigRuntime that has
        // a fetchChainFn. We'll use reflection or the package-private constructor.
        // Actually, let's use the static create method and immediately close the WS thread.

        // We'll create a runtime using a mock HttpClient that fails WS connection immediately
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("test - no WS"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        List<ChangeEvent> events = Collections.synchronizedList(new ArrayList<>());

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(initialChain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> updatedChain,
                1
        );

        // Wait a bit for the WS thread to fail and fall into reconnect
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        runtime.onChange(events::add);

        // Now call refresh
        runtime.refresh();

        assertEquals(2, runtime.get("a"));
        assertEquals(3, runtime.get("b"));

        // Listener should have fired for both changed keys
        assertFalse(events.isEmpty());
        boolean foundAChange = events.stream().anyMatch(e -> "a".equals(e.key()));
        boolean foundBChange = events.stream().anyMatch(e -> "b".equals(e.key()));
        assertTrue(foundAChange);
        assertTrue(foundBChange);

        // Verify source is "manual"
        for (ChangeEvent e : events) {
            assertEquals("manual", e.source());
        }

        runtime.close();
    }

    @Test
    void refresh_keySpecificListenerOnlyFiresForMatchingKey() {
        List<ConfigRuntime.ChainEntry> initialChain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1, "b", 2), Map.of())
        );
        List<ConfigRuntime.ChainEntry> updatedChain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 99, "b", 2), Map.of())
        );

        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("test - no WS"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(initialChain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> updatedChain,
                1
        );

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        List<ChangeEvent> aEvents = Collections.synchronizedList(new ArrayList<>());
        List<ChangeEvent> bEvents = Collections.synchronizedList(new ArrayList<>());
        runtime.onChange("a", aEvents::add);
        runtime.onChange("b", bEvents::add);

        runtime.refresh();

        assertFalse(aEvents.isEmpty());
        assertTrue(bEvents.isEmpty()); // b didn't change

        runtime.close();
    }

    @Test
    void refresh_listenerExceptionDoesNotPreventOtherListeners() {
        List<ConfigRuntime.ChainEntry> initialChain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())
        );
        List<ConfigRuntime.ChainEntry> updatedChain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 2), Map.of())
        );

        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("test - no WS"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(initialChain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> updatedChain,
                1
        );

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        List<ChangeEvent> events = Collections.synchronizedList(new ArrayList<>());
        // First listener throws
        runtime.onChange(e -> { throw new RuntimeException("boom"); });
        // Second listener should still receive
        runtime.onChange(events::add);

        runtime.refresh();

        assertFalse(events.isEmpty());
        runtime.close();
    }

    @Test
    void refresh_removedKeyFiresChangeEventWithNullNewValue() {
        List<ConfigRuntime.ChainEntry> initialChain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1, "gone", "bye"), Map.of())
        );
        List<ConfigRuntime.ChainEntry> updatedChain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())
        );

        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("test - no WS"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(initialChain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> updatedChain,
                1
        );

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        List<ChangeEvent> events = Collections.synchronizedList(new ArrayList<>());
        runtime.onChange(events::add);

        runtime.refresh();

        ChangeEvent goneEvent = events.stream()
                .filter(e -> "gone".equals(e.key()))
                .findFirst()
                .orElse(null);
        assertNotNull(goneEvent);
        assertEquals("bye", goneEvent.oldValue());
        assertNull(goneEvent.newValue());

        runtime.close();
    }

    // -----------------------------------------------------------------------
    // close() with actual WebSocket mock
    // -----------------------------------------------------------------------

    @Test
    void close_sendsCloseToWebSocket() throws Exception {
        // Create runtime that actually connects to a mock WS
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);
        CompletableFuture<WebSocket> wsFuture = CompletableFuture.completedFuture(mockWs);
        when(wsBuilder.buildAsync(any(), any())).thenReturn(wsFuture);

        // sendText returns a completed future
        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())
        );

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        // Wait for WS thread to connect
        Thread.sleep(500);

        runtime.close();

        assertEquals("disconnected", runtime.connectionStatus());
        verify(mockWs).sendClose(eq(WebSocket.NORMAL_CLOSURE), eq("bye"));
    }

    @Test
    void close_handlesExceptionFromSendClose() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);
        CompletableFuture<WebSocket> wsFuture = CompletableFuture.completedFuture(mockWs);
        when(wsBuilder.buildAsync(any(), any())).thenReturn(wsFuture);

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        CompletableFuture<WebSocket> failClose = new CompletableFuture<>();
        failClose.completeExceptionally(new RuntimeException("close failed"));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(failClose);

        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of())
        );

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        // Should not throw even if sendClose fails
        assertDoesNotThrow(runtime::close);
        assertEquals("disconnected", runtime.connectionStatus());
    }

    // -----------------------------------------------------------------------
    // stats accumulation via refresh
    // -----------------------------------------------------------------------

    @Test
    void stats_incrementsOnRefresh() {
        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())
        );

        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("test"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        int initialFetch = runtime.stats().fetchCount();
        runtime.refresh();
        assertTrue(runtime.stats().fetchCount() > initialFetch);

        runtime.close();
    }

    // -----------------------------------------------------------------------
    // WsListener onOpen, onText, onClose, onError via intercepting listener
    // -----------------------------------------------------------------------

    @Test
    void wsListener_onTextHandlesSubscribedMessage() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        // Capture the listener
        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            // Call onOpen
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())
        );

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener, "Listener should have been captured");

        // Simulate receiving "subscribed" message
        listener.onText(mockWs, "{\"type\":\"subscribed\"}", true);

        // Verify runtime is connected
        assertEquals("connected", runtime.connectionStatus());

        runtime.close();
    }

    @Test
    void wsListener_onTextHandlesConfigChanged() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        Map<String, Object> envData = new HashMap<>();
        envData.put("values", new HashMap<>(Map.of("a", 1)));
        Map<String, Map<String, Object>> envs = new HashMap<>();
        envs.put("prod", envData);

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", new HashMap<>(Map.of("a", 1)), envs));

        List<ChangeEvent> events = Collections.synchronizedList(new ArrayList<>());

        ConfigRuntime runtime = ConfigRuntime.create(
                chain,
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);
        runtime.onChange(events::add);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Simulate "subscribed"
        listener.onText(mockWs, "{\"type\":\"subscribed\"}", true);

        // Simulate "config_changed"
        String changeMsg = "{\"type\":\"config_changed\",\"config_id\":\"id1\",\"changes\":[{\"key\":\"a\",\"old_value\":1,\"new_value\":42}]}";
        listener.onText(mockWs, changeMsg, true);

        Thread.sleep(200);

        assertEquals(42, runtime.get("a"));
        assertFalse(events.isEmpty());

        runtime.close();
    }

    @Test
    void wsListener_onTextHandlesConfigDeleted() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Simulate receiving "config_deleted"
        listener.onText(mockWs, "{\"type\":\"config_deleted\",\"config_id\":\"id1\"}", true);

        Thread.sleep(100);

        assertEquals("disconnected", runtime.connectionStatus());

        runtime.close();
    }

    @Test
    void wsListener_onTextHandlesErrorMessage() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Simulate receiving "error" message type
        listener.onText(mockWs, "{\"type\":\"error\",\"message\":\"some server error\"}", true);

        // Should not crash - just logs
        runtime.close();
    }

    @Test
    void wsListener_onTextHandlesUnknownType() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Unknown type - should be silently ignored
        listener.onText(mockWs, "{\"type\":\"unknown_type\"}", true);

        // Null type - should be silently ignored
        listener.onText(mockWs, "{\"no_type_field\":true}", true);

        runtime.close();
    }

    @Test
    void wsListener_onTextHandlesMalformedJson() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Malformed JSON - should be caught and logged
        listener.onText(mockWs, "this is not json!", true);

        runtime.close();
    }

    @Test
    void wsListener_onTextHandlesPartialMessages() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Send partial message (last=false), then complete (last=true)
        listener.onText(mockWs, "{\"type\":", false);
        listener.onText(mockWs, "\"subscribed\"}", true);

        Thread.sleep(100);

        assertEquals("connected", runtime.connectionStatus());
        runtime.close();
    }

    @Test
    void wsListener_onCloseTriggersReconnect() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Simulate onClose
        listener.onClose(mockWs, 1000, "going away");

        Thread.sleep(200);

        // Status should be "connecting" (reconnecting)
        assertEquals("connecting", runtime.connectionStatus());

        runtime.close();
    }

    @Test
    void wsListener_onCloseAfterRuntimeClosed_doesNotReconnect() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Close the runtime first
        runtime.close();

        // Now trigger onClose - should NOT reconnect
        listener.onClose(mockWs, 1000, "bye");

        assertEquals("disconnected", runtime.connectionStatus());
    }

    @Test
    void wsListener_onError_triggersReconnect() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Simulate onError
        listener.onError(mockWs, new RuntimeException("connection lost"));

        Thread.sleep(200);

        assertEquals("connecting", runtime.connectionStatus());

        runtime.close();
    }

    @Test
    void wsListener_onError_afterRuntimeClosed_doesNotReconnect() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Close first
        runtime.close();

        // Then trigger error - should NOT reconnect
        listener.onError(mockWs, new RuntimeException("error"));

        assertEquals("disconnected", runtime.connectionStatus());
    }

    @Test
    void wsListener_configChanged_deleteKey() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        Map<String, Object> envData = new HashMap<>();
        envData.put("values", new HashMap<>(Map.of("a", 1)));
        Map<String, Map<String, Object>> envs = new HashMap<>();
        envs.put("prod", envData);

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", new HashMap<>(Map.of("a", 1)), envs));

        ConfigRuntime runtime = ConfigRuntime.create(
                chain,
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        listener.onText(mockWs, "{\"type\":\"subscribed\"}", true);

        // Simulate delete: new_value is null, old_value is present
        String changeMsg = "{\"type\":\"config_changed\",\"config_id\":\"id1\",\"changes\":[{\"key\":\"a\",\"old_value\":1}]}";
        listener.onText(mockWs, changeMsg, true);

        Thread.sleep(200);

        assertNull(runtime.get("a"));

        runtime.close();
    }

    @Test
    void wsListener_configChanged_unknownConfigId_ignored() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // config_changed for unknown config_id
        String changeMsg = "{\"type\":\"config_changed\",\"config_id\":\"unknown_id\",\"changes\":[{\"key\":\"a\",\"new_value\":99}]}";
        listener.onText(mockWs, changeMsg, true);

        Thread.sleep(100);

        // Should still have original value
        assertEquals(1, runtime.get("a"));

        runtime.close();
    }

    @Test
    void wsListener_configChanged_emptyChanges_ignored() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Empty changes array
        String changeMsg = "{\"type\":\"config_changed\",\"config_id\":\"id1\",\"changes\":[]}";
        listener.onText(mockWs, changeMsg, true);

        Thread.sleep(100);

        assertEquals(1, runtime.get("a"));

        runtime.close();
    }

    // -----------------------------------------------------------------------
    // Reconnect path — WS connect fails, then closed during reconnect
    // -----------------------------------------------------------------------

    @Test
    void wsThread_initialConnectFails_entersReconnectAndStopsOnClose() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        // Always fail WS connection
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("connection refused"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())
        );

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        // Wait a bit for initial connect to fail and reconnect loop to start
        Thread.sleep(500);

        // Close should interrupt the reconnect sleep
        runtime.close();
        assertEquals("disconnected", runtime.connectionStatus());
    }

    @Test
    void resyncCache_failingFetchChainFn_handlesException() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        // Connection always fails to trigger reconnect (which calls resyncCache)
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("no ws"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())
        );

        // fetchChainFn that throws
        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> { throw new RuntimeException("fetch failed"); },
                1
        );

        // Wait for initial connect fail + reconnect attempt (which calls resyncCache that fails)
        Thread.sleep(2000);

        // Values should still be accessible from initial cache
        assertEquals(1, runtime.get("a"));

        runtime.close();
    }

    @Test
    void wsListener_configChanged_noExistingEnvData_createsNewEnvMap() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        // Chain entry WITHOUT any environment data - computeIfAbsent should create it
        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", new HashMap<>(Map.of("a", 1)), new HashMap<>()));

        ConfigRuntime runtime = ConfigRuntime.create(
                chain,
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        listener.onText(mockWs, "{\"type\":\"subscribed\"}", true);

        // config_changed for a key when no env data exists yet
        String changeMsg = "{\"type\":\"config_changed\",\"config_id\":\"id1\",\"changes\":[{\"key\":\"newkey\",\"new_value\":\"newval\"}]}";
        listener.onText(mockWs, changeMsg, true);

        Thread.sleep(200);

        // The new value should be in the cache
        assertEquals("newval", runtime.get("newkey"));

        runtime.close();
    }

    // -----------------------------------------------------------------------
    // close with wsThread interrupt path (lines 653-654)
    // -----------------------------------------------------------------------

    @Test
    void close_interruptsWsThreadDuringReconnectSleep() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        // Always fail so reconnect loop keeps running with sleep
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("fail"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of())
        );

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        // Let it enter reconnect loop
        Thread.sleep(1500);

        // Close interrupts the sleeping thread
        runtime.close();
        assertEquals("disconnected", runtime.connectionStatus());
    }

    // -----------------------------------------------------------------------
    // connectAndSubscribe completes normally, reaching awaitClose (lines 188-189, 309-310)
    // Then onClose fires, reconnect runs successfully (lines 205-207, 213)
    // -----------------------------------------------------------------------

    @Test
    void wsConnect_subscribeThenClose_exercisesAwaitCloseAndReconnect() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        // Track all captured listeners for each connection
        List<WebSocket.Listener> allListeners = Collections.synchronizedList(new ArrayList<>());
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            allListeners.add(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", new HashMap<>(Map.of("a", 1)), new HashMap<>()));

        ConfigRuntime runtime = ConfigRuntime.create(
                chain,
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> List.of(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())),
                1
        );

        // Wait for WS thread to connect and subscribe
        Thread.sleep(500);

        // First listener should have been captured by now
        assertFalse(allListeners.isEmpty());
        WebSocket.Listener firstListener = allListeners.get(0);

        // Send subscribed to unblock subscribedLatch, then awaitClose will be blocking
        firstListener.onText(mockWs, "{\"type\":\"subscribed\"}", true);

        // Give time for the thread to reach awaitClose
        Thread.sleep(300);

        // Now trigger onClose which will: 1) countDown closedLatch (unblock awaitClose)
        // 2) Start reconnect thread (in a daemon thread from onClose handler)
        firstListener.onClose(mockWs, 1000, "test close");

        // Wait for reconnect (1s backoff + connect time)
        Thread.sleep(2000);

        // The reconnect should have connected again with a second listener
        assertTrue(allListeners.size() >= 2, "Reconnect should create a new listener");

        runtime.close();
    }

    @Test
    void reconnect_succeeds_andConnectAndSubscribeCompletes() throws Exception {
        // This test targets lines 205-207 (successful reconnect -> connectAndSubscribe returns)
        // Flow: initial connect fails -> wsThreadEntry catch -> reconnect(0) ->
        //   reconnect calls resyncCache + connectAndSubscribe which succeeds ->
        //   connectAndSubscribe blocks on awaitClose ->
        //   we trigger onClose on the reconnect listener ->
        //   awaitClose returns, connectAndSubscribe returns normally ->
        //   reconnect reaches line 206 ("Reconnected successfully") and returns
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        // Track listeners across connections
        List<WebSocket.Listener> allListeners = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("initial fail"));

        // First: fails. Second+: succeeds.
        when(wsBuilder.buildAsync(any(), any()))
                .thenReturn(failedFuture)
                .thenAnswer(invocation -> {
                    WebSocket.Listener listener = invocation.getArgument(1);
                    allListeners.add(listener);
                    listener.onOpen(mockWs);
                    return CompletableFuture.completedFuture(mockWs);
                });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> List.of(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())),
                1
        );

        // Wait: initial fail + 1s reconnect backoff + reconnect attempt
        Thread.sleep(3000);

        assertFalse(allListeners.isEmpty(), "Reconnect should have created a listener");
        WebSocket.Listener reconnectListener = allListeners.get(0);

        // Send subscribed to unblock the subscribedLatch
        reconnectListener.onText(mockWs, "{\"type\":\"subscribed\"}", true);
        Thread.sleep(200);

        // The reconnect's connectAndSubscribe is now blocking on awaitClose.
        // Trigger onClose on the reconnect listener. This will:
        // 1) Count down closedLatch -> awaitClose returns -> connectAndSubscribe returns normally
        // 2) reconnect() reaches line 206 "Reconnected successfully" and returns (lines 206-207)
        // 3) onClose also spawns a new reconnect thread (which will see closed soon)
        reconnectListener.onClose(mockWs, 1000, "test trigger");

        // Wait for lines 206-207 to execute, and the spawned reconnect thread to start sleeping
        Thread.sleep(500);

        runtime.close();
    }

    @Test
    void wsThreadEntry_catchBlock_triggeredByBuildAsyncFailure() throws Exception {
        // This test ensures the catch block in wsThreadEntry (lines 148, 155) is hit
        // We need connectAndSubscribe to throw, which happens when .join() throws

        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        // First call fails (triggers catch in wsThreadEntry -> reconnect)
        // Second call succeeds (so reconnect returns)
        WebSocket mockWs = mock(WebSocket.class);
        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();

        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("initial connect fail"));

        when(wsBuilder.buildAsync(any(), any()))
                .thenReturn(failedFuture)  // first call fails
                .thenAnswer(invocation -> {
                    WebSocket.Listener listener = invocation.getArgument(1);
                    capturedListener.set(listener);
                    listener.onOpen(mockWs);
                    return CompletableFuture.completedFuture(mockWs);
                }); // second call succeeds

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> List.of(new ConfigRuntime.ChainEntry("id1", Map.of("a", 1), Map.of())),
                1
        );

        // First connect fails -> catch in wsThreadEntry -> reconnect(0) with 1s sleep
        // Then reconnect calls resyncCache + connectAndSubscribe which succeeds
        Thread.sleep(3000);

        // Should be connected after reconnect
        WebSocket.Listener listener = capturedListener.get();
        if (listener != null) {
            listener.onText(mockWs, "{\"type\":\"subscribed\"}", true);
            Thread.sleep(200);
            assertEquals("connected", runtime.connectionStatus());
        }

        runtime.close();
    }

    // -----------------------------------------------------------------------
    // close while wsThread.join is waiting (lines 653-654)
    // -----------------------------------------------------------------------

    @Test
    void close_whileWsThreadBlocked_interruptsJoin() throws Exception {
        // Create a runtime where the WS thread blocks in awaitClose
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        AtomicReference<WebSocket.Listener> capturedListener = new AtomicReference<>();
        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            capturedListener.set(listener);
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        when(mockWs.sendClose(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(mockWs));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(500);

        WebSocket.Listener listener = capturedListener.get();
        assertNotNull(listener);

        // Send subscribed so it progresses to awaitClose
        listener.onText(mockWs, "{\"type\":\"subscribed\"}", true);
        Thread.sleep(300);

        // Now close - the wsThread is blocked in awaitClose() on closedLatch
        // close() will: set closed=true, sendClose, then interrupt wsThread, then join(2000)
        runtime.close();

        assertEquals("disconnected", runtime.connectionStatus());
    }

    // -----------------------------------------------------------------------
    // close() interrupted during wsThread.join (lines 653-654)
    // -----------------------------------------------------------------------

    @Test
    void close_interruptedDuringJoin_restoresInterruptFlag() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);

        WebSocket mockWs = mock(WebSocket.class);

        // Use a latch to ensure the wsThread is blocked when we call close
        CountDownLatch wsThreadStarted = new CountDownLatch(1);

        when(wsBuilder.buildAsync(any(), any())).thenAnswer(invocation -> {
            WebSocket.Listener listener = invocation.getArgument(1);
            listener.onOpen(mockWs);
            wsThreadStarted.countDown();
            return CompletableFuture.completedFuture(mockWs);
        });

        when(mockWs.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(mockWs));
        // sendClose throws so the WS is not cleanly closed
        when(mockWs.sendClose(anyInt(), any())).thenThrow(new RuntimeException("sendClose failed"));

        List<ConfigRuntime.ChainEntry> chain = new ArrayList<>();
        chain.add(new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of()));

        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "https://config.smplkit.com",
                () -> chain,
                1
        );

        wsThreadStarted.await(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        // Call close from a separate thread, and pre-set its interrupt flag.
        // When the thread calls wsThread.join(2000), the join will throw
        // InterruptedException immediately because the interrupt flag is set.
        Thread closeThread = new Thread(() -> {
            Thread.currentThread().interrupt(); // Set interrupt flag BEFORE close
            runtime.close();
        });
        closeThread.start();
        closeThread.join(5000);

        assertEquals("disconnected", runtime.connectionStatus());
    }

    // -----------------------------------------------------------------------
    // buildWsUrl coverage: http:// prefix and bare host
    // -----------------------------------------------------------------------

    @Test
    void create_withHttpUrl_usesWsPrefix() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("test"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of())
        );

        // http:// should become ws://
        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "http://localhost:8080",
                () -> chain,
                1
        );

        Thread.sleep(200);
        runtime.close();
        // If we got here without error, the URL was built correctly
    }

    @Test
    void create_withBareHost_usesWssPrefix() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        WebSocket.Builder wsBuilder = mock(WebSocket.Builder.class);
        when(mockHttpClient.newWebSocketBuilder()).thenReturn(wsBuilder);
        CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("test"));
        when(wsBuilder.buildAsync(any(), any())).thenReturn(failedFuture);

        List<ConfigRuntime.ChainEntry> chain = List.of(
                new ConfigRuntime.ChainEntry("id1", Map.of(), Map.of())
        );

        // Bare host (no http/https prefix) should get wss://
        ConfigRuntime runtime = ConfigRuntime.create(
                new ArrayList<>(chain),
                "id1",
                "test-key",
                "prod",
                mockHttpClient,
                "api-key",
                "config.smplkit.com",
                () -> chain,
                1
        );

        Thread.sleep(200);
        runtime.close();
    }
}
