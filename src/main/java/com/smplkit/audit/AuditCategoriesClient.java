package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.CategoriesApi;
import com.smplkit.internal.generated.audit.model.CategoryListResponse;
import com.smplkit.internal.generated.audit.model.CategoryResource;

import java.util.ArrayList;
import java.util.List;

/**
 * Distinct category values seen for the account — accessed via
 * {@link AuditClient#categories()}.
 *
 * <p>Response time is independent of how many years of events the account
 * has accumulated. Sorted alphabetically; offset pagination via
 * {@link ListCategoriesInput#pageNumber} /
 * {@link ListCategoriesInput#pageSize}.</p>
 */
public final class AuditCategoriesClient {

    private final CategoriesApi api;
    private final String environment;

    AuditCategoriesClient(CategoriesApi api) {
        this(api, null);
    }

    AuditCategoriesClient(CategoriesApi api, String environment) {
        this.api = api;
        this.environment = environment;
    }

    /**
     * List the distinct category values seen in the account.
     *
     * <p>{@link ListCategoriesInput#environments} scopes the listing to a set
     * of environments, sent comma-separated as {@code filter[environment]}.
     * Omit it (the default) to scope the listing to the client's configured
     * environment; with no configured environment the filter is left off
     * entirely.</p>
     *
     * @param input optional environment scope and pagination; an empty
     *     instance lists every distinct category
     * @return a {@link ListCategoriesPage} of the matching category values
     * @throws ApiException if the request fails
     */
    public ListCategoriesPage list(ListCategoriesInput input) throws ApiException {
        CategoryListResponse resp = api.listCategories(
                AuditResourceTypesClient.resolveEnvironmentFilter(input.environments, environment), null,
                input.pageNumber, input.pageSize, input.metaTotal);
        List<AuditCategory> rows = new ArrayList<>();
        if (resp.getData() != null) {
            for (CategoryResource r : resp.getData()) {
                rows.add(fromResource(r));
            }
        }
        return new ListCategoriesPage(rows, AuditResourceTypesClient.extractPagination(
                resp.getMeta() == null ? null : resp.getMeta().getPagination()));
    }

    private static AuditCategory fromResource(CategoryResource r) {
        String id = r.getId() != null ? r.getId() : "";
        String cat = (r.getAttributes() != null && r.getAttributes().getCategory() != null)
                ? r.getAttributes().getCategory() : id;
        java.time.OffsetDateTime createdAt = r.getAttributes() != null
                ? r.getAttributes().getCreatedAt() : null;
        return new AuditCategory(id, cat, createdAt);
    }
}
