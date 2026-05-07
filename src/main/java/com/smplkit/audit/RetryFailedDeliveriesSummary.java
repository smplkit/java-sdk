package com.smplkit.audit;

/**
 * Summary returned by
 * {@link AuditForwarders#retryFailedDeliveries(java.util.UUID)}.
 */
public final class RetryFailedDeliveriesSummary {
    public final int attempted;
    public final int succeeded;
    public final int failed;

    public RetryFailedDeliveriesSummary(int attempted, int succeeded, int failed) {
        this.attempted = attempted;
        this.succeeded = succeeded;
        this.failed = failed;
    }
}
