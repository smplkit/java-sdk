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
    void resolve_returnsEmptyMapWhenNotConnected() {
        ConfigsApi mockApi = mock(ConfigsApi.class);
        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");

        Map<String, Object> values = configClient.resolve("my-config");
        assertTrue(values.isEmpty());
    }

    @Test
    void resolve_fetchesAndCachesConfigs() throws ApiException {
        ConfigsApi mockApi = mock(ConfigsApi.class);

        Map<String, Object> configData = new HashMap<>();
        configData.put("id", "db-config");
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
                        "id", "db-config",
                        "attributes", configData
                ))),
                ConfigListResponse.class);
        when(mockApi.listConfigs(isNull())).thenReturn(listResponse);

        // Without setEnvironment, resolve returns empty (no lazy init)
        ConfigClient noEnvClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        assertTrue(noEnvClient.resolve("db-config").isEmpty());

        // With setEnvironment, resolve triggers lazy init and returns data
        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        configClient.setEnvironment("production");

        Map<String, Object> resolved = configClient.resolve("db-config");
        assertEquals("prod-db.example.com", resolved.get("host"));
        assertEquals(5432, resolved.get("port"));

        // Nonexistent config
        assertTrue(configClient.resolve("nonexistent-config").isEmpty());
    }

    @Test
    void resolve_returnsFullResolvedMap() throws ApiException {
        ConfigsApi mockApi = mock(ConfigsApi.class);

        ConfigListResponse listResponse = OBJECT_MAPPER.convertValue(
                Map.of("data", List.of(Map.of(
                        "id", "app-config",
                        "attributes", Map.of(
                                "id", "app-config",
                                "name", "App Config",
                                "items", Map.of(
                                        "title", Map.of("type", "STRING", "value", "Default Title")
                                )
                        )
                ))),
                ConfigListResponse.class);
        when(mockApi.listConfigs(isNull())).thenReturn(listResponse);

        ConfigClient configClient = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        configClient.setEnvironment("staging");

        Map<String, Object> values = configClient.resolve("app-config");
        assertNotNull(values);
        assertEquals("Default Title", values.get("title"));

        assertTrue(configClient.resolve("unknown").isEmpty());
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
        attrs.put("id", "svc-flag");
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
                        "id", "svc-flag",
                        "type", "flag", "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull())).thenReturn(flagList);

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
        attrs.put("id", "svc-flag");
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
                        "id", "svc-flag",
                        "type", "flag", "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull())).thenReturn(flagList);

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
        flagsClient.setEnvironment("staging");

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", "svc-flag");
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
                        "id", "svc-flag",
                        "type", "flag", "attributes", attrs
                ))),
                FlagListResponse.class);
        when(mockApi.listFlags(isNull())).thenReturn(flagList);

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
        HttpClient mockHttp = mock(HttpClient.class);
        when(mockHttp.send(any(), any())).thenThrow(new java.io.IOException("No server"));

        SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "my-service",
                Duration.ofSeconds(5));

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

        assertDoesNotThrow(() -> {
            try (SmplClient client = new SmplClient(mockHttp, "test-key", "staging", "test-service",
                    Duration.ofSeconds(5), flagsClient, configClient, mockContextsApi)) {
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

        when(mockApi.listFlags(isNull()))
                .thenReturn(new FlagListResponse().data(List.of()));

        Flag<Boolean> handle = flagsClient.booleanFlag("unknown-flag", false);
        handle.get(List.of());
        handle.get(List.of());

        verify(mockApi, times(1)).listFlags(isNull());
    }
}
