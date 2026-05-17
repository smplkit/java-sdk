package com.smplkit.audit;

import java.util.List;

/**
 * A single page from {@link AuditForwarders#list(ListForwardersInput)}.
 */
public final class ListForwardersPage {
    /** The page's items, in server-returned order. */
    public final List<Forwarder> forwarders;
    /** Pagination metadata describing the page that served the response. */
    public final PageInfo pagination;

    public ListForwardersPage(List<Forwarder> forwarders, PageInfo pagination) {
        this.forwarders = forwarders;
        this.pagination = pagination;
    }
}
