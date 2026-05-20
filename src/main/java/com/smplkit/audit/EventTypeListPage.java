package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditEventTypesClient#list(ListEventTypesInput)}.
 */
public final class EventTypeListPage {
    /** The page's items, in server-returned order. */
    public final List<AuditEventType> eventTypes;
    /** Pagination metadata describing the page that served the response. */
    public final PageInfo pagination;

    public EventTypeListPage(List<AuditEventType> eventTypes, PageInfo pagination) {
        this.eventTypes = eventTypes;
        this.pagination = pagination;
    }
}
