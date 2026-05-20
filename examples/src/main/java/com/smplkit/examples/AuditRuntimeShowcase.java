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
import com.smplkit.audit.AuditResourceType;
import com.smplkit.audit.AuditEventType;
import com.smplkit.audit.CreateEventInput;
import com.smplkit.audit.ListEventTypesInput;
import com.smplkit.audit.EventTypeListPage;
import com.smplkit.audit.ListEventsInput;
import com.smplkit.audit.ListEventsPage;
import com.smplkit.audit.ListResourceTypesInput;
import com.smplkit.audit.ListResourceTypesPage;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public final class AuditRuntimeShowcase {

    public static void main(String[] args) throws Exception {

        // create the client (no equivalent of Python's AsyncSmplClient in Java
        // today — SmplClient is the idiomatic entry point)
        try (SmplClient client = SmplClient.builder()
                .environment("production").service("showcase-service").build()) {

            String someResourceId = "showcase-" + UUID.randomUUID().toString().substring(0, 8);

            // record an event
            CreateEventInput input = new CreateEventInput("invoice.created", "invoice", someResourceId);
            input.occurredAt = OffsetDateTime.now();
            input.data = Map.of(
                    "snapshot", Map.of("total_cents", 4900, "currency", "USD"),
                    "request_id", "req-abc");
            client.audit().events().record(input);
            // flush so the event is durable before we read it back
            client.audit().events().flush(2000);
            System.out.println("Recorded events for invoice " + someResourceId);

            // list events
            ListEventsInput listInput = new ListEventsInput();
            listInput.resourceType = "invoice";
            listInput.resourceId = someResourceId;
            ListEventsPage page = client.audit().events().list(listInput);
            assert page.events.stream().anyMatch(e -> e.resourceId.equals(someResourceId))
                    : "expected event not found in list";
            UUID recordedEventId = page.events.get(0).id;
            System.out.println("Listed " + page.events.size() + " event(s) for invoice " + someResourceId);

            // fetch an event
            AuditEvent event = client.audit().events().get(recordedEventId);
            assert event.id.equals(recordedEventId) : "event id mismatch";
            assert event.resourceId.equals(someResourceId) : "resource_id mismatch";
            assert "invoice.created".equals(event.eventType) : "event type mismatch";
            System.out.println("Fetched event " + event.id + ": " + event.eventType);

            // list resource types observed
            ListResourceTypesPage resourceTypes = client.audit().resourceTypes().list(new ListResourceTypesInput());
            assert resourceTypes.resourceTypes.stream().anyMatch(rt -> "invoice".equals(rt.id))
                    : "expected 'invoice' resource type not found";
            System.out.println("Observed resource types: "
                    + resourceTypes.resourceTypes.stream().map(rt -> rt.id).toList());

            // list event types observed
            EventTypeListPage eventTypes = client.audit().eventTypes().list(new ListEventTypesInput());
            assert eventTypes.eventTypes.stream().anyMatch(a -> "invoice.created".equals(a.id))
                    : "expected 'invoice.created' event type not found";
            System.out.println("Observed event types: "
                    + eventTypes.eventTypes.stream().map(a -> a.id).toList());

            System.out.println("Done!");
        }
    }
}
