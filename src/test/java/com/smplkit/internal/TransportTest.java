package com.smplkit.internal;

import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplValidationException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Transport#checkStatus(HttpResponse)}.
 */
class TransportTest {

    @Test
    void checkStatus_2xxDoesNotThrow() {
        for (int code : new int[]{200, 201, 204}) {
            HttpResponse<String> response = mockResponse(code, "");
            assertDoesNotThrow(() -> Transport.checkStatus(response));
        }
    }

    @Test
    void checkStatus_404ThrowsNotFoundException() {
        HttpResponse<String> response = mockResponse(404, "not found body");
        SmplNotFoundException ex = assertThrows(SmplNotFoundException.class,
                () -> Transport.checkStatus(response));
        assertEquals(404, ex.statusCode());
        assertEquals("not found body", ex.responseBody());
    }

    @Test
    void checkStatus_409ThrowsConflictException() {
        HttpResponse<String> response = mockResponse(409, "conflict body");
        SmplConflictException ex = assertThrows(SmplConflictException.class,
                () -> Transport.checkStatus(response));
        assertEquals(409, ex.statusCode());
    }

    @Test
    void checkStatus_422ThrowsValidationException() {
        HttpResponse<String> response = mockResponse(422, "validation body");
        SmplValidationException ex = assertThrows(SmplValidationException.class,
                () -> Transport.checkStatus(response));
        assertEquals(422, ex.statusCode());
    }

    @Test
    void checkStatus_500ThrowsSmplException() {
        HttpResponse<String> response = mockResponse(500, "server error");
        SmplException ex = assertThrows(SmplException.class,
                () -> Transport.checkStatus(response));
        assertEquals(500, ex.statusCode());
        assertEquals("server error", ex.responseBody());
    }

    @Test
    void checkStatus_400ThrowsSmplException() {
        HttpResponse<String> response = mockResponse(400, "bad request");
        SmplException ex = assertThrows(SmplException.class,
                () -> Transport.checkStatus(response));
        assertEquals(400, ex.statusCode());
    }

    @Test
    void checkStatus_3xxDoesNotThrow() {
        for (int code : new int[]{301, 302, 304}) {
            HttpResponse<String> response = mockResponse(code, "");
            assertDoesNotThrow(() -> Transport.checkStatus(response));
        }
    }

    @Test
    void checkStatus_1xxDoesNotThrow() {
        // Status < 200 exercises the false branch of `status >= 200`.
        HttpResponse<String> response = mockResponse(100, "");
        assertDoesNotThrow(() -> Transport.checkStatus(response));
    }

    @Test
    void authHeaderIsSet() {
        Auth auth = new Auth("test-key");
        assertEquals("Bearer test-key", auth.authorizationHeader());
        assertEquals("test-key", auth.apiKey());
    }

    @Test
    void authRejectsNull() {
        assertThrows(NullPointerException.class, () -> new Auth(null));
    }

    @Test
    void authRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new Auth(""));
        assertThrows(IllegalArgumentException.class, () -> new Auth("   "));
    }

    @Test
    @SuppressWarnings("unchecked")
    void put_sendsRequestAndReturnsResponse() throws Exception {
        HttpResponse<String> mockResponse = mockResponse(200, "{\"data\":{}}");
        java.net.http.HttpClient mockClient = Mockito.mock(java.net.http.HttpClient.class);
        when(mockClient.send(
                Mockito.any(java.net.http.HttpRequest.class),
                Mockito.any(HttpResponse.BodyHandler.class)
        )).thenReturn(mockResponse);

        Auth auth = new Auth("test-key");
        Transport transport = new Transport(mockClient, auth, "https://config.smplkit.com",
                java.time.Duration.ofSeconds(30));

        HttpResponse<String> response = transport.put("/api/v1/configs/some-id", "{\"data\":{}}");
        assertNotNull(response);
        assertEquals(200, response.statusCode());
    }

    @Test
    void httpClient_returnsUnderlyingClient() {
        java.net.http.HttpClient mockClient = Mockito.mock(java.net.http.HttpClient.class);
        Auth auth = new Auth("test-key");
        Transport transport = new Transport(mockClient, auth, "https://config.smplkit.com",
                java.time.Duration.ofSeconds(30));

        assertSame(mockClient, transport.httpClient());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int statusCode, String body) {
        HttpResponse<String> response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }
}
