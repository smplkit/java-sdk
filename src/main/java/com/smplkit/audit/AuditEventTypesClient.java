package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.EventTypesApi;
import com.smplkit.internal.generated.audit.model.EventTypeListResponse;
import com.smplkit.internal.generated.audit.model.EventTypeResource;

import java.util.ArrayList;
import java.util.List;

/**
 * Distinct event type slugs seen for the account — accessed via
 * {@link AuditClient#eventTypes()}.
 *
 * <p>Without {@link ListEventTypesInput#filterResourceType}, returns one
 * row per distinct event type. With the filter, returns the event types seen
 * with that specific resource_type, powering cascading-filter behavior.
 * Sorted alphabetically; offset pagination via
 * {@link ListEventTypesInput#pageNumber} / {@link
 * ListEventTypesInput#pageSize}.</p>
 */
public final class AuditEventTypesClient {

    private final EventTypesApi api;
    private final String environment;

    AuditEventTypesClient(EventTypesApi api) {
        this(api, null);
    }

    AuditEventTypesClient(EventTypesApi api, String environment) {
        this.api = api;
        this.environment = environment;
    }

    /**
     * List the distinct event-type slugs seen in the account.
     *
     * <p>{@link ListEventTypesInput#environments} scopes the listing to a set
     * of environments, sent comma-separated as {@code filter[environment]}.
     * Omit it (the default) to scope the listing to the client's configured
     * environment; with no configured environment the filter is left off
     * entirely.</p>
     *
     * @param input optional resource-type filter, environment scope, and
     *     pagination; an empty instance lists every distinct event type
     * @return an {@link EventTypeListPage} of the matching event-type slugs
     * @throws ApiException if the request fails
     */
    public EventTypeListPage list(ListEventTypesInput input) throws ApiException {
        EventTypeListResponse resp = api.listEventTypes(
                AuditResourceTypesClient.resolveEnvironmentFilter(input.environments, environment),
                input.filterResourceType, null, input.pageNumber, input.pageSize, input.metaTotal);
        List<AuditEventType> rows = new ArrayList<>();
        if (resp.getData() != null) {
            for (EventTypeResource r : resp.getData()) {
                rows.add(fromResource(r));
            }
        }
        return new EventTypeListPage(rows, AuditResourceTypesClient.extractPagination(
                resp.getMeta() == null ? null : resp.getMeta().getPagination()));
    }

    private static AuditEventType fromResource(EventTypeResource r) {
        String id = r.getId() != null ? r.getId() : "";
        String eventType = (r.getAttributes() != null && r.getAttributes().getEventType() != null)
                ? r.getAttributes().getEventType() : id;
        java.time.OffsetDateTime createdAt = r.getAttributes() != null
                ? r.getAttributes().getCreatedAt() : null;
        return new AuditEventType(id, eventType, createdAt);
    }
}
