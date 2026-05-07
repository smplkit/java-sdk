/*
 * Demonstrates the smplkit runtime SDK for Smpl Audit.
 *
 * Covers: event record / list / get, plus the SIEM forwarders surface
 * (create / list / delete + the test_forwarder/execute proxy + a
 * doNotForward event flow). The forwarders portion gracefully skips
 * on 402 (free / standard tier) so the showcase remains runnable in
 * any environment.
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
import com.smplkit.audit.CreateForwarderInput;
import com.smplkit.audit.Forwarder;
import com.smplkit.audit.ForwarderHttp;
import com.smplkit.audit.HttpHeader;
import com.smplkit.audit.ListEventsInput;
import com.smplkit.audit.ListEventsPage;
import com.smplkit.audit.TestForwarderInput;
import com.smplkit.audit.TestForwarderResult;
import com.smplkit.internal.generated.audit.ApiException;

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
            client.audit().events().flush(2000);

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

            // Forwarders (Pro tier — gracefully skip on 402)
            Forwarder fwd = null;
            try {
                ForwarderHttp http = new ForwarderHttp("https://httpbin.org/post");
                http.headers.add(new HttpHeader("X-Showcase", "ok"));
                CreateForwarderInput fwdInput = new CreateForwarderInput(
                        "showcase-" + UUID.randomUUID().toString().substring(0, 6),
                        "http", http);
                fwd = client.audit().forwarders().create(fwdInput);
                System.out.println("Created forwarder: " + fwd.slug);
            } catch (ApiException e) {
                if (e.getCode() == 402) {
                    System.out.println("Skipping forwarder showcase — account is not Pro tier");
                    System.out.println("Done!");
                    return;
                }
                throw e;
            }

            try {
                // doNotForward suppresses the forward but still records the
                // skip in the delivery log.
                CreateEventInput skipped = new CreateEventInput(
                        "invoice.created", "invoice", someResourceId + "-skipped");
                skipped.doNotForward = true;
                client.audit().events().record(skipped);
                client.audit().events().flush(2000);

                // Test the destination via the proxy
                TestForwarderInput tfi = new TestForwarderInput("https://httpbin.org/post");
                tfi.body = "{\"hello\":\"world\"}";
                tfi.timeoutMs = 5000;
                TestForwarderResult tr = client.audit().functions().executeTestForwarder(tfi);
                System.out.println("test_forwarder: succeeded=" + tr.succeeded
                        + " status=" + tr.responseStatus);
            } finally {
                client.audit().forwarders().delete(fwd.id);
                System.out.println("Deleted forwarder: " + fwd.slug);
            }

            System.out.println("Done!");
        }
    }
}
