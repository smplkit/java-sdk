package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ResourceTypesApi;
import com.smplkit.internal.generated.audit.model.PaginationMeta;
import com.smplkit.internal.generated.audit.model.ResourceTypeListResponse;
import com.smplkit.internal.generated.audit.model.ResourceTypeResource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Distinct resource-type slugs seen for the account — accessed via
 * {@link AuditClient#resourceTypes()}.
 *
 * <p>Response time is independent of how many years of events the account
 * has accumulated. Sorted alphabetically; offset pagination via
 * {@link ListResourceTypesInput#pageNumber} / {@link
 * ListResourceTypesInput#pageSize}.</p>
 */
public final class AuditResourceTypesClient {

    private final ResourceTypesApi api;

    AuditResourceTypesClient(ResourceTypesApi api) {
        this.api = api;
    }

    /**
     * List the distinct resource-type slugs seen in the account.
     *
     * @param input optional environment scope and pagination; an empty
     *     instance lists every distinct resource type
     * @return a {@link ListResourceTypesPage} of the matching resource-type slugs
     * @throws ApiException if the request fails
     */
    public ListResourceTypesPage list(ListResourceTypesInput input) throws ApiException {
        ResourceTypeListResponse resp = api.listResourceTypes(
                joinEnvironments(input.environments), null,
                input.pageNumber, input.pageSize, input.metaTotal);
        List<AuditResourceType> rows = new ArrayList<>();
        if (resp.getData() != null) {
            for (ResourceTypeResource r : resp.getData()) {
                rows.add(fromResource(r));
            }
        }
        return new ListResourceTypesPage(rows, extractPagination(resp.getMeta() == null
                ? null : resp.getMeta().getPagination()));
    }

    private static AuditResourceType fromResource(ResourceTypeResource r) {
        String id = r.getId() != null ? r.getId() : "";
        String rt = (r.getAttributes() != null && r.getAttributes().getResourceType() != null)
                ? r.getAttributes().getResourceType() : id;
        java.time.OffsetDateTime createdAt = r.getAttributes() != null
                ? r.getAttributes().getCreatedAt() : null;
        return new AuditResourceType(id, rt, createdAt);
    }

    static PageInfo extractPagination(PaginationMeta meta) {
        if (meta == null) return null;
        return new PageInfo(meta.getPage(), meta.getSize(), meta.getTotal(), meta.getTotalPages());
    }

    /**
     * Join environment keys into the comma-separated string the generated
     * {@code filter[environment]} parameter expects. Returns {@code null} when
     * the list is {@code null} or contains no non-blank entries, leaving the
     * filter unset (prior behavior). Blank entries are dropped.
     */
    static String joinEnvironments(List<String> environments) {
        if (environments == null || environments.isEmpty()) {
            return null;
        }
        String joined = environments.stream()
                .filter(e -> e != null && !e.isBlank())
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }
}
