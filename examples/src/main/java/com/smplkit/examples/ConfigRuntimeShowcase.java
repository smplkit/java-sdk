/*
 * Demonstrates the smplkit runtime SDK for Smpl Config.
 *
 * Headline pattern: declare configurations as Java POJOs, bind() them to
 * a config id, then use the returned objects directly — property access
 * stays in sync with the server via the SDK's in-memory cache and
 * WebSocket push.
 *
 * Also demonstrates three lower-friction patterns:
 *   - bind() with an untyped Map
 *   - get(id) for dict-like lookup of an entire config
 *   - get(id, key, default) for one-shot value reads with fallback
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key (env or ~/.smplkit)
 *
 * Usage:
 *     make config_runtime_showcase
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

    // Configuration shapes for the typed declarative path. Nested public
    // fields flatten to dot-notation when registered with the server.

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
        // create the client
        try (SmplClient client = SmplClient.builder()
                .environment("production").service("showcase-billing").build()) {

            ConfigRuntimeSetup.cleanup(client.manage());

            // Pattern 1: typed declarative — bind a POJO. The class is the
            // schema; the instance carries the in-code defaults. Nested
            // POJO fields flatten to dot-notation (e.g. "plan.max_seats").
            Common common = client.config().bind("showcase-common", new Common());
            Billing billing = client.config().bind(
                    "showcase-billing", new Billing(), common);

            System.out.println("common.app.name = " + common.app.name);
            System.out.println("billing.plan.max_seats = " + billing.plan.max_seats);
            assert "Acme SaaS".equals(common.app.name);
            assert billing.plan.max_seats == 5;

            // listen for changes
            List<ConfigChangeEvent> changes = new ArrayList<>();
            client.config().onChange("showcase-billing", "plan.max_seats", evt -> {
                changes.add(evt);
                System.out.println("    [CHANGE] " + evt.configId() + "."
                        + evt.itemKey() + ": " + evt.oldValue()
                        + " -> " + evt.newValue());
            });

            // simulate someone making a change in smplkit console
            ConfigRuntimeSetup.simulateAdminOverride(client.manage());

            // wait for the WebSocket push, then observe in-place mutation
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && billing.plan.max_seats != 25) {
                Thread.sleep(100);
            }
            System.out.println("billing.plan.max_seats after override = "
                    + billing.plan.max_seats);
            assert billing.plan.max_seats == 25
                    : "Expected 25, got " + billing.plan.max_seats;
            assert !changes.isEmpty() : "Expected at least one change event";

            // Pattern 2: untyped declarative — bind a Map (no class needed).
            // Nested Maps flatten to dot-notation the same way nested POJOs do.
            Map<String, Object> primary = new LinkedHashMap<>();
            primary.put("host", "db.acme.example");
            primary.put("port", 5432);
            Map<String, Object> db = new LinkedHashMap<>();
            db.put("primary", primary);
            db.put("pool_size", 10);
            db.put("statement_timeout_ms", 30000);
            Map<String, Object> dbBound = client.config().bind("showcase-database", db);

            @SuppressWarnings("unchecked")
            Map<String, Object> primaryRef = (Map<String, Object>) dbBound.get("primary");
            System.out.println("db[primary][host] = " + primaryRef.get("host"));
            System.out.println("db[pool_size] = " + dbBound.get("pool_size"));
            assert "db.acme.example".equals(primaryRef.get("host"));
            assert ((Number) dbBound.get("pool_size")).intValue() == 10;

            // Pattern 3: get(id) — read-only lookup of an entire config
            // (throws NotFoundError if missing).
            LiveConfigProxy commonView = client.config().get("showcase-common");
            System.out.println("showcase-common (via get):");
            for (Map.Entry<String, Object> entry : commonView.entrySet()) {
                System.out.println("    " + entry.getKey() + " = " + entry.getValue());
            }
            assert "Acme SaaS".equals(commonView.get("app.name"));

            // Pattern 4: get(id, key, default) — one-shot value with default.
            // Auto-registers the (config, key) so the reference shows up in
            // the smplkit console even if no schema was declared via bind().
            int slowQueryMs = ((Number) client.config().get(
                    "showcase-database", "slow_query_threshold_ms", 500)).intValue();
            System.out.println("showcase-database.slow_query_threshold_ms = "
                    + slowQueryMs + "  // default used; now registered for visibility");
            assert slowQueryMs == 500;

            ConfigRuntimeSetup.cleanup(client.manage());
            System.out.println("Done!");
        }
    }
}
