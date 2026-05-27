package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.EventsApi;
import com.smplkit.internal.generated.audit.model.Event;
import com.smplkit.internal.generated.audit.model.EventListResponse;
import com.smplkit.internal.generated.audit.model.EventRequest;
import com.smplkit.internal.generated.audit.model.EventResource;
import com.smplkit.internal.generated.audit.model.EventResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Audit events surface — accessed via {@link AuditClient#events()}.
 *
 * <p>{@link #record(CreateEventInput)} is fire-and-forget per ADR-047
 * §2.6 — the call enqueues the event onto an in-memory bounded buffer
 * and returns immediately. Reads ({@link #list(ListEventsInput)}, {@link
 * #get(UUID)}) are synchronous.</p>
 */
public final class AuditEvents {

    private final EventsApi api;
    private final AuditEventBuffer buffer;

    AuditEvents(EventsApi api) {
        this.api = api;
        this.buffer = new AuditEventBuffer(api);
    }

    /**
     * Enqueue an audit event for asynchronous delivery. Returns immediately.
     *
     * <p>Customer attempts to record events with {@code resourceType}
     * starting with {@code smpl.} are rejected by the server with a 403
     * (the buffer logs the permanent failure and drops the item).</p>
     *
     * @throws IllegalArgumentException if eventType / resourceType / resourceId
     *     are missing
     */
    public void record(CreateEventInput input) {
        if (input.eventType == null || input.eventType.isEmpty()
                || input.resourceType == null || input.resourceType.isEmpty()
                || input.resourceId == null || input.resourceId.isEmpty()) {
            throw new IllegalArgumentException(
                    "AuditEvents.record requires eventType, resourceType, and resourceId");
        }
        Event attrs = new Event()
                .eventType(input.eventType)
                .resourceType(input.resourceType)
                .resourceId(input.resourceId);
        if (input.occurredAt != null) {
            attrs.occurredAt(input.occurredAt);
        }
        if (input.actorType != null) {
            attrs.actorType(input.actorType);
        }
        if (input.actorId != null) {
            attrs.actorId(input.actorId);
        }
        if (input.actorLabel != null) {
            attrs.actorLabel(input.actorLabel);
        }
        if (input.data != null) {
            attrs.data(input.data);
        }
        if (input.doNotForward) {
            attrs.doNotForward(true);
        }
        EventResource resource = new EventResource()
                .id("") // server assigns
                .type("event")
                .attributes(attrs);
        EventRequest body = new EventRequest().data(resource);
        buffer.enqueue(body, input.idempotencyKey);
    }

    /** Single-event retrieval. Returns 404 wrapped as {@link ApiException}. */
    public AuditEvent get(UUID eventId) throws ApiException {
        EventResponse resp = api.getEvent(eventId);
        return fromResource(resp.getData());
    }

    /** List events with filters and cursor pagination. */
    public ListEventsPage list(ListEventsInput input) throws ApiException {
        EventListResponse resp = api.listEvents(
                input.occurredAtRange,
                input.actorType,
                input.actorId,
                input.eventType,
                input.resourceType,
                input.resourceId,
                input.search,
                input.doNotForward,
                input.pageSize,
                input.pageAfter,
                null, // format — null = paginated JSON:API response (no streaming export)
                null  // sort
        );

        List<AuditEvent> events = new ArrayList<>();
        if (resp.getData() != null) {
            for (EventResource r : resp.getData()) {
                events.add(fromResource(r));
            }
        }
        String nextCursor = null;
        if (resp.getLinks() != null && resp.getLinks().getNext() != null) {
            String next = resp.getLinks().getNext();
            int idx = next.indexOf("page[after]=");
            if (idx >= 0) {
                nextCursor = next.substring(idx + "page[after]=".length());
                int amp = nextCursor.indexOf('&');
                if (amp >= 0) {
                    nextCursor = nextCursor.substring(0, amp);
                }
            }
        }
        return new ListEventsPage(events, nextCursor);
    }

    /** Block until the in-memory buffer is drained or timeout elapses. */
    public void flush(long timeoutMs) {
        buffer.flush(timeoutMs);
    }

    /** Drains best-effort and stops the background worker. Called from {@code SmplClient.close}. */
    public void close() {
        buffer.close();
    }

    private static AuditEvent fromResource(EventResource r) {
        Event a = r.getAttributes();
        return new AuditEvent(
                r.getId() != null && !r.getId().isEmpty() ? UUID.fromString(r.getId()) : null,
                a.getEventType(),
                a.getResourceType(),
                a.getResourceId(),
                a.getOccurredAt(),
                a.getCreatedAt(),
                a.getActorType(),
                a.getActorId(),
                a.getActorLabel(),
                a.getData(),
                a.getIdempotencyKey(),
                a.getDoNotForward() != null ? a.getDoNotForward() : false
        );
    }
}
