package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditResourceTypesClient#list(ListResourceTypesInput)}.
 *
 * <p>{@code resourceTypes} is the page's items; {@code pagination}
 * describes the page that served the response.</p>
 */
public final class ListResourceTypesPage {
    public final List<AuditResourceType> resourceTypes;
    public final PageInfo pagination;

    public ListResourceTypesPage(List<AuditResourceType> resourceTypes, PageInfo pagination) {
        this.resourceTypes = resourceTypes;
        this.pagination = pagination;
    }
}
