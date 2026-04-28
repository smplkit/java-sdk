package com.smplkit.management;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.AccountApi;

import java.util.HashMap;
import java.util.Map;

/**
 * Account-settings get/save under {@code client.management.account_settings}.
 *
 * <p>The settings endpoint returns an opaque JSON object, not a JSON:API resource.
 * We use the generated {@link AccountApi} for GET and build a raw PUT for save.</p>
 */
public final class AccountSettingsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AccountApi api;
    private final String baseUri;
    private final String apiKey;
    private final java.net.http.HttpClient httpClient;

    AccountSettingsClient(ApiClient appApiClient, String appBaseUrl, String apiKey) {
        this(appApiClient, appBaseUrl, apiKey, java.net.http.HttpClient.newHttpClient());
    }

    AccountSettingsClient(ApiClient appApiClient, String appBaseUrl, String apiKey,
                          java.net.http.HttpClient httpClient) {
        this.api = new AccountApi(appApiClient);
        this.baseUri = appBaseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    /** Fetch the current account settings. */
    public AccountSettings get() {
        try {
            Map<String, Object> data = api.getAccountSettings();
            return new AccountSettings(this, data != null ? data : new HashMap<>());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    AccountSettings _save(Map<String, Object> data) {
        // The generated putAccountSettings() sends no body (OpenAPI spec issue).
        // Build a raw PUT with the settings body using the ApiClient's HttpClient.
        try {
            String json = MAPPER.writeValueAsString(data);
            String url = baseUri + "/api/v1/accounts/current/settings";

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiExceptionHandler.mapApiException(response.statusCode(), response.body());
            }
            Map<String, Object> saved = MAPPER.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {});
            return new AccountSettings(this, saved != null ? saved : new HashMap<>());
        } catch (com.smplkit.errors.SmplException e) {
            throw e;
        } catch (Exception e) {
            throw new com.smplkit.errors.SmplConnectionException("Failed to save account settings: " + e.getMessage(), e);
        }
    }

    private static com.smplkit.errors.SmplException mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
