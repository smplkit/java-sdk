/*
 * Demonstrates the smplkit runtime SDK for Smpl Audit.
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

        // create the client
        try (SmplClient client = SmplClient.builder()
                .environment("production").service("showcase-service").build()) {

            // record an event
            String someResourceId = "showcase-" + UUID.randomUUID().toString().substring(0, 8);
            CreateEventInput input = new CreateEventInput("invoice.created", "invoice", someResourceId);
            input.occurredAt = OffsetDateTime.now();
            input.snapshot = Map.of("total_cents", 4900, "currency", "USD");
            input.data = Map.of("request_id", "req-abc");
            client.audit().events().record(input);

            // force the event to be posted (normally happens automatically, in the
            // background, but we want to force it to be written now for this demo)
            client.audit().events().flush(200);

            // list events
            ListEventsInput list = new ListEventsInput();
            list.resourceType = "invoice";
            list.resourceId = someResourceId;
            list.pageSize = 10;
            ListEventsPage page = client.audit().events().list(list);
            System.out.println("Found " + page.events.size() + " events for " + someResourceId + ":");
            for (AuditEvent ev : page.events) {
                System.out.println("  " + ev.action + "  id=" + ev.id + "  actor=" + ev.actorType);
            }

            assert page.events.size() == 1 : "Expected 1 event, got " + page.events.size();

            // fetch an event by ID
            AuditEvent first = client.audit().events().get(page.events.get(0).id);
            System.out.println("Round-tripped: " + first.action + " at " + first.occurredAt);

            System.out.println("Done!");
        }
    }
}
