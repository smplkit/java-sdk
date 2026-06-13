package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.UUID;

/**
 * The Smpl Audit client (async) — counterpart of {@link AuditClient}.
 *
 * <p>Genuinely async: event reads, discovery, and forwarder CRUD perform their
 * network round-trips on the wrapper's {@link Executor} and return a
 * {@code CompletableFuture<T>}; only {@code events.record} is fire-and-forget
 * (it enqueues onto a background worker thread and returns without awaiting),
 * which is the correct shape for the hot path. The forwarder factory
 * ({@code forwarders.newForwarder}) stays synchronous — it performs no I/O.</p>
 *
 * <p>Holds a sync {@link AuditClient} as the single source of truth for state.
 * Use {@link #create()} for default credentials and the common-pool executor;
 * use {@link #wrap(AuditClient, Executor)} to override the executor
 * (recommended for production: a bounded I/O thread pool).</p>
 *
 * <pre>{@code
 * try (AsyncAuditClient audit = AsyncAuditClient.create()) {
 *     audit.events().list(new ListEventsInput())
 *          .thenAccept(page -> page.events.forEach(e -> System.out.println(e.id)));
 * }
 * }</pre>
 */
public final class AsyncAuditClient implements AutoCloseable {

    private final AuditClient delegate;
    private final Executor executor;

    private final AsyncEvents events;
    private final AsyncResourceTypes resourceTypes;
    private final AsyncEventTypes eventTypes;
    private final AsyncCategories categories;
    private final AsyncForwarders forwarders;

    private AsyncAuditClient(AuditClient delegate, Executor executor) {
        this.delegate = delegate;
        this.executor = executor;
        this.events = new AsyncEvents();
        this.resourceTypes = new AsyncResourceTypes();
        this.eventTypes = new AsyncEventTypes();
        this.categories = new AsyncCategories();
        this.forwarders = new AsyncForwarders();
    }

    /** Create with default credentials and the common-pool executor. */
    public static AsyncAuditClient create() {
        return wrap(AuditClient.create(), ForkJoinPool.commonPool());
    }

    /** Create with the given API key and the common-pool executor. */
    public static AsyncAuditClient create(String apiKey) {
        return wrap(AuditClient.create(apiKey), ForkJoinPool.commonPool());
    }

    /** Wrap an existing {@link AuditClient}, using the common-pool executor. */
    public static AsyncAuditClient wrap(AuditClient delegate) {
        return wrap(delegate, ForkJoinPool.commonPool());
    }

    /**
     * Wrap an existing {@link AuditClient} with a custom executor.
     *
     * <p>For production use, supply a bounded I/O thread pool rather than the
     * common pool — audit reads and forwarder CRUD are blocking I/O and
     * shouldn't compete with compute work on the common pool.</p>
     */
    public static AsyncAuditClient wrap(AuditClient delegate, Executor executor) {
        return new AsyncAuditClient(delegate, executor);
    }

    /** Returns the underlying sync client. */
    public AuditClient sync() {
        return delegate;
    }

    /** Returns the executor used to schedule async work. */
    public Executor executor() {
        return executor;
    }

    /** Returns the async events sub-client (record/flush/list/get). */
    public AsyncEvents events() {
        return events;
    }

    /** Returns the async resource-types sub-client (list). */
    public AsyncResourceTypes resourceTypes() {
        return resourceTypes;
    }

    /** Returns the async event-types sub-client (list). */
    public AsyncEventTypes eventTypes() {
        return eventTypes;
    }

    /** Returns the async categories sub-client (list). */
    public AsyncCategories categories() {
        return categories;
    }

    /** Returns the async forwarders sub-client (SIEM forwarder CRUD). */
    public AsyncForwarders forwarders() {
        return forwarders;
    }

    @Override
    public void close() {
        delegate.close();
    }

    // ------------------------------------------------------------------
    // Async sub-clients
    // ------------------------------------------------------------------

    /** Async surface for {@code audit.events.*}. */
    public final class AsyncEvents {

