package com.smplkit.account;

import com.smplkit.errors.SmplError;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.AccountApi;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the {@code com.smplkit.account} package: the {@link AccountSettings}
 * model, the {@link SettingsClient} (get via mocked {@link AccountApi}, save via
 * a local {@link com.sun.net.httpserver.HttpServer}), the async wrappers, and the
 * {@link AccountClient}/{@link AccountClientBuilder} construction surface.
 *
 * <p>No real network: {@code get()} is exercised against a Mockito mock injected
 * into the {@code api} field; {@code save()} hits a loopback HTTP server bound to
 * 127.0.0.1:0. Construction always supplies an explicit API key, so it is
 * hermetic on a clean CI runner with no {@code ~/.smplkit}.</p>
 */
class AccountTest {

    // =======================================================================
    // AccountSettings model
    // =======================================================================

    @Test
    void accountSettings_environmentOrder_empty() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        assertTrue(s.environmentOrder().isEmpty());
    }

    @Test
    void accountSettings_setEnvironmentOrder() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        s.setEnvironmentOrder(List.of("production", "staging"));
        assertEquals(List.of("production", "staging"), s.environmentOrder());
    }

    @Test
    void accountSettings_environmentOrder_fromList() {
        Map<String, Object> data = new HashMap<>();
        data.put("environment_order", List.of("production", "staging"));
        AccountSettings s = new AccountSettings(null, data);
        assertEquals(List.of("production", "staging"), s.environmentOrder());
    }

    @Test
    void accountSettings_environmentOrder_filtersNonStrings() {
        Map<String, Object> data = new HashMap<>();
        data.put("environment_order", java.util.Arrays.asList("production", 42, "staging"));
        AccountSettings s = new AccountSettings(null, data);
        assertEquals(List.of("production", "staging"), s.environmentOrder());
    }

    @Test
    void accountSettings_environmentOrder_nonListValue_returnsEmpty() {
        Map<String, Object> data = new HashMap<>();
        data.put("environment_order", "not-a-list");
        AccountSettings s = new AccountSettings(null, data);
        assertTrue(s.environmentOrder().isEmpty());
    }

    @Test
    void accountSettings_setEnvironmentOrder_null() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        s.setEnvironmentOrder(null);
        assertTrue(s.environmentOrder().isEmpty());
    }

    @Test
    void accountSettings_raw() {
        Map<String, Object> data = new HashMap<>();
        data.put("key", "value");
        AccountSettings s = new AccountSettings(null, data);
        assertEquals("value", s.raw().get("key"));
    }

    @Test
    void accountSettings_setRaw() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        s.setRaw(Map.of("a", "b"));
        assertEquals("b", s.raw().get("a"));
    }

    @Test
    void accountSettings_setRaw_null() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        s.setRaw(null);
        assertTrue(s.raw().isEmpty());
    }

    @Test
    void accountSettings_nullData_isEmptyMap() {
        AccountSettings s = new AccountSettings(null, null);
        assertTrue(s.raw().isEmpty());
    }

    @Test
    void accountSettings_toString() {
        AccountSettings s = new AccountSettings(null, Map.of("x", "y"));
        assertTrue(s.toString().contains("AccountSettings"));
    }

    @Test
    void accountSettings_save_withoutClient_throws() {
        AccountSettings s = new AccountSettings(null, new HashMap<>());
        assertThrows(IllegalStateException.class, s::save);
    }

    @Test
    void accountSettings_saveAsync_default_returnsFuture() {
        AccountSettings s = new AccountSettings(null, null);
        assertNotNull(s.saveAsync());
    }

    @Test
    void accountSettings_saveAsync_propagatesUnboundClient() {
        AccountSettings unbound = new AccountSettings(null, null);
        var fut = unbound.saveAsync();
        Exception ex = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> fut.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void accountSettings_saveAsyncWithExecutor_usesExecutor() throws Exception {
        AccountSettings unbound = new AccountSettings(null, null);
        AtomicBoolean used = new AtomicBoolean(false);
        Executor inline = r -> { used.set(true); r.run(); };
        try {
            unbound.saveAsync(inline).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // save() fails (unbound client) — only care that the executor ran.
        }
        assertTrue(used.get());
    }

    // =======================================================================
    // SettingsClient.get() — via reflection-injected mock AccountApi
    // =======================================================================

    @Test
    void settingsClient_get_returnsSettings() throws Exception {
        AccountApi mockApi = mock(AccountApi.class);
        SettingsClient client = new SettingsClient("http://localhost", "key", null);
        injectApi(client, mockApi);
        when(mockApi.getAccountSettings())
                .thenReturn(Map.of("environment_order", List.of("production", "staging")));

        AccountSettings settings = client.get();
        assertEquals(List.of("production", "staging"), settings.environmentOrder());
    }

    @Test
    void settingsClient_get_nullResponse_returnsEmpty() throws Exception {
        AccountApi mockApi = mock(AccountApi.class);
        SettingsClient client = new SettingsClient("http://localhost", "key", null);
        injectApi(client, mockApi);
        when(mockApi.getAccountSettings()).thenReturn(null);

        AccountSettings settings = client.get();
        assertTrue(settings.environmentOrder().isEmpty());
    }

    @Test
    void settingsClient_get_apiException() throws Exception {
        AccountApi mockApi = mock(AccountApi.class);
        SettingsClient client = new SettingsClient("http://localhost", "key", null);
        injectApi(client, mockApi);
        when(mockApi.getAccountSettings()).thenThrow(new ApiException(500, "error"));
        assertThrows(SmplError.class, client::get);
    }

    @Test
    void settingsClient_get_apiException_zeroCode() throws Exception {
        AccountApi mockApi = mock(AccountApi.class);
        SettingsClient client = new SettingsClient("http://localhost", "key", null);
        injectApi(client, mockApi);
        when(mockApi.getAccountSettings()).thenThrow(new ApiException(0, "connection error"));
        assertThrows(SmplError.class, client::get);
    }

    @Test
    void settingsClient_constructor_stripsTrailingSlash() throws Exception {
        // A base URL with trailing slashes is normalized; the _save URL must not
        // contain a doubled slash. Verified indirectly via the HttpServer path.
        String responseBody = "{\"environment_order\":[\"production\"]}";
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/accounts/current/settings", exchange -> {
            byte[] resp = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SettingsClient client = new SettingsClient("http://127.0.0.1:" + port + "///", "test-key", Map.of("X-H", "v"));
            AccountSettings settings = new AccountSettings(client, Map.of());
            settings.save();
            assertEquals(List.of("production"), settings.environmentOrder());
        } finally {
            server.stop(0);
        }
    }

    // =======================================================================
    // SettingsClient._save() — via local HttpServer
    // =======================================================================

    @Test
    void settingsClient_save_successViaHttpServer() throws Exception {
        String responseBody = "{\"environment_order\":[\"production\"]}";
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/accounts/current/settings", exchange -> {
            byte[] resp = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SettingsClient client = new SettingsClient("http://127.0.0.1:" + port, "test-key", null);
            AccountSettings settings = new AccountSettings(client, Map.of());
            settings.setEnvironmentOrder(List.of("staging"));
            settings.save();
            assertEquals(List.of("production"), settings.environmentOrder());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void settingsClient_save_errorStatusThrows() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/accounts/current/settings", exchange -> {
            byte[] resp = "{\"error\":\"unauthorized\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SettingsClient client = new SettingsClient("http://127.0.0.1:" + port, "test-key", null);
            AccountSettings settings = new AccountSettings(client, Map.of());
            assertThrows(SmplError.class, settings::save);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void settingsClient_constructor_nullBaseUrl_takesNullArm() {
        // The null arm of the base-URL ternary assigns the null base URL through
        // unchanged (no stripTrailingSlash); construction then fails downstream
        // when the generated ApiClient calls URI.create(null). The null-arm
        // assignment still executes before the failure, which is the line under
        // test. No HTTP is performed, so this is hermetic.
        assertThrows(NullPointerException.class,
                () -> new SettingsClient(null, "test-key", null));
    }

    @Test
    void settingsClient_save_responseBodyNull_yieldsEmptySettings() throws Exception {
        // When the server returns a JSON literal `null` body, deserialization
        // produces a null map and _save must coalesce it to an empty map
        // (the null arm of the saved-map ternary).
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/accounts/current/settings", exchange -> {
            byte[] resp = "null".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SettingsClient client = new SettingsClient("http://127.0.0.1:" + port, "test-key", null);
            AccountSettings settings = new AccountSettings(client, Map.of());
            settings.save();
            assertTrue(settings.environmentOrder().isEmpty());
            assertTrue(settings.raw().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void settingsClient_save_connectionRefused_throws() {
        SettingsClient client = new SettingsClient("http://127.0.0.1:1", "test-key", null);
        AccountSettings settings = new AccountSettings(client, Map.of());
        assertThrows(SmplError.class, settings::save);
    }

    @Test
    void settingsClient_save_viaSaveAsync_successViaHttpServer() throws Exception {
        String responseBody = "{\"environment_order\":[\"prod\"]}";
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/accounts/current/settings", exchange -> {
            byte[] resp = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SettingsClient client = new SettingsClient("http://127.0.0.1:" + port, "test-key", null);
            AccountSettings settings = new AccountSettings(client, Map.of());
            settings.saveAsync().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("prod"), settings.environmentOrder());
        } finally {
            server.stop(0);
        }
    }

    // =======================================================================
    // AsyncSettingsClient — get delegates to the executor
    // =======================================================================

    @Test
    void asyncSettingsClient_get_delegatesToExecutor() throws Exception {
        AccountApi mockApi = mock(AccountApi.class);
        SettingsClient sync = new SettingsClient("http://localhost", "key", null);
        injectApi(sync, mockApi);
        when(mockApi.getAccountSettings()).thenReturn(Map.of("environment_order", List.of("production")));

        ExecutorService exec = Executors.newSingleThreadExecutor();
        AtomicInteger ran = new AtomicInteger();
        Executor counting = r -> { ran.incrementAndGet(); exec.execute(r); };
        try {
            AsyncSettingsClient async = new AsyncSettingsClient(sync, counting);
            AccountSettings settings = async.get().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("production"), settings.environmentOrder());
            assertEquals(1, ran.get());
        } finally {
            exec.shutdownNow();
        }
    }

    // =======================================================================
    // AccountClient + AccountClientBuilder construction
    // =======================================================================

    @Test
    void accountClient_wiredConstructor_buildsSettings() {
        try (AccountClient account = new AccountClient("test-key", "http://localhost:8000", Map.of("X-H", "v"))) {
            assertNotNull(account.settings);
        }
    }

    @Test
    void accountClient_wiredConstructor_nullHeaders() {
        try (AccountClient account = new AccountClient("test-key", "http://localhost:8000", null)) {
            assertNotNull(account.settings);
        }
    }

    @Test
    void accountClient_create_withApiKey() {
        try (AccountClient account = AccountClient.create("test-key")) {
            assertNotNull(account.settings);
        }
    }

    @Test
    void accountClient_create_noArg_coversFactory() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_account_create");
        try (AccountClient account = AccountClient.create()) {
            assertNotNull(account.settings);
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void accountClient_close_isIdempotent() {
        AccountClient account = AccountClient.create("test-key");
        account.close();
        account.close();
    }

    @Test
    void accountClientBuilder_allSetters_build() {
        try (AccountClient account = AccountClient.builder()
                .profile("default")
                .apiKey("test-key")
                .baseDomain("smplkit.example")
                .scheme("https")
                .debug(false)
                .extraHeaders(Map.of("X-Test", "1"))
                .build()) {
            assertNotNull(account.settings);
        }
    }

    @Test
    void accountClientBuilder_baseUrl_override() {
        try (AccountClient account = AccountClient.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost:9000")
                .build()) {
            assertNotNull(account.settings);
        }
    }

    @Test
    void accountClientBuilder_extraHeaders_null_ignored() {
        try (AccountClient account = AccountClient.builder()
                .apiKey("test-key")
                .extraHeaders(null)
                .build()) {
            assertNotNull(account.settings);
        }
    }

    @Test
    void accountClientBuilder_rejectsNullArgs() {
        assertThrows(NullPointerException.class, () -> AccountClient.builder().profile(null));
        assertThrows(NullPointerException.class, () -> AccountClient.builder().apiKey(null));
        assertThrows(NullPointerException.class, () -> AccountClient.builder().baseUrl(null));
        assertThrows(NullPointerException.class, () -> AccountClient.builder().baseDomain(null));
        assertThrows(NullPointerException.class, () -> AccountClient.builder().scheme(null));
    }

    // =======================================================================
    // AsyncAccountClient construction + delegation
    // =======================================================================

    @Test
    void asyncAccountClient_create_withApiKey() {
        AsyncAccountClient account = AsyncAccountClient.create("test-key");
        assertNotNull(account.settings);
        assertNotNull(account.sync());
        assertNotNull(account.executor());
    }

    @Test
    void asyncAccountClient_create_noArg_coversFactory() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_async_account_create");
        try {
            AsyncAccountClient account = AsyncAccountClient.create();
            assertNotNull(account.settings);
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void asyncAccountClient_wrap_reusesSyncClient() {
        try (AccountClient sync = AccountClient.create("test-key")) {
            AsyncAccountClient async = AsyncAccountClient.wrap(sync);
            assertSame(sync, async.sync());
            assertNotNull(async.settings);
        }
    }

    @Test
    void asyncAccountClient_wrap_customExecutor() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try (AccountClient sync = AccountClient.create("test-key")) {
            AsyncAccountClient async = AsyncAccountClient.wrap(sync, exec);
            assertSame(exec, async.executor());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void asyncAccountClient_settings_get_returnsFuture() throws Exception {
        AccountApi mockApi = mock(AccountApi.class);
        try (AccountClient sync = AccountClient.create("test-key")) {
            injectApi(sync.settings, mockApi);
            when(mockApi.getAccountSettings()).thenReturn(Map.of("environment_order", List.of("production")));

            AsyncAccountClient async = AsyncAccountClient.wrap(sync);
            AccountSettings settings = async.settings.get().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("production"), settings.environmentOrder());
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private static void injectApi(SettingsClient client, AccountApi mockApi) throws Exception {
        var f = SettingsClient.class.getDeclaredField("api");
        f.setAccessible(true);
        f.set(client, mockApi);
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((Map<String, String>) field.get(env)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearEnv(String key) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((Map<String, String>) field.get(env)).remove(key);
    }
}
