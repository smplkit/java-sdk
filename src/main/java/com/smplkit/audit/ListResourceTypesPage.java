package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditResourceTypesClient#list(ListResourceTypesInput)}.
 */
public final class ListResourceTypesPage {
    /** The page's items, in server-returned order. */
    public final List<AuditResourceType> resourceTypes;
    /** Pagination metadata describing the page that served the response. */
    public final PageInfo pagination;

    public ListResourceTypesPage(List<AuditResourceType> resourceTypes, PageInfo pagination) {
        this.resourceTypes = resourceTypes;
        this.pagination = pagination;
    }
}
