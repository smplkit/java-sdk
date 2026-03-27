package com.smplkit.config;

import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplConnectionException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplTimeoutException;
import com.smplkit.errors.SmplValidationException;
import com.smplkit.internal.Auth;
import com.smplkit.internal.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ConfigClient} using a mocked {@link HttpClient}.
 */
class ConfigClientTest {

    private HttpClient mockHttpClient;
    private ConfigClient configClient;

    private static final String SINGLE_CONFIG_RESPONSE = """
            {
              "data": {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "type": "config",
                "attributes": {
                  "key": "user_service",
                  "name": "User Service",
                  "description": "Main user service config",
                  "parent": null,
                  "values": {"timeout": 30, "retries": 3},
                  "environments": {},
                  "created_at": "2024-01-15T10:30:00Z",
                  "updated_at": "2024-01-15T10:30:00Z"
                }
              }
            }
            """;

    private static final String LIST_RESPONSE = """
            {
              "data": [
                {
                  "id": "550e8400-e29b-41d4-a716-446655440000",
                  "type": "config",
                  "attributes": {
                    "key": "user_service",
                    "name": "User Service",
                    "description": null,
                    "parent": null,
                    "values": {"timeout": 30},
                    "environments": {},
                    "created_at": "2024-01-15T10:30:00Z",
                    "updated_at": "2024-01-15T10:30:00Z"
                  }
                },
                {
                  "id": "660e8400-e29b-41d4-a716-446655440001",
                  "type": "config",
                  "attributes": {
                    "key": "payment_service",
                    "name": "Payment Service",
                    "description": "Payments config",
                    "parent": "550e8400-e29b-41d4-a716-446655440000",
                    "values": {"currency": "USD"},
                    "environments": {},
                    "created_at": "2024-01-16T12:00:00Z",
                    "updated_at": "2024-01-16T12:00:00Z"
                  }
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        mockHttpClient = Mockito.mock(HttpClient.class);
        Auth auth = new Auth("test-api-key");
        Transport transport = new Transport(mockHttpClient, auth, "https://config.smplkit.com", Duration.ofSeconds(30));
        configClient = new ConfigClient(transport);
    }

    @Test
    void getById_returnsConfig() throws Exception {
        stubResponse(200, SINGLE_CONFIG_RESPONSE);

        Config config = configClient.get("550e8400-e29b-41d4-a716-446655440000");

        assertEquals("550e8400-e29b-41d4-a716-446655440000", config.id());
        assertEquals("user_service", config.key());
        assertEquals("User Service", config.name());
        assertEquals("Main user service config", config.description());
        assertNull(config.parent());
        assertEquals(30.0, config.values().get("timeout"));
        assertEquals(3.0, config.values().get("retries"));
        assertNotNull(config.createdAt());
        assertNotNull(config.updatedAt());
    }

    @Test
    void getByKey_returnsConfig() throws Exception {
        stubResponse(200, LIST_RESPONSE);

        Config config = configClient.getByKey("user_service");

        assertEquals("550e8400-e29b-41d4-a716-446655440000", config.id());
        assertEquals("user_service", config.key());
        assertEquals("User Service", config.name());
    }

    @Test
    void getByKey_throwsNotFoundWhenEmpty() throws Exception {
        stubResponse(200, """
                {"data": []}
                """);

        assertThrows(SmplNotFoundException.class, () ->
                configClient.getByKey("nonexistent"));
    }

    @Test
    void list_returnsConfigs() throws Exception {
        stubResponse(200, LIST_RESPONSE);

        List<Config> configs = configClient.list();

        assertEquals(2, configs.size());
        assertEquals("user_service", configs.get(0).key());
        assertEquals("payment_service", configs.get(1).key());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", configs.get(1).parent());
    }

    @Test
    void list_returnsUnmodifiableList() throws Exception {
        stubResponse(200, LIST_RESPONSE);

        List<Config> configs = configClient.list();

        assertThrows(UnsupportedOperationException.class, () ->
                configs.add(new Config("id", "key", "name", null, null, Map.of(), Map.of(), null, null)));
    }

    @Test
    void create_sendsBodyAndReturnsConfig() throws Exception {
        stubResponse(201, SINGLE_CONFIG_RESPONSE);

        CreateConfigParams params = CreateConfigParams.builder("User Service")
                .key("user_service")
                .description("Main user service config")
                .values(Map.of("timeout", 30, "retries", 3))
                .build();

        Config config = configClient.create(params);

        assertEquals("550e8400-e29b-41d4-a716-446655440000", config.id());
        assertEquals("User Service", config.name());
    }

    @Test
    void create_withMinimalParams() throws Exception {
        stubResponse(201, SINGLE_CONFIG_RESPONSE);

        CreateConfigParams params = CreateConfigParams.builder("My Config").build();
        Config config = configClient.create(params);

        assertNotNull(config);
    }

    @Test
    void create_withParent() throws Exception {
        stubResponse(201, SINGLE_CONFIG_RESPONSE);

        CreateConfigParams params = CreateConfigParams.builder("Child Config")
                .parent("parent-uuid")
                .build();
        Config config = configClient.create(params);

        assertNotNull(config);
    }

    @Test
    void delete_sendsDeleteRequest() throws Exception {
        stubResponse(204, "");

        assertDoesNotThrow(() ->
                configClient.delete("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void get_404_throwsNotFoundException() throws Exception {
        stubResponse(404, "{\"errors\":[{\"detail\":\"Not found\"}]}");

        SmplNotFoundException ex = assertThrows(SmplNotFoundException.class, () ->
                configClient.get("nonexistent-id"));
        assertEquals(404, ex.statusCode());
    }

    @Test
    void delete_409_throwsConflictException() throws Exception {
        stubResponse(409, "{\"errors\":[{\"detail\":\"Has children\"}]}");

        SmplConflictException ex = assertThrows(SmplConflictException.class, () ->
                configClient.delete("some-id"));
        assertEquals(409, ex.statusCode());
    }

    @Test
    void create_422_throwsValidationException() throws Exception {
        stubResponse(422, "{\"errors\":[{\"detail\":\"Name is required\"}]}");

        CreateConfigParams params = CreateConfigParams.builder("").build();
        SmplValidationException ex = assertThrows(SmplValidationException.class, () ->
                configClient.create(params));
        assertEquals(422, ex.statusCode());
    }

    @Test
    void connectionError_throwsSmplConnectionException() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new ConnectException("Connection refused"));

        assertThrows(SmplConnectionException.class, () ->
                configClient.list());
    }

    @Test
    void ioError_throwsSmplConnectionException() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("Network error"));

        assertThrows(SmplConnectionException.class, () ->
                configClient.list());
    }

    @Test
    void timeout_throwsSmplTimeoutException() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new HttpTimeoutException("Request timed out"));

        assertThrows(SmplTimeoutException.class, () ->
                configClient.list());
    }

    @Test
    void interrupted_throwsSmplConnectionException() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new InterruptedException("interrupted"));

        assertThrows(SmplConnectionException.class, () ->
                configClient.list());
    }

    @Test
    void configRecord_equality() {
        Config c1 = new Config("id1", "key", "name", null, null, Map.of(), Map.of(), null, null);
        Config c2 = new Config("id1", "key", "name", null, null, Map.of(), Map.of(), null, null);
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void configRecord_toString() {
        Config config = new Config("id1", "key1", "My Config", null, null, Map.of(), Map.of(), null, null);
        assertEquals("Config[id=id1, key=key1, name=My Config]", config.toString());
    }

    @Test
    void configRecord_nullValues_defaultToEmptyMap() {
        Config config = new Config("id1", "key1", "name", null, null, null, null, null, null);
        assertNotNull(config.values());
        assertTrue(config.values().isEmpty());
        assertNotNull(config.environments());
        assertTrue(config.environments().isEmpty());
    }

    @Test
    void configRecord_requiresNonNullIdKeyName() {
        assertThrows(NullPointerException.class, () ->
                new Config(null, "key", "name", null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () ->
                new Config("id", null, "name", null, null, null, null, null, null));
        assertThrows(NullPointerException.class, () ->
                new Config("id", "key", null, null, null, null, null, null, null));
    }

    @Test
    void createConfigParams_builder() {
        CreateConfigParams params = CreateConfigParams.builder("Test Config")
                .key("test_config")
                .description("A test config")
                .parent("parent-id")
                .values(Map.of("key", "value"))
                .build();

        assertEquals("Test Config", params.name());
        assertEquals("test_config", params.key());
        assertEquals("A test config", params.description());
        assertEquals("parent-id", params.parent());
        assertEquals(Map.of("key", "value"), params.values());
    }

    @Test
    void createConfigParams_minimalBuilder() {
        CreateConfigParams params = CreateConfigParams.builder("Minimal").build();

        assertEquals("Minimal", params.name());
        assertNull(params.key());
        assertNull(params.description());
        assertNull(params.parent());
        assertNull(params.values());
    }

    @Test
    void createConfigParams_requiresName() {
        assertThrows(NullPointerException.class, () ->
                CreateConfigParams.builder(null));
    }

    @Test
    void parseConfigWithNullEnvironments() throws Exception {
        String response = """
                {
                  "data": {
                    "id": "abc-123",
                    "type": "config",
                    "attributes": {
                      "key": "test_key",
                      "name": "Test",
                      "description": null,
                      "parent": null,
                      "values": null,
                      "environments": null,
                      "created_at": null,
                      "updated_at": null
                    }
                  }
                }
                """;
        stubResponse(200, response);

        Config config = configClient.get("abc-123");
        assertEquals("abc-123", config.id());
        assertEquals("test_key", config.key());
        assertNotNull(config.values());
        assertTrue(config.values().isEmpty());
        assertNotNull(config.environments());
        assertTrue(config.environments().isEmpty());
        assertNull(config.createdAt());
        assertNull(config.updatedAt());
    }

    @Test
    void parseConfigWithNullKey_usesEmptyStringFallback() throws Exception {
        String response = """
                {
                  "data": {
                    "id": "null-key-id",
                    "type": "config",
                    "attributes": {
                      "key": null,
                      "name": null,
                      "description": null,
                      "parent": null,
                      "values": {},
                      "environments": {},
                      "created_at": null,
                      "updated_at": null
                    }
                  }
                }
                """;
        stubResponse(200, response);

        Config config = configClient.get("null-key-id");
        assertEquals("", config.key());
        assertEquals("", config.name());
    }

    @Test
    void parseConfigWithMalformedTimestamp_returnsNullInstant() throws Exception {
        String response = """
                {
                  "data": {
                    "id": "bad-ts-id",
                    "type": "config",
                    "attributes": {
                      "key": "test",
                      "name": "Bad Timestamp",
                      "description": null,
                      "parent": null,
                      "values": {},
                      "environments": {},
                      "created_at": "not-a-timestamp",
                      "updated_at": "also-invalid"
                    }
                  }
                }
                """;
        stubResponse(200, response);

        Config config = configClient.get("bad-ts-id");
        assertNull(config.createdAt());
        assertNull(config.updatedAt());
    }

    @Test
    void parseConfigWithMissingFields_usesDefaults() throws Exception {
        // Attributes with only the required "name" field — all optional fields absent.
        // Exercises the false branch of `attrs.has(...)` in parseResource, getStringOrNull,
        // and parseInstant.
        String response = """
                {
                  "data": {
                    "id": "minimal-id",
                    "type": "config",
                    "attributes": {
                      "name": "Minimal"
                    }
                  }
                }
                """;
        stubResponse(200, response);

        Config config = configClient.get("minimal-id");
        assertEquals("minimal-id", config.id());
        assertEquals("", config.key());
        assertEquals("Minimal", config.name());
        assertNull(config.description());
        assertNull(config.parent());
        assertTrue(config.values().isEmpty());
        assertTrue(config.environments().isEmpty());
        assertNull(config.createdAt());
        assertNull(config.updatedAt());
    }

    @Test
    void list_missingDataField_returnsEmpty() throws Exception {
        // Response with no "data" field — getAsJsonArray returns null.
        stubResponse(200, "{}");

        List<Config> configs = configClient.list();
        assertTrue(configs.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int statusCode, String body) throws Exception {
        HttpResponse<String> mockResponse = Mockito.mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(statusCode);
        when(mockResponse.body()).thenReturn(body);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);
    }
}
