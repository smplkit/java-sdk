/*
 * Demonstrates the smplkit runtime SDK for Smpl Audit.
 *
 * Audit is a fire-and-forget event-recording surface. {@code create}
 * enqueues the event onto an in-memory bounded buffer and returns
 * immediately; the buffer worker retries with exponential backoff on
 * transient failures and drops oldest under back-pressure (ADR-047 §2.6).
 * Reads ({@code get}, {@code list}) are synchronous on the wire.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.AuditRuntimeShowcase
 */
package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.audit.AuditEvent;
import com.smplkit.audit.CreateEventInput;
import com.smplkit.audit.ListEventsInput;
import com.smplkit.audit.ListEventsPage;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public final class AuditRuntimeShowcase {

    public static void main(String[] args) throws Exception {
        try (SmplClient client = SmplClient.builder()
                .environment("production").service("showcase-service").build()) {

            // unique resource id so we can find back exactly the events
            // this showcase wrote, regardless of what other history exists.
            String resourceId = "showcase-" + UUID.randomUUID().toString().substring(0, 8);

            // 1) fire-and-forget create — returns immediately. The actual
            //    POST happens on the buffer worker. Customer events must
            //    NOT use a resourceType beginning with "smpl." — that
            //    namespace is reserved for smplkit-emitted events; the
            //    server returns 403.
            CreateEventInput created = new CreateEventInput("invoice.created", "invoice", resourceId);
            created.occurredAt = OffsetDateTime.now();
            created.snapshot = Map.of("total_cents", 4900, "currency", "USD");
            created.data = Map.of("request_id", "req-abc");
            client.audit().events().create(created);

            // 2) caller-supplied idempotency key — replaying with the
            //    same key returns the original event (server dedupes
            //    on account_id + idempotency_key).
            String idempotencyKey = "showcase-" + UUID.randomUUID();
            for (int i = 0; i < 2; i++) {
                CreateEventInput updated = new CreateEventInput("invoice.updated", "invoice", resourceId);
                updated.snapshot = Map.of("total_cents", 5400);
                updated.idempotencyKey = idempotencyKey;
                client.audit().events().create(updated);
            }

            // 3) flush — block until the in-memory buffer drains so
            //    that the events we just wrote are durable before we
            //    read them.
            client.audit().events().flush(5_000);

            // 4) list — server-side filters per ADR-047 §4.  Cursor
            //    pagination via pageSize / pageAfter; page.nextCursor
            //    is non-null when more pages exist.
            ListEventsInput list = new ListEventsInput();
            list.resourceType = "invoice";
            list.resourceId = resourceId;
            list.pageSize = 10;
            ListEventsPage page = client.audit().events().list(list);

            System.out.println("Found " + page.events.size() + " events for " + resourceId + ":");
            for (AuditEvent ev : page.events) {
                System.out.println("  " + ev.action + "  id=" + ev.id + "  actor=" + ev.actorType);
            }

            // idempotency dedupe check — 3 creates (1 distinct + 2 with
            // the same idempotency key) so we expect exactly 2 events.
            if (page.events.size() != 2) {
                throw new IllegalStateException(
                    "Expected 2 events (idempotency dedup), got " + page.events.size());
            }

            // 5) get — read a single event by id.
            AuditEvent first = client.audit().events().get(page.events.get(0).id);
            System.out.println("Round-tripped: " + first.action + " at " + first.occurredAt);

            System.out.println("Done!");
        }
    }
}
