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
 * <p>{@link #record(CreateEventInput)} is fire-and-forget — the call
 * enqueues the event onto an in-memory bounded buffer and returns
 * immediately. Reads ({@link #list(ListEventsInput)}, {@link #get(UUID)})
 * are synchronous.</p>
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
     * @param input the event to record; per-field semantics live on
     *     {@link CreateEventInput}
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
        if (input.category != null) {
            attrs.category(input.category);
        }
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

    /**
     * Retrieve a single audit event by id.
     *
     * @param eventId the event's UUID
     * @return the matching {@link AuditEvent}
     * @throws ApiException if no event with that id exists in the caller's
     *     account (surfaced as a not-found error)
     */
    public AuditEvent get(UUID eventId) throws ApiException {
        EventResponse resp = api.getEvent(eventId);
        return fromResource(resp.getData());
    }

    /**
     * List audit events for the authenticated account.
     *
     * <p>Filters apply server-side. {@code actorId} is matched as a literal
     * string against whatever the recording call stored. Pagination uses an
     * opaque cursor ({@link ListEventsInput#pageAfter}); the returned page
     * exposes {@code nextCursor} when more pages are available.</p>
     *
     * <p>{@link ListEventsInput#search} is an optional free-text filter:
     * it returns only events whose {@code resource_id} or {@code description}
     * contains it as a case-insensitive substring. A {@code search} filter
     * must be scoped — combine it with {@code occurredAtRange}, or with both
     * {@code resourceType} and {@code resourceId} — or the request is
     * rejected.</p>
     *
     * @param input filters and pagination cursor; an empty instance lists
     *     every event with default paging
     * @return a {@link ListEventsPage} of the matching events; its
     *     {@code nextCursor} is set when more pages are available
     * @throws ApiException if the request fails
     */
    public ListEventsPage list(ListEventsInput input) throws ApiException {
        EventListResponse resp = api.listEvents(
                AuditResourceTypesClient.joinEnvironments(input.environments),
                input.occurredAtRange,
                input.actorType,
                input.actorId,
                input.eventType,
                input.resourceType,
                input.resourceId,
                null, // severity filter not exposed on the wrapper surface
                input.category,
                input.search,
                null, // do_not_forward is a record()-time flag, not a list filter
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

    /**
     * Block until the in-memory buffer is drained or the timeout elapses.
     *
     * <p>Useful for draining buffered events at process shutdown or after a
     * batch of fire-and-forget records.</p>
     *
     * @param timeoutMs upper bound on the blocking flush, in milliseconds
     */
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
                a.getCategory(),
                a.getOccurredAt(),
                a.getCreatedAt(),
                a.getActorType(),
                a.getActorId(),
                a.getActorLabel(),
                a.getData(),
                a.getIdempotencyKey(),
                a.getDoNotForward() != null ? a.getDoNotForward() : false,
                a.getEnvironment()
        );
    }
}
