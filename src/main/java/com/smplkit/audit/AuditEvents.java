package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.EventsApi;
import com.smplkit.internal.generated.audit.model.Event;
import com.smplkit.internal.generated.audit.model.EventListResponse;
import com.smplkit.internal.generated.audit.model.EventRequest;
import com.smplkit.internal.generated.audit.model.EventResource;
import com.smplkit.internal.generated.audit.model.EventResponse;
import com.smplkit.internal.generated.audit.model.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Audit events surface — accessed via {@link AuditClient#events()}.
 *
 * <p>{@link #record(CreateEventInput)} is fire-and-forget — the call
 * enqueues the event onto an in-memory bounded buffer and returns
 * immediately. Pass {@code flush=true} to
 * {@link #record(CreateEventInput, boolean)} (or
 * {@link #record(CreateEventInput, boolean, long)}) to instead block until
 * the event is durable — useful from CLI tools, tests, and any flow about
 * to exit the process. Reads ({@link #list(ListEventsInput)},
 * {@link #get(UUID)}) are synchronous.</p>
 */
public final class AuditEvents {

    /**
     * Default upper bound on an inline flush, in milliseconds (5s) — matches
     * the timeout {@link AuditClient#close()} applies at shutdown and the
     * canonical SDK default.
     */
    public static final long DEFAULT_FLUSH_TIMEOUT_MS = 5_000L;

    private final EventsApi api;
    private final AuditEventBuffer buffer;
    private final String environment;

    AuditEvents(EventsApi api) {
        this(api, null);
    }

    AuditEvents(EventsApi api, String environment) {
        this.api = api;
        this.environment = environment;
        this.buffer = new AuditEventBuffer(api);
    }

    /**
     * Enqueue an audit event for asynchronous delivery. Returns immediately.
     *
     * <p>Fire-and-forget: equivalent to
     * {@link #record(CreateEventInput, boolean) record(input, false)}. The
     * event is appended to an in-memory bounded buffer drained by a
     * background worker, so this returns without awaiting any network
     * round-trip.</p>
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
        record(input, false, DEFAULT_FLUSH_TIMEOUT_MS);
    }

    /**
     * Enqueue an audit event, optionally blocking until it is durable.
     *
     * <p>When {@code flush} is {@code false} this is fire-and-forget and
     * returns immediately. When {@code flush} is {@code true} the call blocks
     * until the buffer has drained or the default timeout
     * ({@value #DEFAULT_FLUSH_TIMEOUT_MS}ms) elapses — equivalent to
     * {@link #record(CreateEventInput, boolean, long)
     * record(input, true, DEFAULT_FLUSH_TIMEOUT_MS)}.</p>
     *
     * @param input the event to record; per-field semantics live on
     *     {@link CreateEventInput}
     * @param flush when {@code true}, block until the buffer drains (or the
     *     default timeout elapses) before returning; when {@code false},
     *     return immediately
     * @throws IllegalArgumentException if eventType / resourceType / resourceId
     *     are missing
     */
    public void record(CreateEventInput input, boolean flush) {
        record(input, flush, DEFAULT_FLUSH_TIMEOUT_MS);
    }

    /**
     * Enqueue an audit event, optionally blocking until it is durable with an
     * explicit timeout.
     *
     * <p>The event is always appended to the in-memory buffer first. When
     * {@code flush} is {@code true}, this call then blocks until the buffer
     * has drained or {@code flushTimeoutMs} elapses; use it when the caller
     * needs the event durable before continuing — typical examples are CLI
     * tools, in-test assertions, and any flow about to exit the process. The
     * fire-and-forget default ({@code flush=false}) remains the right choice
     * on the request-handling hot path.</p>
     *
     * <p>Customer attempts to record events with {@code resourceType}
     * starting with {@code smpl.} are rejected by the server with a 403
     * (the buffer logs the permanent failure and drops the item).</p>
     *
     * @param input the event to record; per-field semantics live on
     *     {@link CreateEventInput}
     * @param flush when {@code true}, block until the buffer drains (or
     *     {@code flushTimeoutMs} elapses) before returning; when {@code false},
     *     return immediately and ignore {@code flushTimeoutMs}
     * @param flushTimeoutMs upper bound on the blocking flush, in
     *     milliseconds; ignored when {@code flush} is {@code false}
     * @throws IllegalArgumentException if eventType / resourceType / resourceId
     *     are missing
     */
    public void record(CreateEventInput input, boolean flush, long flushTimeoutMs) {
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
        if (input.severity != null) {
            attrs.severity(Severity.fromValue(input.severity));
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
        // Stamp the client's configured environment onto the event request body
        // — the body-driven replacement for the dead X-Smplkit-Environment
        // header (ADR-055). Omitted when null so a single-environment credential
        // resolves it server-side.
        if (environment != null) {
            attrs.environment(environment);
        }
        EventResource resource = new EventResource()
                .id("") // server assigns
                .type("event")
                .attributes(attrs);
        EventRequest body = new EventRequest().data(resource);
        buffer.enqueue(body, input.idempotencyKey);
        if (flush) {
            buffer.flush(flushTimeoutMs);
        }
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
     * <p>{@link ListEventsInput#environments} scopes the read to a set of
     * environments — environment keys and/or the reserved {@code "smplkit"}
     * control-plane bucket, sent comma-separated as {@code filter[environment]}.
     * Omit it (the default) to scope the read to the client's configured
     * environment; with no configured environment the filter is left off
     * entirely.</p>
     *
     * @param input filters and pagination cursor; an empty instance lists
     *     every event with default paging
     * @return a {@link ListEventsPage} of the matching events; its
     *     {@code nextCursor} is set when more pages are available
     * @throws ApiException if the request fails
     */
    public ListEventsPage list(ListEventsInput input) throws ApiException {
        EventListResponse resp = api.listEvents(
                AuditResourceTypesClient.resolveEnvironmentFilter(input.environments, environment),
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
