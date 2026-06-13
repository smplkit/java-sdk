/*
 * Demonstrates the smplkit SDK for Smpl Audit.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.AuditShowcase
 */
package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.audit.AuditClient;
import com.smplkit.audit.AuditEvent;
import com.smplkit.audit.CreateEventInput;
import com.smplkit.audit.Forwarder;
import com.smplkit.audit.ForwarderType;
import com.smplkit.audit.HttpConfiguration;
import com.smplkit.audit.HttpHeader;
import com.smplkit.audit.HttpMethod;
import com.smplkit.audit.ListCategoriesInput;
import com.smplkit.audit.ListEventTypesInput;
import com.smplkit.audit.ListEventsInput;
import com.smplkit.audit.ListEventsPage;
import com.smplkit.audit.ListForwardersPage;
import com.smplkit.audit.ListResourceTypesInput;
import com.smplkit.audit.TransformType;
import com.smplkit.internal.generated.audit.ApiException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuditShowcase {

    // JSON Logic filter — only forward ``invoice.*`` actions. Events that don't
    // match the filter aren't forwarded (and produce no delivery record).
    // See https://jsonlogic.com for the full operator reference.
    private static final Map<String, Object> INVOICE_FILTER =
            Map.of("in", List.of("invoice.", Map.of("var", "action")));

    // JSONata template — reshape the event payload before POSTing to the
    // destination. This example flattens the event into a compact SIEM-style
    // record. See https://jsonata.org for the full language reference.
    private static final String SIEM_TRANSFORM = """
            {
                "event": action,
                "subject": resource_type & ":" & resource_id,
                "ts": occurred_at,
                "actor": actor_label
            }
            """;

    public static void main(String[] args) throws Exception {

        // or AsyncSmplClient for asynchronous use
        try (SmplClient client = SmplClient.builder().environment("production").build()) {
            AuditClient audit = client.audit;
            String someResourceId = "showcase-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

            // ----- Events: record / list / get --------------------------------

            // record an event
            CreateEventInput input = new CreateEventInput("invoice.created", "invoice", someResourceId);
            input.actorId = "billing-bot:42";
            input.actorLabel = "finance@example.com";
            input.actorType = "USER";
            input.category = "billing";
            input.data = Map.of(
                    "snapshot", Map.of("total_cents", 4900, "currency", "USD"),
                    "request_id", "req-abc");
            input.occurredAt = OffsetDateTime.now();
            audit.events().record(input);
            // flush so the event is durable before we read it back (or omit to
            // have events flushed asynchronously)
            audit.events().flush(5_000);
            System.out.println("Recorded event for invoice " + someResourceId);

            // list events
            ListEventsInput listInput = new ListEventsInput();
            listInput.resourceType = "invoice";
            listInput.resourceId = someResourceId;
            ListEventsPage page = audit.events().list(listInput);
            assert page.events.stream().anyMatch(e -> someResourceId.equals(e.resourceId))
                    : "expected event not found in list";
            UUID recordedEventId = page.events.get(0).id;
            System.out.println("Listed " + page.events.size() + " event(s) for invoice " + someResourceId);

            // fetch an event
            AuditEvent event = audit.events().get(recordedEventId);
            assert event.id.equals(recordedEventId) : "event id mismatch";
            assert event.resourceId.equals(someResourceId) : "resource_id mismatch";
            assert "invoice.created".equals(event.eventType) : "event type mismatch";
            assert "billing-bot:42".equals(event.actorId) : "actor_id mismatch";
            assert "finance@example.com".equals(event.actorLabel) : "actor_label mismatch";
            assert "billing".equals(event.category) : "category mismatch";
            System.out.println("Fetched event " + event.id + ": " + event.eventType
                    + " by " + event.actorLabel + " in " + event.environment);

            // ----- Discovery: distinct resource_types / event_types / categories

            var resourceTypes = audit.resourceTypes().list(new ListResourceTypesInput());
            assert resourceTypes.resourceTypes.stream().anyMatch(rt -> "invoice".equals(rt.id))
                    : "expected 'invoice' resource type not found";
            System.out.println("Observed resource types: "
                    + resourceTypes.resourceTypes.stream().map(rt -> rt.id).toList());

            var eventTypes = audit.eventTypes().list(new ListEventTypesInput());
            assert eventTypes.eventTypes.stream().anyMatch(et -> "invoice.created".equals(et.id))
                    : "expected 'invoice.created' event type not found";
            System.out.println("Observed event types: "
                    + eventTypes.eventTypes.stream().map(et -> et.id).toList());

            var categories = audit.categories().list(new ListCategoriesInput());
            assert categories.categories.stream().anyMatch(c -> "billing".equals(c.id))
                    : "expected 'billing' category not found";
            System.out.println("Observed categories: "
                    + categories.categories.stream().map(c -> c.id).toList());

            // ----- Forwarders: SIEM streaming CRUD ----------------------------

            String forwarderId = "showcase-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);

            try {
                // create a forwarder (disabled by default)
                Forwarder forwarder = audit.forwarders().newForwarder(
                        forwarderId,
                        forwarderId,
                        ForwarderType.HTTP,
                        new HttpConfiguration(
                                HttpMethod.POST,
                                "https://example.com",
                                List.of(new HttpHeader("X-Showcase", "ok"))),
                        TransformType.JSONATA,
                        SIEM_TRANSFORM);
                forwarder.filter = INVOICE_FILTER;
                forwarder.save();
                System.out.println("Created forwarder: " + forwarder.name + " (id=" + forwarder.id + ")");

                // list forwarders
                ListForwardersPage listed = audit.forwarders().list();
                assert listed.forwarders.stream().anyMatch(f -> f.id.equals(forwarderId))
                        : "created forwarder not in list";
                System.out.println("Account has " + listed.forwarders.size() + " forwarder(s)");

                // get a forwarder
                forwarder = client.audit.forwarders().get(forwarderId);
                System.out.println("Fetched forwarder: " + forwarder.name + " (id=" + forwarder.id + ")");
                assert forwarder.id.equals(forwarderId) : "fetched id mismatch";

                // configure where to forward events in production
                forwarder.setConfiguration(new HttpConfiguration(
                        HttpMethod.POST,
                        "https://httpbin.org/post",
                        List.of(new HttpHeader("X-Showcase", "ok"))),
                        "production");
                forwarder.save();
                assert "https://httpbin.org/post".equals(
                        forwarder.environments.get("production").configuration.url)
                        : "expected production configuration url updated";
                System.out.println("Updated forwarder: " + forwarder.name);

                // start forwarding events in production
                forwarder.setEnabled(true, "production");
                forwarder.save();
                System.out.println("Enabled forwarder " + forwarder.name + " (id=" + forwarder.id + ") "
                        + "to start forwarding events in production");

                // delete a forwarder
                forwarder.delete();
                ListForwardersPage remaining = audit.forwarders().list();
                assert remaining.forwarders.stream().noneMatch(f -> f.id.equals(forwarderId))
                        : "deleted forwarder still in list";
                System.out.println("Deleted forwarder: " + forwarder.name);
            } finally {
                // tear-down: never leave the showcase forwarder behind, even on failure
                try {
                    audit.forwarders().delete(forwarderId);
                } catch (ApiException err) {
                    if (err.getCode() != 404) {
                        throw err;
                    }
                }
            }

            System.out.println("Done!");
        }
    }
}