        /**
         * Enqueue an audit event for asynchronous delivery.
         *
         * <p>Fire-and-forget: the event is appended to an in-memory buffer
         * drained by a background worker thread, so this returns without
         * awaiting any network round-trip — nothing blocks the caller.</p>
         *
         * @param input the event to record; per-field semantics live on
         *     {@link CreateEventInput}
         * @throws IllegalArgumentException if eventType / resourceType /
         *     resourceId are missing
         */
        public void record(CreateEventInput input) {
            delegate.events().record(input);
        }

        /**
         * Drain the in-memory buffer, scheduled on the wrapper's executor.
         *
         * @param timeoutMs upper bound on the flush, in milliseconds
         * @return a future that completes when the buffer is drained or the
         *     timeout elapses
         */
        public CompletableFuture<Void> flush(long timeoutMs) {
            return CompletableFuture.runAsync(() -> delegate.events().flush(timeoutMs), executor);
        }

        /**
         * List audit events for the authenticated account, scheduled on the
         * wrapper's executor.
         *
         * <p>Filters apply server-side and pagination uses an opaque cursor;
         * the returned page exposes {@code nextCursor} when more pages are
         * available. A {@link ListEventsInput#search} filter must be scoped
         * (combine it with {@code occurredAtRange}, or with both
         * {@code resourceType} and {@code resourceId}) or the request is
         * rejected.</p>
         *
         * @param input filters and pagination cursor; an empty instance lists
         *     every event with default paging
         * @return a future yielding a {@link ListEventsPage} of the matching
         *     events; its {@code nextCursor} is set when more pages are
         *     available. The future completes exceptionally (wrapping the
         *     {@code ApiException}) if the request fails
         */
        public CompletableFuture<ListEventsPage> list(ListEventsInput input) {
            return CompletableFuture.supplyAsync(() -> checked(() -> delegate.events().list(input)), executor);
        }

        /**
         * Retrieve a single audit event by id, scheduled on the wrapper's
         * executor.
         *
         * @param eventId the event's UUID
         * @return a future yielding the matching {@link AuditEvent}; it
         *     completes exceptionally (wrapping the {@code ApiException}) if no
         *     event with that id exists in the caller's account
         */
        public CompletableFuture<AuditEvent> get(UUID eventId) {
            return CompletableFuture.supplyAsync(() -> checked(() -> delegate.events().get(eventId)), executor);
        }
    }

    /** Async surface for {@code audit.resource_types.*}. */
    public final class AsyncResourceTypes {

        /**
         * List the distinct {@code resource_type} slugs seen in the account,
         * scheduled on the wrapper's executor. Sorted alphabetically; offset
         * paginated.
         *
         * @param input optional environment scope and pagination; an empty
         *     instance lists every distinct resource type
         * @return a future yielding a {@link ListResourceTypesPage} of the
         *     matching resource-type slugs; it completes exceptionally
         *     (wrapping the {@code ApiException}) if the request fails
         */
        public CompletableFuture<ListResourceTypesPage> list(ListResourceTypesInput input) {
            return CompletableFuture.supplyAsync(() -> checked(() -> delegate.resourceTypes().list(input)), executor);
        }
    }

    /** Async surface for {@code audit.event_types.*}. */
    public final class AsyncEventTypes {

        /**
         * List the distinct {@code event_type} slugs seen in the account,
         * scheduled on the wrapper's executor. Without a resource-type filter,
         * returns one row per distinct event type; with the filter, returns the
         * event types seen with that specific resource type (cascading filter).
         * Sorted alphabetically; offset paginated.
         *
         * @param input optional resource-type filter, environment scope, and
         *     pagination; an empty instance lists every distinct event type
         * @return a future yielding an {@link EventTypeListPage} of the matching
         *     event-type slugs; it completes exceptionally (wrapping the
         *     {@code ApiException}) if the request fails
         */
        public CompletableFuture<EventTypeListPage> list(ListEventTypesInput input) {
            return CompletableFuture.supplyAsync(() -> checked(() -> delegate.eventTypes().list(input)), executor);
        }
    }

    /** Async surface for {@code audit.categories.*}. */
    public final class AsyncCategories {

