package com.smplkit.config;

import com.smplkit.internal.generated.config.api.ConfigsApi;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Catches the small remaining gaps in {@link LiveConfigProxy}: configId(),
 * the per-itemKey onChange overload, containsValue, and toString.
 */
class LiveConfigProxyExtraTest {

    private ConfigClient newClient() {
        ConfigsApi api = mock(ConfigsApi.class);
        ConfigClient client = new ConfigClient(api, java.net.http.HttpClient.newHttpClient(), "test-key");
        client.setEnvironment("staging");
        try {
            Field f = ConfigClient.class.getDeclaredField("connected");
            f.setAccessible(true);
            f.set(client, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return client;
    }

    private void seedCache(ConfigClient client, String id, Map<String, Object> values) {
        try {
            Field f = ConfigClient.class.getDeclaredField("configCache");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) f.get(client);
            cache.put(id, values);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void configId_returnsConstructorArgument() {
        ConfigClient client = newClient();
        seedCache(client, "user-svc", Map.of("k", "v"));
        LiveConfigProxy proxy = client.get("user-svc");
        assertEquals("user-svc", proxy.configId());
    }

    @Test
    void onChange_perItemKey_delegatesToClient() {
        ConfigClient client = newClient();
        seedCache(client, "user-svc", Map.of("max_retries", 3));
        LiveConfigProxy proxy = client.get("user-svc");

        AtomicInteger fired = new AtomicInteger();
        proxy.onChange("max_retries", evt -> fired.incrementAndGet());

        // Verify the listener was actually registered with the client (we don't
        // rely on firing it — that's covered elsewhere).
        try {
            Field f = ConfigClient.class.getDeclaredField("listeners");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<Object> listeners = (java.util.List<Object>) f.get(client);
            assertEquals(1, listeners.size());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void containsValue_delegatesToCurrentValues() {
        ConfigClient client = newClient();
        Map<String, Object> initial = new HashMap<>();
        initial.put("host", "prod-db");
        initial.put("port", 5432);
        seedCache(client, "user-svc", initial);
        LiveConfigProxy proxy = client.get("user-svc");

        assertTrue(proxy.containsValue("prod-db"));
        assertTrue(proxy.containsValue(5432));
        assertFalse(proxy.containsValue("nonexistent-value"));
    }

    @Test
    void toString_includesConfigIdAndValues() {
        ConfigClient client = newClient();
        seedCache(client, "user-svc", Map.of("k", "v"));
        LiveConfigProxy proxy = client.get("user-svc");

        String str = proxy.toString();
        assertTrue(str.contains("LiveConfigProxy"));
        assertTrue(str.contains("user-svc"));
        assertTrue(str.contains("k") && str.contains("v"));
    }
}
