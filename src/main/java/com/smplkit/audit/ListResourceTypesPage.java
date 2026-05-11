package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditResourceTypesClient#list(ListResourceTypesInput)}.
 *
 * <p>{@code resourceTypes} is the page's items; {@code nextCursor} is
 * the opaque token for the next page, or {@code null} when this is the
 * last page.</p>
 */
public final class ListResourceTypesPage {
    public final List<AuditResourceType> resourceTypes;
    public final String nextCursor;

    public ListResourceTypesPage(List<AuditResourceType> resourceTypes, String nextCursor) {
        this.resourceTypes = resourceTypes;
        this.nextCursor = nextCursor;
    }
}
