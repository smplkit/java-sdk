package com.smplkit.audit;

import com.smplkit.SmplClient;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.api.CategoriesApi;
import com.smplkit.internal.generated.audit.api.EventTypesApi;
import com.smplkit.internal.generated.audit.api.EventsApi;
import com.smplkit.internal.generated.audit.api.ResourceTypesApi;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Audit-product runtime entry point — accessed via {@link SmplClient#audit()}.
 *
 * <p>Sub-clients: {@link #events()} for event recording / listing /
 * retrieval; {@link #resourceTypes()} for the distinct resource-type
 * slugs seen in the account; {@link #eventTypes()} for the distinct event
 * type slugs (powering cascading filter dropdowns).</p>
 *
 * <p>Management-plane operations (SIEM forwarder CRUD) live on
 * {@code SmplManagementClient} under {@code mgmt.audit.*}.</p>
 */
public final class AuditClient implements AutoCloseable {

    private final AuditEvents events;
    private final AuditResourceTypesClient resourceTypes;
    private final AuditEventTypesClient eventTypes;
    private final AuditCategoriesClient categories;

    public AuditClient(HttpClient httpClient, String apiKey, Map<String, String> extraHeaders,
                       Duration timeout, String baseUrl) {
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(baseUrl);
        apiClient.setRequestInterceptor(SmplClient.compositeInterceptor(apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);
        this.events = new AuditEvents(new EventsApi(apiClient));
        this.resourceTypes = new AuditResourceTypesClient(new ResourceTypesApi(apiClient));
        this.eventTypes = new AuditEventTypesClient(new EventTypesApi(apiClient));
        this.categories = new AuditCategoriesClient(new CategoriesApi(apiClient));
    }

    /** Returns the events sub-client (record, list, get). */
    public AuditEvents events() {
        return events;
    }

    /** Returns the resource-types sub-client (list). */
    public AuditResourceTypesClient resourceTypes() {
        return resourceTypes;
    }

    /** Returns the event-types sub-client (list). */
    public AuditEventTypesClient eventTypes() {
        return eventTypes;
    }

    /** Returns the categories sub-client (list). */
    public AuditCategoriesClient categories() {
        return categories;
    }

    @Override
    public void close() {
        events.close();
    }
}
