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
 * <p>Backed by a maintain-by-write side table populated whenever an event
 * is recorded with a non-null {@code category}. Sorted alphabetically;
 * offset pagination via {@link ListCategoriesInput#pageNumber} /
 * {@link ListCategoriesInput#pageSize}.</p>
 */
public final class AuditCategoriesClient {

    private final CategoriesApi api;

    AuditCategoriesClient(CategoriesApi api) {
        this.api = api;
    }

    /** List the distinct category values seen in the account. */
    public ListCategoriesPage list(ListCategoriesInput input) throws ApiException {
        CategoryListResponse resp = api.listCategories(
                null, input.pageNumber, input.pageSize, input.metaTotal);
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
