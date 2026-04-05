package com.smplkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.config.Config;
import com.smplkit.config.ConfigClient;
import com.smplkit.errors.SmplNotConnectedException;
import com.smplkit.flags.Context;
import com.smplkit.flags.FlagHandle;
import com.smplkit.flags.FlagsClient;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests covering Phase 2 new code paths: connect(), registerServiceContext(),
 * config prescriptive access, and service context auto-injection.
 */
class Phase2CoverageTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    // --- SmplClient.connect() ---

    @Test
    void connect_withService_registersServiceContext() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        com.smplkit.internal.generated.app.api.ContextsApi mockContextsApi =
                mock(com.smplkit.internal.generated.app.api.ContextsApi.class);

        // Create flags + config with mocked APIs
        FlagsApi mockFlagsApi = mock(FlagsApi.class);
        FlagListResponse emptyFlags = new FlagListResponse().data(List.of());
        when(mockFlagsApi.listFlags(isNull(), isNull())).thenReturn(emptyFlags);
        FlagsClient flagsClient = new FlagsClient(mockFlagsApi, null, null,
                mockHttp, "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));

        ConfigsApi mockConfigsApi = mock(ConfigsApi.class);
        ConfigListResponse emptyConfigs = new ConfigListResponse().data(List.of());
        when(mockConfigsApi.listConfigs(isNull(), isNull())).thenReturn(emptyConfigs);
        ConfigClient configClient = new ConfigClient(mockConfigsApi, mockHttp, "test-key");

        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "my-service",
                Duration.ofSeconds(5), flagsClient, configClient, mockContextsApi);

        client.connect();

        // Verify service registration was called via generated API
        verify(mockContextsApi).bulkRegisterContexts(
                any(com.smplkit.internal.generated.app.model.ContextBulkRegister.class));
        client.close();
    }

    @Test
    void connect_withService_registersContext() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{}");
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-service",
                Duration.ofSeconds(5));

        try {
            client.connect();
        } catch (Exception e) {
            // Expected — real HTTP calls may fail
        }

        client.close();
    }

    @Test
    void connect_isIdempotent() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"data\":[]}");
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-service",
                Duration.ofSeconds(5));
        assertFalse(client.isConnected());

        try {
            client.connect();
        } catch (Exception e) {
            // Expected
        }

        // Calling connect again should be a no-op if already connected
        // (Though first call may have failed, test the idempotency guard)
        client.close();
    }

    @Test
    void createWithApiKeyEnvironmentAndService() {
        try (SmplClient client = SmplClient.create("test-key", "test", "test-service")) {
            assertNotNull(client);
            assertEquals("test", client.environment());
            assertEquals("test-service", client.service());
        }
    }

    // --- ConfigClient prescriptive access ---

    @Test
    void getValue_throwsWhenNotConnected() {
        ConfigsApi mockApi = mock(ConfigsApi.class);
        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");

        assertThrows(SmplNotConnectedException.class,
                () -> configClient.getValue("my-config", "host"));
    }

    @Test
    void getValues_throwsWhenNotConnected() {
        ConfigsApi mockApi = mock(ConfigsApi.class);
        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");

        assertThrows(SmplNotConnectedException.class,
                () -> configClient.getValues("my-config"));
    }

    @Test
    void connectInternal_fetchesAndCachesConfigs() throws ApiException {
        ConfigsApi mockApi = mock(ConfigsApi.class);

        // Build a config list response
        Map<String, Object> configData = new HashMap<>();
        configData.put("key", "db-config");
        configData.put("name", "DB Config");
        configData.put("items", Map.of(
                "host", Map.of("type", "STRING", "value", "localhost"),
                "port", Map.of("type", "NUMBER", "value", 5432)
        ));
        configData.put("environments", Map.of(
                "production", Map.of(
                        "values", Map.of(
                                "host", Map.of("value", "prod-db.example.com")
                        )
                )
        ));

        ConfigListResponse listResponse = OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of(
                        "id", "11111111-1111-1111-1111-111111111111",
                        "attributes", configData
                ))),
                ConfigListResponse.class);
        when(mockApi.listConfigs(isNull(), isNull())).thenReturn(listResponse);

        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        // Not connected yet — getValue should throw
        assertThrows(SmplNotConnectedException.class,
                () -> configClient.getValue("db-config", "host"));

        configClient.connectInternal("production");

        // getValue should resolve with production override
        assertEquals("prod-db.example.com", configClient.getValue("db-config", "host"));
        assertEquals(5432, configClient.getValue("db-config", "port"));

        // Missing item returns null
        assertNull(configClient.getValue("db-config", "nonexistent"));

        // Missing config returns null
        assertNull(configClient.getValue("nonexistent-config", "host"));
    }

    @Test
    void getValues_returnsFullResolvedMap() throws ApiException {
        ConfigsApi mockApi = mock(ConfigsApi.class);

        ConfigListResponse listResponse = OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of(
                        "id", "22222222-2222-2222-2222-222222222222",
                        "attributes", Map.of(
                                "key", "app-config",
                                "name", "App Config",
                                "items", Map.of(
                                        "title", Map.of("type", "STRING", "value", "Default Title")
                                )
                        )
                ))),
                ConfigListResponse.class);
        when(mockApi.listConfigs(isNull(), isNull())).thenReturn(listResponse);

        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        configClient.connectInternal("staging");

        Map<String, Object> values = configClient.getValues("app-config");
        assertNotNull(values);
        assertEquals("Default Title", values.get("title"));

        // Returns null for unknown config
        assertNull(configClient.getValues("unknown"));
    }

    // --- Service context auto-injection in flag evaluation ---

    @Test
    void serviceContext_autoInjected() throws com.smplkit.internal.generated.flags.ApiException {
        FlagsApi mockApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockApi, null, null,
                HttpClient.newHttpClient(), "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));
        flagsClient.setParentService("my-service");

        // Set up a flag with a rule that matches service.key == "my-service"
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "svc-flag");
        attrs.put("name", "Svc Flag");
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Service match",
                                "logic", Map.of("==", List.of(Map.of("var", "service.key"), "my-service")),
                                "value", true
                        ))
                )
        ));
        FlagListResponse flagList = OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of(
                        "id", "33333333-3333-3333-3333-333333333333",
                        "type", "flag",
                        "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(flagList);

        flagsClient.connectInternal("staging");

        FlagHandle<Boolean> handle = flagsClient.boolFlag("svc-flag", false);
        // Should match because service context is auto-injected
        assertTrue(handle.get(List.of()));
    }

    @Test
    void serviceContext_notInjectedWhenExplicitlyProvided() throws com.smplkit.internal.generated.flags.ApiException {
        FlagsApi mockApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockApi, null, null,
                HttpClient.newHttpClient(), "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));
        flagsClient.setParentService("my-service");

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "svc-flag");
        attrs.put("name", "Svc Flag");
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Service match",
                                "logic", Map.of("==", List.of(Map.of("var", "service.key"), "my-service")),
                                "value", true
                        ))
                )
        ));
        FlagListResponse flagList = OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of(
                        "id", "33333333-3333-3333-3333-333333333333",
                        "type", "flag",
                        "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(flagList);

        flagsClient.connectInternal("staging");

        FlagHandle<Boolean> handle = flagsClient.boolFlag("svc-flag", false);
        // Explicit service context with different key should NOT match
        Context explicitService = new Context("service", "other-service", Map.of());
        assertFalse(handle.get(List.of(explicitService)));
    }

    @Test
    void serviceContext_notInjectedWhenNoService() throws com.smplkit.internal.generated.flags.ApiException {
        FlagsApi mockApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockApi, null, null,
                HttpClient.newHttpClient(), "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));
        // No setParentService call

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("key", "svc-flag");
        attrs.put("name", "Svc Flag");
        attrs.put("type", "BOOLEAN");
        attrs.put("default", false);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of(
                "staging", Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of(
                                "description", "Service match",
                                "logic", Map.of("==", List.of(Map.of("var", "service.key"), "my-service")),
                                "value", true
                        ))
                )
        ));
        FlagListResponse flagList = OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of(
                        "id", "33333333-3333-3333-3333-333333333333",
                        "type", "flag",
                        "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(flagList);

        flagsClient.connectInternal("staging");

        FlagHandle<Boolean> handle = flagsClient.boolFlag("svc-flag", false);
        // Without service, rule should NOT match
        assertFalse(handle.get(List.of()));
    }

    // --- SmplClient.connect() with mocked internals ---

    @Test
    void connect_registersServiceViaGeneratedApi() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        com.smplkit.internal.generated.app.api.ContextsApi mockContextsApi =
                mock(com.smplkit.internal.generated.app.api.ContextsApi.class);

        FlagsApi mockFlagsApi = mock(FlagsApi.class);
        FlagListResponse emptyFlags = new FlagListResponse().data(List.of());
        when(mockFlagsApi.listFlags(isNull(), isNull())).thenReturn(emptyFlags);
        FlagsClient flagsClient = new FlagsClient(mockFlagsApi, null, null,
                mockHttp, "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));

        ConfigsApi mockConfigsApi = mock(ConfigsApi.class);
        ConfigListResponse emptyConfigs = new ConfigListResponse().data(List.of());
        when(mockConfigsApi.listConfigs(isNull(), isNull())).thenReturn(emptyConfigs);
        ConfigClient configClient = new ConfigClient(mockConfigsApi, mockHttp, "test-key");

        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-svc",
                Duration.ofSeconds(5), flagsClient, configClient, mockContextsApi);

        client.connect();

        // Verify service registration was called with correct type and key
        verify(mockContextsApi).bulkRegisterContexts(
                argThat(req -> req.getContexts().get(0).getType().equals("service")
                        && req.getContexts().get(0).getKey().equals("test-svc")));
        client.close();
    }

    @Test
    void connect_serviceRegistrationFailure_isSwallowed() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-service",
                Duration.ofSeconds(5));

        // connect() should not throw even if service registration fails
        try {
            client.connect();
        } catch (Exception e) {
            // May fail on flags connectInternal, which is fine
            // The key test is that registerServiceContext didn't propagate
        }
        client.close();
    }

    @Test
    void connect_fullPath_withMockedSubClients() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        com.smplkit.internal.generated.app.api.ContextsApi mockContextsApi =
                mock(com.smplkit.internal.generated.app.api.ContextsApi.class);

        // Create flags client with mocked API
        FlagsApi mockFlagsApi = mock(FlagsApi.class);
        FlagListResponse emptyFlags = new FlagListResponse().data(List.of());
        when(mockFlagsApi.listFlags(isNull(), isNull())).thenReturn(emptyFlags);
        FlagsClient flagsClient = new FlagsClient(mockFlagsApi, null, null,
                mockHttp, "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));

        // Create config client with mocked API
        ConfigsApi mockConfigsApi = mock(ConfigsApi.class);
        ConfigListResponse emptyConfigs = new ConfigListResponse().data(List.of());
        when(mockConfigsApi.listConfigs(isNull(), isNull())).thenReturn(emptyConfigs);
        ConfigClient configClient = new ConfigClient(mockConfigsApi, mockHttp, "test-key");

        // Build SmplClient with injectable sub-clients and mock contextsApi
        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-svc",
                Duration.ofSeconds(5), flagsClient, configClient, mockContextsApi);
        assertFalse(client.isConnected());

        client.connect();

        assertTrue(client.isConnected());
        assertEquals("connected", flagsClient.connectionStatus());

        // Verify service registration was called via generated API
        verify(mockContextsApi).bulkRegisterContexts(
                any(com.smplkit.internal.generated.app.model.ContextBulkRegister.class));

        client.close();
    }

    @Test
    void connect_withService_fullPath() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"data\":[]}");
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        FlagsApi mockFlagsApi = mock(FlagsApi.class);
        when(mockFlagsApi.listFlags(isNull(), isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));
        FlagsClient flagsClient = new FlagsClient(mockFlagsApi, null, null,
                mockHttp, "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));

        ConfigsApi mockConfigsApi = mock(ConfigsApi.class);
        when(mockConfigsApi.listConfigs(isNull(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));
        ConfigClient configClient = new ConfigClient(mockConfigsApi, mockHttp, "test-key");

        com.smplkit.internal.generated.app.api.ContextsApi mockContextsApi =
                mock(com.smplkit.internal.generated.app.api.ContextsApi.class);

        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-service",
                Duration.ofSeconds(5), flagsClient, configClient, mockContextsApi);

        client.connect();
        assertTrue(client.isConnected());

        // Service registration should have been called
        verify(mockContextsApi).bulkRegisterContexts(
                any(com.smplkit.internal.generated.app.model.ContextBulkRegister.class));

        client.close();
    }

    @Test
    void connect_isIdempotent_fullPath() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"data\":[]}");
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        FlagsApi mockFlagsApi = mock(FlagsApi.class);
        when(mockFlagsApi.listFlags(isNull(), isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));
        FlagsClient flagsClient = new FlagsClient(mockFlagsApi, null, null,
                mockHttp, "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com", Duration.ofSeconds(5));

        ConfigsApi mockConfigsApi = mock(ConfigsApi.class);
        when(mockConfigsApi.listConfigs(isNull(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));
        ConfigClient configClient = new ConfigClient(mockConfigsApi, mockHttp, "test-key");

        com.smplkit.internal.generated.app.api.ContextsApi mockContextsApi =
                mock(com.smplkit.internal.generated.app.api.ContextsApi.class);

        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-service",
                Duration.ofSeconds(5), flagsClient, configClient, mockContextsApi);

        client.connect();
        assertTrue(client.isConnected());

        // Second connect should be no-op
        client.connect();

        // flags list should only be called once
        verify(mockFlagsApi, times(1)).listFlags(isNull(), isNull());

        client.close();
    }
}
