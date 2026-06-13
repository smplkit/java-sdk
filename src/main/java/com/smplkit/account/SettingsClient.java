package com.smplkit.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smplkit.internal.ApiExceptionHandler;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.AccountApi;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Sync account-settings get/save ({@code client.account.settings}).
 *
 * <p>The endpoint isn't JSON:API — body is a raw JSON object — so we
 * use HTTP directly rather than going through a generated client.</p>
 */
public final class SettingsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AccountApi api;
    private final String baseUri;
    private final String apiKey;
    private final Map<String, String> extraHeaders;
    private final java.net.http.HttpClient httpClient;

    SettingsClient(String appBaseUrl, String apiKey, Map<String, String> extraHeaders) {
        this.baseUri = appBaseUrl != null ? stripTrailingSlash(appBaseUrl) : appBaseUrl;
        this.apiKey = apiKey;
        this.extraHeaders = extraHeaders != null ? new HashMap<>(extraHeaders) : new HashMap<>();
        this.httpClient = HttpClients.builder().build();

        ApiClient appApiClient = new ApiClient();
        appApiClient.setHttpClientBuilder(HttpClients.builder());
        appApiClient.updateBaseUri(this.baseUri);
        appApiClient.setRequestInterceptor(HttpClients.compositeInterceptor(apiKey, this.extraHeaders));
        appApiClient.setReadTimeout(Duration.ofSeconds(30));
        this.api = new AccountApi(appApiClient);
    }

    /**
     * Fetch the authenticated account's current settings.
     *
     * @return an {@link AccountSettings} active record; mutate its fields and
     *     call {@link AccountSettings#save()} to persist the changes
     */
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
        // Build a raw PUT with the settings body, opening a short-lived HTTP call.
        try {
            String json = MAPPER.writeValueAsString(data);
            String url = baseUri + "/api/v1/accounts/current/settings";

            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url));
            HttpClients.compositeInterceptor(apiKey, extraHeaders).accept(builder);
            java.net.http.HttpRequest request = builder
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
        } catch (com.smplkit.errors.SmplError e) {
            throw e;
        } catch (Exception e) {
            throw new com.smplkit.errors.ConnectionError("Failed to save account settings: " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private static com.smplkit.errors.SmplError mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
