package com.smplkit.audit;

import java.util.List;

/** A single page of forwarders. */
public final class ListForwardersPage {
    public final List<Forwarder> forwarders;
    /** Opaque cursor for the next page, or null if this is the last page. */
    public final String nextCursor;

    public ListForwardersPage(List<Forwarder> forwarders, String nextCursor) {
        this.forwarders = forwarders;
        this.nextCursor = nextCursor;
    }
}
