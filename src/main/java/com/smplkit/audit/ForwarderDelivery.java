package com.smplkit.audit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only delivery row.
 *
 * <p>{@code request.headers} have always-redacted values regardless of
 * who configured them. {@code status} is one of {@code "SUCCEEDED"},
 * {@code "FAILED"}, {@code "FILTERED_OUT"}, or
 * {@code "SKIPPED_DO_NOT_FORWARD"}.</p>
 */
public final class ForwarderDelivery {
    public final UUID id;
    public final UUID forwarderId;
    public final UUID eventId;
    public final int attemptNumber;
    public final String status;
    public final Map<String, Object> request; // nullable
    public final Integer responseStatus; // nullable
    public final String responseBody; // nullable
    public final Integer latencyMs; // nullable
    public final String error; // nullable
    public final OffsetDateTime createdAt;

    public ForwarderDelivery(UUID id, UUID forwarderId, UUID eventId, int attemptNumber,
                             String status, Map<String, Object> request,
                             Integer responseStatus, String responseBody,
                             Integer latencyMs, String error, OffsetDateTime createdAt) {
        this.id = id;
        this.forwarderId = forwarderId;
        this.eventId = eventId;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.request = request;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.latencyMs = latencyMs;
        this.error = error;
        this.createdAt = createdAt;
    }
}
