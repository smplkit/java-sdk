package com.smplkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smplkit.config.ConfigClient;
import com.smplkit.flags.Flag;
import com.smplkit.flags.FlagsClient;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests covering service context auto-injection, config prescriptive access,
 * and SmplClient construction patterns.
 */
class Phase2CoverageTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    // --- ConfigClient prescriptive access ---

    @Test
    void getValue_returnsNullWhenNotConnected() {
        ConfigsApi mockApi = mock(ConfigsApi.class);
        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");

        assertNull(configClient.getValue("my-config", "host"));
    }

    @Test
    void getValues_returnsNullWhenNotConnected() {
        ConfigsApi mockApi = mock(ConfigsApi.class);
        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");

        assertNull(configClient.getValues("my-config"));
    }

    @Test
    void connectInternal_fetchesAndCachesConfigs() throws ApiException {
        ConfigsApi mockApi = mock(ConfigsApi.class);

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
        assertNull(configClient.getValue("db-config", "host"));

        configClient.connectInternal("production");

        assertEquals("prod-db.example.com", configClient.getValue("db-config", "host"));
        assertEquals(5432, configClient.getValue("db-config", "port"));
        assertNull(configClient.getValue("db-config", "nonexistent"));
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

        assertNull(configClient.getValues("unknown"));
    }

    // --- Service context auto-injection in flag evaluation ---

    @Test
    void serviceContext_autoInjected() throws com.smplkit.internal.generated.flags.ApiException {
        FlagsApi mockApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockApi, null, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        flagsClient.setParentService("my-service");
        flagsClient.setEnvironment("staging");

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
                        "type", "flag", "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(flagList);

        // get() triggers lazy init (_connectInternal) automatically
        Flag<Boolean> handle = flagsClient.booleanFlag("svc-flag", false);
        assertTrue(handle.get(List.of()));
    }

    @Test
    void serviceContext_notInjectedWhenExplicitlyProvided() throws com.smplkit.internal.generated.flags.ApiException {
        FlagsApi mockApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockApi, null, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        flagsClient.setParentService("my-service");
        flagsClient.setEnvironment("staging");

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
                        "type", "flag", "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(flagList);

        // get() triggers lazy init; explicit service context with different key should NOT match
        Flag<Boolean> handle = flagsClient.booleanFlag("svc-flag", false);
        Context explicitService = new Context("service", "other-service", Map.of());
        assertFalse(handle.get(List.of(explicitService)));
    }

    @Test
    void serviceContext_notInjectedWhenNoService() throws com.smplkit.internal.generated.flags.ApiException {
        FlagsApi mockApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockApi, null, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        // No setParentService
        flagsClient.setEnvironment("staging");

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
                        "type", "flag", "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull(), isNull())).thenReturn(flagList);

        // get() triggers lazy init; without service the rule should NOT match
        Flag<Boolean> handle = flagsClient.booleanFlag("svc-flag", false);
        assertFalse(handle.get(List.of()));
    }

    // --- SmplClient construction patterns ---

    @Test
    void createWithApiKeyEnvironmentAndService() {
        try (SmplClient client = SmplClient.create("test-key", "test", "test-service")) {
            assertNotNull(client);
            assertEquals("test", client.environment());
            assertEquals("test-service", client.service());
        }
    }

    @Test
    void smplClient_registersServiceContext_viaStandardConstructor() throws Exception {
        // The standard (non-injectable) constructors call registerServiceContext().
        // Use the 5-param HttpClient constructor which calls buildContextsApi internally.
        // The call will fail (no real server) but we just verify it doesn't throw.
        HttpClient mockHttp = mock(HttpClient.class);
        when(mockHttp.send(any(), any())).thenThrow(new java.io.IOException("No server"));

        // This constructor calls registerServiceContext() which catches exceptions
        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "my-service",
                Duration.ofSeconds(5));

        // service() confirms the client was constructed
        assertEquals("my-service", client.service());
        client.close();
    }

    @Test
    void smplClient_serviceRegistrationFailure_isSwallowed() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        com.smplkit.internal.generated.app.api.ContextsApi mockContextsApi =
                mock(com.smplkit.internal.generated.app.api.ContextsApi.class);

        when(mockContextsApi.bulkRegisterContexts(any()))
                .thenThrow(new com.smplkit.internal.generated.app.ApiException(500, "fail"));

        FlagsApi mockFlagsApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockFlagsApi, null, mockHttp,
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        ConfigsApi mockConfigsApi = mock(ConfigsApi.class);
        ConfigClient configClient = new ConfigClient(mockConfigsApi, mockHttp, "test-key");

        // Constructor should not throw even if context registration fails
        assertDoesNotThrow(() -> {
            try (SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-service",
                    Duration.ofSeconds(5), flagsClient, configClient, mockContextsApi)) {
                // The constructor calls registerServiceContext() which swallows the exception
                verify(mockContextsApi).bulkRegisterContexts(any());
            }
        });
    }

    @Test
    void smplClient_serviceRegistrationSuccess_callsBulkRegister() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        com.smplkit.internal.generated.app.api.ContextsApi mockContextsApi =
                mock(com.smplkit.internal.generated.app.api.ContextsApi.class);

        FlagsApi mockFlagsApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockFlagsApi, null, mockHttp,
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        ConfigsApi mockConfigsApi = mock(ConfigsApi.class);
        ConfigClient configClient = new ConfigClient(mockConfigsApi, mockHttp, "test-key");

        try (SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "my-service",
                Duration.ofSeconds(5), flagsClient, configClient, mockContextsApi)) {
            verify(mockContextsApi).bulkRegisterContexts(any());
        }
    }

    @Test
    void flagsClient_lazyInitIsIdempotent() throws com.smplkit.internal.generated.flags.ApiException {
        FlagsApi mockApi = mock(FlagsApi.class);
        FlagsClient flagsClient = new FlagsClient(mockApi, null, HttpClient.newHttpClient(),
                "test-key", "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        flagsClient.setEnvironment("staging");

        when(mockApi.listFlags(isNull(), isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));

        // Two evaluations trigger only one listFlags call (lazy init is idempotent)
        Flag<Boolean> handle = flagsClient.booleanFlag("unknown-flag", false);
        handle.get(List.of()); // triggers lazy init
        handle.get(List.of()); // should NOT re-init

        verify(mockApi, times(1)).listFlags(isNull(), isNull());
    }
}
