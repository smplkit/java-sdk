package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.DefaultApi;
import com.smplkit.internal.generated.audit.model.Event;
import com.smplkit.internal.generated.audit.model.EventListResponse;
import com.smplkit.internal.generated.audit.model.EventResource;
import com.smplkit.internal.generated.audit.model.EventResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Audit events surface — accessed via {@link AuditClient#events()}.
 *
 * <p>{@link #create(CreateEventInput)} is fire-and-forget per ADR-047
 * §2.6 — the call enqueues the event onto an in-memory bounded buffer
 * and returns immediately. Reads ({@link #list(ListEventsInput)}, {@link
 * #get(UUID)}) are synchronous.</p>
 */
public final class AuditEvents {

    private final DefaultApi api;
    private final AuditEventBuffer buffer;

    AuditEvents(DefaultApi api) {
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
     * @throws IllegalArgumentException if action / resourceType / resourceId
     *     are missing
     */
    public void create(CreateEventInput input) {
        if (input.action == null || input.action.isEmpty()
                || input.resourceType == null || input.resourceType.isEmpty()
                || input.resourceId == null || input.resourceId.isEmpty()) {
            throw new IllegalArgumentException(
                    "AuditEvents.create requires action, resourceType, and resourceId");
        }
        Event attrs = new Event()
                .action(input.action)
                .resourceType(input.resourceType)
                .resourceId(input.resourceId);
        if (input.occurredAt != null) {
            attrs.occurredAt(input.occurredAt);
        }
        if (input.snapshot != null) {
            attrs.snapshot(input.snapshot);
        }
        if (input.data != null) {
            attrs.data(input.data);
        }
        EventResource resource = new EventResource()
                .id("") // server assigns
                .type("event")
                .attributes(attrs);
        EventResponse body = new EventResponse().data(resource);
        buffer.enqueue(body, input.idempotencyKey);
    }

    /** Single-event retrieval. Returns 404 wrapped as {@link ApiException}. */
    public AuditEvent get(UUID eventId) throws ApiException {
        EventResponse resp = api.getEvent(eventId);
        return fromResource(resp.getData());
    }

    /** List events with filters and cursor pagination. */
    public ListEventsPage list(ListEventsInput input) throws ApiException {
        UUID actorId = null;
        if (input.actorId != null && !input.actorId.isEmpty()) {
            actorId = UUID.fromString(input.actorId);
        }
        EventListResponse resp = api.listEvents(
                input.occurredAtRange,
                input.actorType,
                actorId,
                input.action,
                input.resourceType,
                input.resourceId,
                input.pageSize,
                input.pageAfter
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
                a.getAction(),
                a.getResourceType(),
                a.getResourceId(),
                a.getOccurredAt(),
                a.getCreatedAt(),
                a.getActorType(),
                a.getActorId(),
                a.getActorLabel(),
                a.getSnapshot(),
                a.getData(),
                a.getIdempotencyKey()
        );
    }
}
