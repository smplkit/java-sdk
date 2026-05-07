package com.smplkit.audit;

import java.util.List;

/** A single page of forwarder deliveries. */
public final class ListDeliveriesPage {
    public final List<ForwarderDelivery> deliveries;
    public final String nextCursor;

    public ListDeliveriesPage(List<ForwarderDelivery> deliveries, String nextCursor) {
        this.deliveries = deliveries;
        this.nextCursor = nextCursor;
    }
}
