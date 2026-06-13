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
    private final String environment;

    AuditResourceTypesClient(ResourceTypesApi api) {
        this(api, null);
    }

    AuditResourceTypesClient(ResourceTypesApi api, String environment) {
        this.api = api;
        this.environment = environment;
    }

    /**
     * List the distinct resource-type slugs seen in the account.
     *
     * <p>{@link ListResourceTypesInput#environments} scopes the listing to a
     * set of environments, sent comma-separated as {@code filter[environment]}.
     * Omit it (the default) to scope the listing to the client's configured
     * environment; with no configured environment the filter is left off
     * entirely.</p>
     *
     * @param input optional environment scope and pagination; an empty
     *     instance lists every distinct resource type
     * @return a {@link ListResourceTypesPage} of the matching resource-type slugs
     * @throws ApiException if the request fails
     */
    public ListResourceTypesPage list(ListResourceTypesInput input) throws ApiException {
        ResourceTypeListResponse resp = api.listResourceTypes(
                resolveEnvironmentFilter(input.environments, environment), null,
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

    /**
     * Resolve the {@code filter[environment]} value for an audit read surface.
     *
     * <p>An explicit {@code environments} list always wins and is comma-joined.
     * Otherwise the client's configured {@code defaultEnvironment} scopes the
     * read — the body-driven replacement for the dead {@code X-Smplkit-Environment}
     * header, which previously scoped every read to the client's environment
     * (ADR-055). With no explicit list and no configured environment this
     * returns {@code null}, so the filter is omitted and the credential's own
     * scoping applies server-side.
     */
    static String resolveEnvironmentFilter(List<String> environments, String defaultEnvironment) {
        String explicit = joinEnvironments(environments);
        return explicit != null ? explicit : defaultEnvironment;
    }
}
