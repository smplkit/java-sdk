package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditActionsClient#list(ListActionsInput)}.
 */
public final class ListActionsPage {
    /** The page's items, in server-returned order. */
    public final List<AuditAction> actions;
    /** Pagination metadata describing the page that served the response. */
    public final PageInfo pagination;

    public ListActionsPage(List<AuditAction> actions, PageInfo pagination) {
        this.actions = actions;
        this.pagination = pagination;
    }
}
