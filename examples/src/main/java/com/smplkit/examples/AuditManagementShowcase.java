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

import com.smplkit.audit.Forwarder;
import com.smplkit.audit.ForwarderEnvironment;
import com.smplkit.audit.ForwarderType;
import com.smplkit.audit.HttpConfiguration;
import com.smplkit.audit.HttpHeader;
import com.smplkit.audit.HttpMethod;
import com.smplkit.audit.ListForwardersPage;
import com.smplkit.audit.TransformType;
import com.smplkit.management.SmplManagementClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuditManagementShowcase {

    // JSON Logic filter — only forward ``invoice.*`` event types.
    // Events that don't match the filter aren't forwarded (and produce no delivery record).
    // See https://jsonlogic.com for the full operator reference.
    private static final Map<String, Object> INVOICE_FILTER =
            Map.of("in", List.of("invoice.", Map.of("var", "event_type")));

    // JSONata template — reshape the event payload before POSTing to the
    // destination. This example flattens the event into a compact SIEM-style
    // record. See https://jsonata.org for the full language reference.
    private static final String SIEM_TRANSFORM = """
            {
                "event": event_type,
                "subject": resource_type & ":" & resource_id,
                "ts": occurred_at,
                "actor": actor_label
            }
            """;

    public static void main(String[] args) throws Exception {

        // create the client (use AsyncSmplManagementClient for asynchronous use)
        try (SmplManagementClient manage = SmplManagementClient.create()) {
            String forwarderName = "showcase-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);

            // create a new forwarder
            Forwarder forwarder = manage.audit.forwarders.newForwarder(
                    forwarderName,                              // id (caller-supplied key)
                    forwarderName,                              // name (free-form display)
                    ForwarderType.HTTP,
                    new HttpConfiguration(
                            HttpMethod.POST,
                            "https://httpbin.org/post",
                            List.of(new HttpHeader("X-Showcase", "ok"))),
                    TransformType.JSONATA,
                    SIEM_TRANSFORM);
            forwarder.filter = INVOICE_FILTER;
            // Enablement is per-environment: a forwarder delivers in an
            // environment only when that environment's entry is enabled. The
            // base `enabled` field is read-only and always false.
            forwarder.environments.put("production", new ForwarderEnvironment(true));
            forwarder.save();
            System.out.println("Created forwarder: " + forwarder.name + " (id=" + forwarder.id + ")");

            // list forwarders
            ListForwardersPage listed = manage.audit.forwarders.list();
            assert listed.forwarders.stream().anyMatch(f -> f.id.equals(forwarder.id))
                    : "created forwarder not in list";
            System.out.println("Account has " + listed.forwarders.size() + " forwarder(s)");

            // get a forwarder
            Forwarder fetched = manage.audit.forwarders.get(forwarder.id);
            assert fetched.id.equals(forwarder.id) : "fetched id mismatch";
            assert fetched.environments.containsKey("production")
                    && fetched.environments.get("production").enabled
                    : "expected forwarder enabled in production";
            System.out.println("Fetched forwarder: " + fetched.name
                    + " (enabled in: " + fetched.environments.entrySet().stream()
                            .filter(e -> e.getValue().enabled).map(Map.Entry::getKey).toList() + ")");

            // disable the forwarder in production (per-environment)
            fetched.environments.put("production", new ForwarderEnvironment(false));
            fetched.save();
            assert !fetched.environments.get("production").enabled
                    : "expected production disabled";
            System.out.println("Disabled forwarder in production: " + fetched.name);

            // delete a forwarder
            fetched.delete();
            ListForwardersPage remaining = manage.audit.forwarders.list();
            assert remaining.forwarders.stream().noneMatch(f -> f.id.equals(forwarder.id))
                    : "deleted forwarder still in list";
            System.out.println("Deleted forwarder: " + fetched.name);

            System.out.println("Done!");
        }
    }
}
