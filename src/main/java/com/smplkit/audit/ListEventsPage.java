package com.smplkit.audit;

import java.util.List;

/**
 * One page of {@link AuditEvent}s plus the cursor for the next page.
 */
public final class ListEventsPage {
    /** The events on this page, in server-returned order. */
    public final List<AuditEvent> events;
    /** Cursor for the next page, or {@code null} if this is the last page. */
    public final String nextCursor;

    public ListEventsPage(List<AuditEvent> events, String nextCursor) {
        this.events = events;
        this.nextCursor = nextCursor;
    }
}
