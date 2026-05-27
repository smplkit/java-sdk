package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditCategoriesClient#list(ListCategoriesInput)}.
 */
public final class ListCategoriesPage {
    /** The page's items, in server-returned order. */
    public final List<AuditCategory> categories;
    /** Pagination metadata describing the page that served the response. */
    public final PageInfo pagination;

    public ListCategoriesPage(List<AuditCategory> categories, PageInfo pagination) {
        this.categories = categories;
        this.pagination = pagination;
    }
}
