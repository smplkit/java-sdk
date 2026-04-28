package com.smplkit.examples;

import com.smplkit.Context;
import com.smplkit.SmplClient;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.management.ContextEntity;
import com.smplkit.management.ContextType;
import com.smplkit.management.Environment;
import com.smplkit.management.EnvironmentClassification;

import java.util.ArrayList;
import java.util.List;

/**
 * Smpl SDK Showcase — Management API
 * ====================================
 *
 * <p>Demonstrates {@code client.management.*} — the management plane for app-service-owned
 * resources that are not tied to a specific microservice: environments, contexts, context
 * types, and per-account settings.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.ManagementShowcase
 * </pre>
 */
public class ManagementShowcase {

    public static void main(String[] args) throws Exception {
        section("1. SDK Initialization");

        try (SmplClient client = SmplClient.builder()
                .environment("production")
                .service("management-showcase")
                .build()) {

            step("SmplClient initialized");

            // Best-effort cleanup of leftovers from previous runs.
            for (String envId : List.of("preview_acme")) {
                try { client.management().environments.delete(envId); } catch (Exception ignored) {}
            }
            for (String ctId : List.of("user", "account", "device")) {
                try { client.management().contextTypes.delete(ctId); } catch (Exception ignored) {}
            }

            // ==================================================================
            // 2. ENVIRONMENTS
            // ==================================================================
            section("2a. List built-in environments");

            for (Environment e : client.management().environments.list()) {
                step("id=" + e.getId() + " name=" + e.getName()
                        + " classification=" + e.getClassification().getValue());
            }

            section("2b. Create a new environment");

            Environment preview = client.management().environments.new_(
                    "preview_acme", "Preview — Acme branch",
                    "#8b5cf6", EnvironmentClassification.STANDARD);
            preview.save();
            step("Created: id=" + preview.getId()
                    + " classification=" + preview.getClassification().getValue());

            section("2c. Update an environment in place");

            Environment prod = client.management().environments.get("production");
            prod.setColor("#ef4444");
            prod.save();
            step("Updated: id=" + prod.getId() + " color=" + prod.getColor());

            // ==================================================================
            // 3. CONTEXT TYPES
            // ==================================================================
            section("3a. Create context types");

            ContextType userCt = client.management().contextTypes.new_("user", "User", null);
            for (String attr : List.of("plan", "region", "beta_tester", "signup_date", "account_age_days")) {
                userCt.addAttribute(attr);
            }
            userCt.save();
            step("user: attributes=" + new ArrayList<>(userCt.getAttributes().keySet()));

            ContextType accountCt = client.management().contextTypes.new_("account", "Account", null);
            for (String attr : List.of("tier", "industry", "region", "employee_count", "annual_revenue")) {
                accountCt.addAttribute(attr);
            }
            accountCt.save();

            ContextType deviceCt = client.management().contextTypes.new_("device", "Device", null);
            for (String attr : List.of("os", "version", "type")) {
                deviceCt.addAttribute(attr);
            }
            deviceCt.save();
            step("account, device created");

            section("3b. List + mutate an existing context type");

            for (ContextType t : client.management().contextTypes.list()) {
                step("id=" + t.getId() + " name=" + t.getName());
            }

            ContextType existing = client.management().contextTypes.get("user");
            existing.addAttribute("lifetime_value");
            existing.removeAttribute("account_age_days");
            existing.save();
            step("user attributes now: " + new ArrayList<>(existing.getAttributes().keySet()));

            // ==================================================================
            // 4. CONTEXTS
            // ==================================================================
            section("4a. Register contexts (immediate flush)");

            client.management().contexts.register(
                    List.of(
                            new Context("user", "usr_a1b2c3", java.util.Map.of("plan", "free", "region", "us")),
                            new Context("user", "usr_d4e5f6", java.util.Map.of("plan", "enterprise", "region", "eu")),
                            new Context("account", "acct_acme_inc", java.util.Map.of("tier", "enterprise", "industry", "retail"))
                    ),
                    true
            );
            step("3 contexts registered + flushed");

            section("4b. List contexts of a single type");

            for (ContextEntity c : client.management().contexts.list("user")) {
                step("  type=" + c.getType() + " key=" + c.getKey() + " attributes=" + c.getAttributes());
            }

            section("4c. Get + delete by composite id (or by (type, key))");

            ContextEntity one = client.management().contexts.get("user:usr_a1b2c3");
            step("got: " + one.getType() + ":" + one.getKey());

            ContextEntity same = client.management().contexts.get("user", "usr_a1b2c3");
            step("got via (type, key): " + same.getType() + ":" + same.getKey());

            client.management().contexts.delete("user:usr_a1b2c3");
            step("deleted user:usr_a1b2c3");

            // ==================================================================
            // 5. ACCOUNT SETTINGS
            // ==================================================================
            section("5a. Read settings");

            var settings = client.management().accountSettings.get();
            step("environment_order=" + settings.getEnvironmentOrder());
            step("raw=" + settings.getRaw());

            section("5b. Mutate + save (active record)");

            settings.setEnvironmentOrder(List.of("production", "staging", "development"));
            settings.save();
            step("saved: environment_order=" + settings.getEnvironmentOrder());

            // ==================================================================
            // 6. CLEANUP
            // ==================================================================
            section("6. Cleanup");

            for (ContextEntity c : client.management().contexts.list("user")) {
                client.management().contexts.delete(c.getType(), c.getKey());
            }
            for (ContextEntity c : client.management().contexts.list("account")) {
                client.management().contexts.delete(c.getType(), c.getKey());
            }

            for (String ctId : List.of("user", "account", "device")) {
                try { client.management().contextTypes.delete(ctId); } catch (SmplNotFoundException ignored) {}
            }

            try { client.management().environments.delete("preview_acme"); } catch (SmplNotFoundException ignored) {}
        }

        section("ALL DONE");
        System.out.println("  The Management showcase completed successfully.");
    }

    private static void section(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60) + "\n");
    }

    private static void step(String description) {
        System.out.println("  -> " + description);
    }
}
