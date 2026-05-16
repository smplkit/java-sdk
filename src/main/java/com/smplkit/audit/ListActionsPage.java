package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditActionsClient#list(ListActionsInput)}.
 *
 * <p>{@code actions} is the page's items; {@code pagination} describes
 * the page that served the response.</p>
 */
public final class ListActionsPage {
    public final List<AuditAction> actions;
    public final PageInfo pagination;

    public ListActionsPage(List<AuditAction> actions, PageInfo pagination) {
        this.actions = actions;
        this.pagination = pagination;
    }
}
