package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ResourceTypesApi;
import com.smplkit.internal.generated.audit.model.ResourceTypeListResponse;
import com.smplkit.internal.generated.audit.model.ResourceTypeResource;

import java.util.ArrayList;
import java.util.List;

/**
 * Distinct resource-type slugs seen for the account — accessed via
 * {@link AuditClient#resourceTypes()}.
 *
 * <p>Backed by a maintain-by-write side table (ADR-047 §2.5), so the
 * response time is independent of how many years of events the account
 * has accumulated. Sorted alphabetically; cursor pagination via
 * {@link ListResourceTypesInput#pageAfter}.</p>
 */
public final class AuditResourceTypesClient {

    private final ResourceTypesApi api;

    AuditResourceTypesClient(ResourceTypesApi api) {
        this.api = api;
    }

    /** List the distinct resource_type slugs seen in the account. */
    public ListResourceTypesPage list(ListResourceTypesInput input) throws ApiException {
        ResourceTypeListResponse resp = api.listResourceTypes(input.pageSize, input.pageAfter);
        List<AuditResourceType> rows = new ArrayList<>();
        if (resp.getData() != null) {
            for (ResourceTypeResource r : resp.getData()) {
                rows.add(fromResource(r));
            }
        }
        String nextCursor = null;
        if (resp.getLinks() != null && resp.getLinks().getNext() != null) {
            nextCursor = extractCursor(resp.getLinks().getNext());
        }
        return new ListResourceTypesPage(rows, nextCursor);
    }

    private static AuditResourceType fromResource(ResourceTypeResource r) {
        String id = r.getId() != null ? r.getId() : "";
        String rt = (r.getAttributes() != null && r.getAttributes().getResourceType() != null)
                ? r.getAttributes().getResourceType() : id;
        java.time.OffsetDateTime createdAt = r.getAttributes() != null
                ? r.getAttributes().getCreatedAt() : null;
        return new AuditResourceType(id, rt, createdAt);
    }

    private static String extractCursor(String link) {
        int i = link.indexOf("page[after]=");
        if (i < 0) return null;
        String token = link.substring(i + "page[after]=".length());
        int amp = token.indexOf('&');
        return amp >= 0 ? token.substring(0, amp) : token;
    }
}
