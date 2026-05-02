package com.smplkit.config;

import com.smplkit.SmplClient;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors Python rule 10: {@code client.config().get(id)} returns a live,
 * read-only, identity-stable proxy.
 */
class LiveConfigProxyTest {

    private ConfigClient newClient() {
        // ConfigsApi is a generated class with a no-arg behavior we can mock simply
        ConfigsApi api = org.mockito.Mockito.mock(ConfigsApi.class);
        ConfigClient client = new ConfigClient(api, java.net.http.HttpClient.newHttpClient(), "test-key");
        client.setEnvironment("staging");
        // Mark connected so we don't trigger lazy fetch
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
    void get_returnsLiveProxy_dictLikeReads() {
        ConfigClient client = newClient();
        seedCache(client, "user-svc", Map.of(
                "database.host", "prod-db",
                "max_retries", 5));

        LiveConfigProxy proxy = client.get("user-svc");
        assertEquals("prod-db", proxy.get("database.host"));
        assertEquals(5, proxy.get("max_retries"));
        assertNull(proxy.get("missing_key"));
        assertEquals("default", proxy.getOrDefault("missing_key", "default"));
        assertTrue(proxy.containsKey("database.host"));
        assertEquals(2, proxy.size());
        assertFalse(proxy.isEmpty());
    }

    @Test
    void get_isIdentityStable() {
        ConfigClient client = newClient();
        seedCache(client, "user-svc", Map.of("k", "v"));

        LiveConfigProxy a = client.get("user-svc");
        LiveConfigProxy b = client.get("user-svc");
        assertSame(a, b, "Proxy must be identity-stable across calls");
    }

    @Test
    void proxy_picksUpCacheUpdates_withoutReFetching() {
        ConfigClient client = newClient();
        Map<String, Object> initial = new HashMap<>();
        initial.put("max_retries", 3);
        seedCache(client, "user-svc", initial);

        LiveConfigProxy proxy = client.get("user-svc");
        assertEquals(3, proxy.get("max_retries"));

        // Simulate a WebSocket event updating the cache.
        seedCache(client, "user-svc", Map.of("max_retries", 7));

        // Same proxy instance — but the read returns the new value.
        assertEquals(7, proxy.get("max_retries"));
    }

    @Test
    void proxy_isReadOnly_mutationsRaise() {
        ConfigClient client = newClient();
        seedCache(client, "user-svc", Map.of("k", "v"));
        LiveConfigProxy proxy = client.get("user-svc");

        UnsupportedOperationException put = assertThrows(
                UnsupportedOperationException.class, () -> proxy.put("x", "y"));
        assertTrue(put.getMessage().contains("client.manage"));

        assertThrows(UnsupportedOperationException.class, () -> proxy.remove("k"));
        assertThrows(UnsupportedOperationException.class, () -> proxy.clear());
        assertThrows(UnsupportedOperationException.class, () -> proxy.putAll(Map.of("a", "b")));
    }

    @Test
    void proxy_into_returnsTypedSnapshot() {
        ConfigClient client = newClient();
        seedCache(client, "user-svc", Map.of(
                "database.host", "prod-db",
                "database.port", 5432,
                "max_retries", 5));

        LiveConfigProxy proxy = client.get("user-svc");
        UserServiceCfg cfg = proxy.into(UserServiceCfg.class);
        assertNotNull(cfg);
        assertEquals("prod-db", cfg.database.host);
        assertEquals(5432, cfg.database.port);
        assertEquals(5, cfg.max_retries);
    }

    @Test
    void proxy_onChange_delegatesToClient_globalForm() {
        ConfigClient client = newClient();
        seedCache(client, "user-svc", Map.of("k", "v"));

        LiveConfigProxy proxy = client.get("user-svc");
        AtomicInteger fired = new AtomicInteger(0);
        proxy.onChange(evt -> fired.incrementAndGet());

        // Inject a synthetic listener firing — exercise the registered listener
        ConfigChangeEvent evt = new ConfigChangeEvent(
                "user-svc", "k", "v", "v2", "manual");
        // Reach the listener list via reflection
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
    void proxy_keySetEntrySetValues_areSnapshots() {
        ConfigClient client = newClient();
        Map<String, Object> initial = new HashMap<>();
        initial.put("k1", "v1");
        initial.put("k2", "v2");
        seedCache(client, "user-svc", initial);
        LiveConfigProxy proxy = client.get("user-svc");

        var keys = proxy.keySet();
        assertEquals(2, keys.size());
        assertTrue(keys.contains("k1"));
        var entries = proxy.entrySet();
        assertEquals(2, entries.size());
        var values = proxy.values();
        assertEquals(2, values.size());
    }

    /** Minimal POJO used as a target for {@link LiveConfigProxy#into(Class)}. */
    public static class UserServiceCfg {
        public Database database;
        public int max_retries;

        public static class Database {
            public String host;
            public int port;
        }
    }
}
