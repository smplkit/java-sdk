package com.smplkit.audit;

import java.util.List;

/**
 * One page of {@link AuditEvent}s plus the cursor for the next page (or
 * {@code null} if this is the last page).
 */
public final class ListEventsPage {
    public final List<AuditEvent> events;
    public final String nextCursor;

    public ListEventsPage(List<AuditEvent> events, String nextCursor) {
        this.events = events;
        this.nextCursor = nextCursor;
    }
}