        /**
         * List the distinct {@code category} values seen in the account,
         * scheduled on the wrapper's executor. Sorted alphabetically; offset
         * paginated.
         *
         * @param input optional environment scope and pagination; an empty
         *     instance lists every distinct category
         * @return a future yielding a {@link ListCategoriesPage} of the matching
         *     category values; it completes exceptionally (wrapping the
         *     {@code ApiException}) if the request fails
         */
        public CompletableFuture<ListCategoriesPage> list(ListCategoriesInput input) {
            return CompletableFuture.supplyAsync(() -> checked(() -> delegate.categories().list(input)), executor);
        }
    }

    /**
     * Async surface for {@code audit.forwarders.*}.
     *
     * <p>Every method performs its network round-trip on the wrapper's
     * executor, except {@link #newForwarder} which is a synchronous factory
     * (no I/O). The returned {@link Forwarder} instances expose
     * {@code saveAsync()} / {@code deleteAsync()} for async persistence.</p>
     */
    public final class AsyncForwarders {

        /**
         * Return an unsaved {@link Forwarder} bound to the sync client.
         * Synchronous — no I/O. Call {@code .save()} / {@code .saveAsync()} to
         * create it.
         */
        public Forwarder newForwarder(String id, String name, ForwarderType forwarderType,
                                      HttpConfiguration configuration) {
            return delegate.forwarders().newForwarder(id, name, forwarderType, configuration);
        }

        /**
         * Return an unsaved {@link Forwarder} bound to the sync client, with a
         * transform pre-configured. Synchronous — no I/O.
         */
        public Forwarder newForwarder(String id, String name, ForwarderType forwarderType,
                                      HttpConfiguration configuration,
                                      TransformType transformType, Object transform) {
            return delegate.forwarders().newForwarder(id, name, forwarderType, configuration,
                    transformType, transform);
        }

        /**
         * List forwarders for the authenticated account, using default filters
         * and page size, scheduled on the wrapper's executor.
         *
         * @return a future yielding a {@link ListForwardersPage} of the
         *     matching forwarders; it completes exceptionally (wrapping the
         *     {@code ApiException}) if the request fails
         */
        public CompletableFuture<ListForwardersPage> list() {
            return CompletableFuture.supplyAsync(() -> checked(() -> delegate.forwarders().list()), executor);
        }

        /**
         * List forwarders for the authenticated account, scheduled on the
         * wrapper's executor. Offset paginated.
         *
         * @param input filters (forwarder type) and pagination; an empty
         *     instance lists every type with default paging
         * @return a future yielding a {@link ListForwardersPage} of the
         *     matching forwarders; it completes exceptionally (wrapping the
         *     {@code ApiException}) if the request fails
         */
        public CompletableFuture<ListForwardersPage> list(ListForwardersInput input) {
            return CompletableFuture.supplyAsync(() -> checked(() -> delegate.forwarders().list(input)), executor);
        }

        /**
         * Fetch a single forwarder by id, scheduled on the wrapper's executor.
         * The returned instance is bound to the sync client so its
         * {@code saveAsync()} / {@code deleteAsync()} work. Header values come
         * back in plaintext, so mutating the returned forwarder and saving
         * preserves them without re-entering secrets.
         *
         * @param forwarderId the forwarder's id (key)
         * @return a future yielding the matching {@link Forwarder}, bound to the
         *     sync client; it completes exceptionally (wrapping the
         *     {@code ApiException}) if no forwarder with that id exists in the
         *     caller's account
         */
        public CompletableFuture<Forwarder> get(String forwarderId) {
            return CompletableFuture.supplyAsync(() -> checked(() -> delegate.forwarders().get(forwarderId)), executor);
        }

        /**
         * Delete a forwarder by id, scheduled on the wrapper's executor.
         *
         * @param forwarderId the id (key) of the forwarder to delete
         * @return a future that completes when the forwarder is deleted; it
         *     completes exceptionally (wrapping the {@code ApiException}) if the
         *     request fails
         */
        public CompletableFuture<Void> delete(String forwarderId) {
            return CompletableFuture.runAsync(() -> {
                try {
                    delegate.forwarders().delete(forwarderId);
                } catch (ApiException e) {
                    throw new CompletionException(e);
                }
            }, executor);
        }
    }

    @FunctionalInterface
    private interface AuditCall<T> {
        T call() throws ApiException;
    }

    private static <T> T checked(AuditCall<T> call) {
        try {
            return call.call();
        } catch (ApiException e) {
            throw new CompletionException(e);
        }
    }
}
