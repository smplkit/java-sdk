package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditActionsClient#list(ListActionsInput)}.
 *
 * <p>{@code actions} is the page's items; {@code nextCursor} is the
 * opaque token for the next page, or {@code null} when this is the
 * last page.</p>
 */
public final class ListActionsPage {
    public final List<AuditAction> actions;
    public final String nextCursor;

    public ListActionsPage(List<AuditAction> actions, String nextCursor) {
        this.actions = actions;
        this.nextCursor = nextCursor;
    }
}
