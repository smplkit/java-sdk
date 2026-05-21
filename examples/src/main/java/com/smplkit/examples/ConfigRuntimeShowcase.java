/*
 * Demonstrates the smplkit runtime SDK for Smpl Config.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key (env or ~/.smplkit)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigRuntimeShowcase
 */
package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.ConfigChangeEvent;
import com.smplkit.config.LiveConfigProxy;
import com.smplkit.examples.setup.ConfigRuntimeSetup;

import java.util.ArrayList;
import java.util.List;

public final class ConfigRuntimeShowcase {

    public static void main(String[] args) throws Exception {
        // create the client
        try (SmplClient client = SmplClient.builder()
                .environment("production").service("showcase-billing").build()) {

            ConfigRuntimeSetup.setup(client.manage());

            // declare a common/shared configuration
            LiveConfigProxy common = client.config().getOrCreate(
                    "showcase-common",
                    "Shared defaults for showcase services.");

            // declare a configuration that inherits from some parent
            LiveConfigProxy billing = client.config().getOrCreate(
                    "showcase-billing", common,
                    "Plan-limit configuration discovered from code.");

            // get a configured value
            String appName = common.getString("app.name", "Acme SaaS");
            String supportEmail = common.getString("support.email", "support@acme.dev");
            int maxSeats = billing.getInt("plan.max_seats", 5, "Maximum seats per organization.");
            int trialDays = billing.getInt("plan.trial_days", 14);
            String tier = billing.getString("plan.tier", "free");

            System.out.println("app.name = " + appName);
            System.out.println("support.email = " + supportEmail);
            System.out.println("plan.max_seats = " + maxSeats);
            System.out.println("plan.trial_days = " + trialDays);
            System.out.println("plan.tier = " + tier);

            // listen for changes
            List<ConfigChangeEvent> changes = new ArrayList<>();
            billing.onChange("plan.max_seats", evt -> {
                changes.add(evt);
                System.out.println("    [CHANGE] " + evt.configId() + "."
                        + evt.itemKey() + ": " + evt.oldValue()
                        + " -> " + evt.newValue());
            });

            // simulate someone overriding a value in the console
            ConfigRuntimeSetup.simulateAdminOverride(client.manage());

            // wait for the WebSocket push to deliver the change, then refetch
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && billing.getInt("plan.max_seats", 5) != 25) {
                Thread.sleep(100);
            }

            // get the latest value
            int updatedSeats = billing.getInt("plan.max_seats", 5);
            System.out.println("plan.max_seats after override = " + updatedSeats);
            assert updatedSeats == 25 : "Expected 25, got " + updatedSeats;
            assert !changes.isEmpty() : "Expected at least one change event";

            ConfigRuntimeSetup.cleanup(client.manage());
            System.out.println("Done!");
        }
    }
}
