/*
 * Demonstrates the smplkit runtime SDK for Smpl Config.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigRuntimeShowcase {

    // Example POJO configuration classes to showcase how "code-first"
    // configuration management works

    public static final class App {
        public String name = "Acme SaaS";
    }

    public static final class Support {
        public String email = "support@acme.dev";
    }

    public static final class Plan {
        public int max_seats = 5;
        public int trial_days = 14;
        public String tier = "free";
    }

    /** Shared defaults for showcase services. */
    public static final class Common {
        public App app = new App();
        public Support support = new Support();
    }

    /** Plan-limit configuration for billing — inherits from Common. */
    public static final class Billing {
        public App app = new App();
        public Support support = new Support();
        public Plan plan = new Plan();
    }

    public static void main(String[] args) throws Exception {

        // or AsyncSmplClient for asynchronous use
        SmplClient client = SmplClient.builder()
                .environment("production").build();
        try {
            ConfigRuntimeSetup.cleanup(client);

            // bind POJOs
            Common common = client.config.bind("showcase-common", new Common());
            Billing billing = client.config.bind(
                    "showcase-billing", new Billing(), common);
            System.out.println("common.app.name = " + common.app.name);
            System.out.println("billing.app.name = " + billing.app.name
                    + "  // inherited from common");
            System.out.println("billing.plan.max_seats = " + billing.plan.max_seats);

            // add listeners if desired
            List<ConfigChangeEvent> changes = new ArrayList<>();
            client.config.onChange("showcase-billing", "plan.max_seats", evt -> {
                changes.add(evt);
                System.out.println("    [CHANGE] " + evt.configId() + "."
                        + evt.itemKey() + ": " + evt.oldValue()
                        + " -> " + evt.newValue());
            });

            client.waitUntilReady();

            // simulate someone making a change in smplkit console
            ConfigRuntimeSetup.simulateAdminOverride(client);

            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && billing.plan.max_seats != 25) {
                Thread.sleep(100);
            }

            // observe changes are automatically reflected in bound objects
            System.out.println("billing.plan.max_seats after override = "
                    + billing.plan.max_seats);
            assert billing.plan.max_seats == 25
                    : "Expected 25, got " + billing.plan.max_seats;
            assert !changes.isEmpty() : "Expected at least one change event";

            // you can also bind plain-old Maps
            Map<String, Object> primary = new LinkedHashMap<>();
            primary.put("host", "db.acme.example");
            primary.put("port", 5432);
            Map<String, Object> db = new LinkedHashMap<>();
            db.put("primary", primary);
            db.put("pool_size", 10);
            db.put("statement_timeout_ms", 30000);
            Map<String, Object> dbBound = client.config.bind("showcase-database", db);

            @SuppressWarnings("unchecked")
            Map<String, Object> primaryRef = (Map<String, Object>) dbBound.get("primary");
            System.out.println("db[primary][host] = " + primaryRef.get("host"));
            System.out.println("db[pool_size] = " + dbBound.get("pool_size"));
            assert "db.acme.example".equals(primaryRef.get("host"));
            assert ((Number) dbBound.get("pool_size")).intValue() == 10;

            // or read live values via subscribe(id)
            LiveConfigProxy commonView = client.config.subscribe("showcase-common");
            System.out.println("showcase-common (via get):");
            for (Map.Entry<String, Object> entry : commonView.entrySet()) {
                System.out.println("    " + entry.getKey() + " = " + entry.getValue());
            }
            assert "Acme SaaS".equals(commonView.get("app.name"));

            // or skip the POJO/Map and just fetch specific keys directly
            int slowQueryMs = ((Number) client.config.getValue(
                    "showcase-database", "slow_query_threshold_ms", 500)).intValue();
            System.out.println("showcase-database.slow_query_threshold_ms = "
                    + slowQueryMs + "  // default used (key absent)");
            assert slowQueryMs == 500;

            System.out.println("Done!");
        } finally {
            // Always tear down — even if an assertion above failed — so a
            // failed run never leaves orphaned configs for the next run.
            ConfigRuntimeSetup.cleanup(client);
            client.close();
        }
    }
}
