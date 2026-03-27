package com.smplkit.internal;

import com.smplkit.errors.SmplConflictException;
import com.smplkit.errors.SmplConnectionException;
import com.smplkit.errors.SmplException;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.errors.SmplTimeoutException;
import com.smplkit.errors.SmplValidationException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;

/**
 * HTTP transport layer using {@link java.net.http.HttpClient}.
 *
 * <p>Builds requests with authentication, content-type, and user-agent headers.
 * Maps HTTP status codes and network exceptions to typed SDK exceptions.</p>
 */
public final class Transport {

    private static final String USER_AGENT = "smplkit-java-sdk/0.0.0";
    private static final String CONTENT_TYPE = "application/vnd.api+json";

    private final HttpClient httpClient;
    private final Auth auth;
    private final String baseUrl;
    private final Duration timeout;

    /**
     * Creates a new Transport.
     *
     * @param httpClient the HTTP client to use
     * @param auth       authentication helper
     * @param baseUrl    API base URL (no trailing slash)
     * @param timeout    request timeout
     */
    public Transport(HttpClient httpClient, Auth auth, String baseUrl, Duration timeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.auth = Objects.requireNonNull(auth, "auth must not be null");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null")
                .replaceAll("/+$", "");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    /**
     * Sends a GET request to the given path.
     *
     * @param path the API path (e.g., "/api/v1/configs")
     * @return the response
     * @throws SmplException on error
     */
    public HttpResponse<String> get(String path) {
        var request = newRequestBuilder(path)
                .GET()
                .build();
        return execute(request);
    }

    /**
     * Sends a GET request to the given path with a query string.
     *
     * @param path  the API path
     * @param query the query string (without leading '?')
     * @return the response
     * @throws SmplException on error
     */
    public HttpResponse<String> get(String path, String query) {
        URI uri = URI.create(baseUrl + path + "?" + query);
        var request = HttpRequest.newBuilder(uri)
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", CONTENT_TYPE)
                .header("User-Agent", USER_AGENT)
                .timeout(timeout)
                .GET()
                .build();
        return execute(request);
    }

    /**
     * Sends a POST request with a JSON body.
     *
     * @param path the API path
     * @param body JSON request body
     * @return the response
     * @throws SmplException on error
     */
    public HttpResponse<String> post(String path, String body) {
        var request = newRequestBuilder(path)
                .header("Content-Type", CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return execute(request);
    }

    /**
     * Sends a PUT request with a JSON body.
     *
     * @param path the API path
     * @param body JSON request body
     * @return the response
     * @throws SmplException on error
     */
    public HttpResponse<String> put(String path, String body) {
        var request = newRequestBuilder(path)
                .header("Content-Type", CONTENT_TYPE)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return execute(request);
    }

    /**
     * Sends a DELETE request.
     *
     * @param path the API path
     * @return the response
     * @throws SmplException on error
     */
    public HttpResponse<String> delete(String path) {
        var request = newRequestBuilder(path)
                .DELETE()
                .build();
        return execute(request);
    }

    private HttpRequest.Builder newRequestBuilder(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", CONTENT_TYPE)
                .header("User-Agent", USER_AGENT)
                .timeout(timeout);
    }

    private HttpResponse<String> execute(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkStatus(response);
            return response;
        } catch (SmplException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new SmplTimeoutException("Request timed out: " + request.uri(), e);
        } catch (ConnectException e) {
            throw new SmplConnectionException("Connection failed: " + request.uri(), e);
        } catch (IOException e) {
            throw new SmplConnectionException("Request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmplConnectionException("Request interrupted: " + request.uri(), e);
        }
    }

    /**
     * Maps HTTP error status codes to typed SDK exceptions.
     *
     * @param response the HTTP response to check
     * @throws SmplNotFoundException    on 404
     * @throws SmplConflictException    on 409
     * @throws SmplValidationException  on 422
     * @throws SmplException            on other 4xx/5xx
     */
    static void checkStatus(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String body = response.body();
        switch (status) {
            case 404 -> throw new SmplNotFoundException("Resource not found", body);
            case 409 -> throw new SmplConflictException("Conflict", body);
            case 422 -> throw new SmplValidationException("Validation failed", body);
            default -> {
                if (status >= 400) {
                    throw new SmplException("HTTP " + status, status, body);
                }
            }
        }
    }

    /**
     * Returns the underlying {@link HttpClient}.
     *
     * @return the HTTP client
     */
    public HttpClient httpClient() {
        return httpClient;
    }
}
