/*
 * Demonstrates the smplkit management SDK for Smpl Audit.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.AuditManagementShowcase
 */
package com.smplkit.examples;

import com.smplkit.audit.CreateForwarderInput;
import com.smplkit.audit.Forwarder;
import com.smplkit.audit.ForwarderHttp;
import com.smplkit.audit.ForwarderType;
import com.smplkit.audit.HttpHeader;
import com.smplkit.audit.ListForwardersInput;
import com.smplkit.audit.ListForwardersPage;
import com.smplkit.management.SmplManagementClient;

import java.util.Map;
import java.util.UUID;

public final class AuditManagementShowcase {

    // JSON Logic filter — only forward ``invoice.*`` actions.
    // Events that don't match are recorded as ``filtered_out`` deliveries.
    // See https://jsonlogic.com for the full operator reference.
    private static final Map<String, Object> INVOICE_FILTER =
            Map.of("in", java.util.List.of("invoice.", Map.of("var", "action")));

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

        // create the client (use AsyncSmplManagementClient for asynchronous use)
        try (SmplManagementClient manage = SmplManagementClient.create()) {
            String forwarderName = "showcase-" + UUID.randomUUID().toString().substring(0, 6);

            // create a forwarder
            CreateForwarderInput createInput = new CreateForwarderInput(
                    forwarderName, ForwarderType.HTTP,
                    new ForwarderHttp("https://httpbin.org/post"));
            createInput.http.headers.add(new HttpHeader("X-Showcase", "ok"));
            createInput.http.successStatus = "2xx";
            createInput.filter = INVOICE_FILTER;
            createInput.transform = SIEM_TRANSFORM;
            Forwarder forwarder = manage.audit.forwarders.create(createInput);
            assert forwarder.name.equals(forwarderName) : "name mismatch";
            assert forwarder.enabled : "expected enabled=true";
            assert INVOICE_FILTER.equals(forwarder.filter) : "filter mismatch";
            assert SIEM_TRANSFORM.equals(forwarder.transform) : "transform mismatch";
            System.out.println("Created forwarder: " + forwarder.slug);

            // fetch a forwarder
            Forwarder fetched = manage.audit.forwarders.get(forwarder.id);
            assert fetched.id.equals(forwarder.id) : "fetched id mismatch";
            assert fetched.name.equals(forwarderName) : "fetched name mismatch";
            assert INVOICE_FILTER.equals(fetched.filter) : "fetched filter mismatch";
            assert SIEM_TRANSFORM.equals(fetched.transform) : "fetched transform mismatch";
            System.out.println("Fetched forwarder: " + fetched.name);

            // list forwarders
            ListForwardersPage listed = manage.audit.forwarders.list(new ListForwardersInput());
            assert listed.forwarders.stream().anyMatch(f -> f.id.equals(forwarder.id))
                    : "created forwarder not in list";
            System.out.println("Account has " + listed.forwarders.size() + " forwarder(s)");

            // update a forwarder
            String renamed = forwarder.name + "-renamed";
            CreateForwarderInput updateInput = new CreateForwarderInput(
                    renamed, forwarder.forwarderType,
                    new ForwarderHttp("https://httpbin.org/post"));
            updateInput.http.headers.add(new HttpHeader("X-Showcase", "ok"));
            updateInput.http.successStatus = "2xx";
            updateInput.enabled = false;
            updateInput.filter = INVOICE_FILTER;
            updateInput.transform = SIEM_TRANSFORM;
            Forwarder updated = manage.audit.forwarders.update(forwarder.id, updateInput);
            assert updated.name.equals(renamed) : "updated name mismatch";
            assert !updated.enabled : "expected enabled=false";
            System.out.println("Updated forwarder: " + updated.name + " (enabled=" + updated.enabled + ")");

            // delete a forwarder
            manage.audit.forwarders.delete(forwarder.id);
            ListForwardersPage remaining = manage.audit.forwarders.list(new ListForwardersInput());
            assert remaining.forwarders.stream().noneMatch(f -> f.id.equals(forwarder.id))
                    : "deleted forwarder still in list";
            System.out.println("Deleted forwarder: " + forwarder.slug);

            System.out.println("Done!");
        }
    }
}
