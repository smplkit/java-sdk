package com.smplkit.audit;

import java.util.List;

/** A single page of forwarders. */
public final class ListForwardersPage {
    public final List<Forwarder> forwarders;
    public final PageInfo pagination;

    public ListForwardersPage(List<Forwarder> forwarders, PageInfo pagination) {
        this.forwarders = forwarders;
        this.pagination = pagination;
    }
}
