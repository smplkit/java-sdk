package com.smplkit.audit;

import com.smplkit.SmplClient;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.api.ForwardersApi;

import java.time.Duration;
import java.util.Map;

/**
 * Audit management surface — accessed via
 * {@code SmplManagementClient.audit}.
 *
 * <p>Owns SIEM forwarder CRUD. Runtime audit operations (event
 * record/list/get, resource-types, event-types) live on
 * {@code SmplClient.audit()}.</p>
 */
public final class AuditManagementClient {

    /** Forwarder CRUD ({@code mgmt.audit.forwarders}). */
    public final AuditForwarders forwarders;

    public AuditManagementClient(String apiKey, Map<String, String> extraHeaders,
                                 Duration timeout, String baseUrl) {
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(baseUrl);
        apiClient.setRequestInterceptor(SmplClient.compositeInterceptor(apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);
        this.forwarders = new AuditForwarders(new ForwardersApi(apiClient));
    }
}
