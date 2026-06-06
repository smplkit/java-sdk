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
import java.util.HashMap;
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
        this(httpClient, apiKey, extraHeaders, timeout, baseUrl, null);
    }

    /**
     * Runtime audit ops are environment-scoped (ADR-055): record / list /
     * get / discovery all resolve their environment from the
     * {@code X-Smplkit-Environment} request header. We stamp it once at the
     * client level from the SDK's configured runtime {@code environment} so
     * every generated call carries it. A caller-supplied {@code extraHeaders}
     * entry of the same name wins (explicit override).
     *
     * @param environment the SDK's configured runtime environment, or
     *     {@code null} to omit the header (e.g. a single-environment
     *     credential resolves the environment server-side)
     */
    public AuditClient(HttpClient httpClient, String apiKey, Map<String, String> extraHeaders,
                       Duration timeout, String baseUrl, String environment) {
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(baseUrl);
        Map<String, String> headers = new HashMap<>();
        if (environment != null) {
            headers.put("X-Smplkit-Environment", environment);
        }
        if (extraHeaders != null) {
            headers.putAll(extraHeaders); // explicit caller override wins
        }
        apiClient.setRequestInterceptor(SmplClient.compositeInterceptor(apiKey, headers));
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
