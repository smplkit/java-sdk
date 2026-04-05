package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.ChangeEvent;
import com.smplkit.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Smpl Config Runtime Showcase
 * =============================
 *
 * <p>Demonstrates the runtime (prescriptive) tier of the Config SDK, covering:</p>
 * <ul>
 *   <li>Client initialization and demo hierarchy setup via {@link ConfigRuntimeSetup}</li>
 *   <li>Connecting to the runtime via {@code connect()}</li>
 *   <li>Typed accessors: {@code getString}, {@code getInt}, {@code getBool}</li>
 *   <li>Raw access: {@code getValues}, {@code getValue}</li>
 *   <li>Multi-level inheritance: auth_module &rarr; user_service &rarr; common</li>
 *   <li>Real-time updates via global and key-specific {@code onChange} listeners</li>
 *   <li>Manual refresh via {@code refresh()}</li>
 *   <li>Cleanup of all created resources</li>
 * </ul>
 *
 * <p>Prerequisites:</p>
 * <ul>
 *   <li>A valid smplkit API key (set via {@code SMPLKIT_API_KEY} env var)</li>
 *   <li>The smplkit Config service running and reachable</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 *   export SMPLKIT_API_KEY="sk_api_..."
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigRuntimeShowcase
 * </pre>
 */
public class ConfigRuntimeShowcase {

    public static void main(String[] args) throws Exception {
        // ======================================================================
        // 1. SDK INITIALIZATION + DEMO SETUP
        // ======================================================================
        section("1. SDK Initialization + Demo Setup");

        String apiKey = System.getenv("SMPLKIT_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: Set the SMPLKIT_API_KEY environment variable before running.");
            System.err.println("  export SMPLKIT_API_KEY='sk_api_...'");
            System.exit(1);
        }

        try (SmplClient client = SmplClient.builder()
                .apiKey(apiKey)
                .environment("production")
                .service("config-runtime-showcase")
                .build()) {

            step("SmplClient initialized with environment=production");

            // Create the three-level hierarchy: common -> user_service -> auth_module
            ConfigRuntimeSetup.DemoConfigs demo = ConfigRuntimeSetup.setupDemoConfigs(client);

            // ==================================================================
            // 2. PRESCRIPTIVE ACCESS — Connect once, read everywhere
            // ==================================================================
            section("2. Prescriptive Access");

            client.connect();
            step("client.connect() — all configs loaded and resolved");

            // --- Typed accessors ---
            String appName = client.config().getString("common", "app_name", "Unknown");
            step("common/app_name (string) = " + appName);

            int retries = client.config().getInt("common", "max_retries", 1);
            step("common/max_retries (int) = " + retries);
            // Expected: 5 (production override)

            boolean signup = client.config().getBool("user_service", "enable_signup", true);
            step("user_service/enable_signup (bool) = " + signup);
            // Expected: false (production override)

            String logLevel = client.config().getString("common", "log_level", "debug");
            step("common/log_level (string) = " + logLevel);
            // Expected: "warn" (production override)

            // --- Raw access ---
            Map<String, Object> allValues = client.config().getValues("user_service");
            step("user_service total keys: " + (allValues != null ? allValues.size() : 0));

            Object missing = client.config().getValue("user_service", "nonexistent_item");
            step("nonexistent item = " + missing);

            // ==================================================================
            // 3. MULTI-LEVEL INHERITANCE
            // ==================================================================
            section("3. Multi-Level Inheritance");

            step("Hierarchy: common -> user_service -> auth_module");

            // auth_module inherits from user_service, which inherits from common.
            // Values cascade: auth_module sees its own values, plus user_service's,
            // plus common's, with more-specific configs winning on conflicts.

            boolean mfa = client.config().getBool("auth_module", "mfa_enabled", false);
            step("auth_module/mfa_enabled (bool) = " + mfa);
            // Expected: true (production override on auth_module)

            int tokenExpiry = client.config().getInt("auth_module", "token_expiry_minutes", 30);
            step("auth_module/token_expiry_minutes (int) = " + tokenExpiry);
            // Expected: 10 (production override on auth_module)

            int maxAttempts = client.config().getInt("auth_module", "max_login_attempts", 10);
            step("auth_module/max_login_attempts (int) = " + maxAttempts);
            // Expected: 3 (production override on auth_module)

            // Inherited from user_service
            int cacheTtl = client.config().getInt("auth_module", "cache_ttl_seconds", 0);
            step("auth_module/cache_ttl_seconds (int, inherited from user_service) = " + cacheTtl);
            // Expected: 600 (production override on user_service)

            // Inherited from common (two levels up)
            String inheritedAppName = client.config().getString("auth_module", "app_name", "N/A");
            step("auth_module/app_name (string, inherited from common) = " + inheritedAppName);
            // Expected: "Acme SaaS Platform" (base value from common)

            String inheritedEmail = client.config().getString("auth_module", "support_email", "N/A");
            step("auth_module/support_email (string, inherited from common) = " + inheritedEmail);
            // Expected: "support@acme.dev"

            // All resolved values for auth_module (own + inherited)
            Map<String, Object> authValues = client.config().getValues("auth_module");
            step("auth_module total resolved keys: " + (authValues != null ? authValues.size() : 0));

            // ==================================================================
            // 4. ONCHANGE + REFRESH
            // ==================================================================
            section("4. OnChange + Refresh");

            // Global listener — fires for any config change.
            List<ChangeEvent> changes = new ArrayList<>();
            client.config().onChange(evt -> {
                changes.add(evt);
                System.out.println("    [CHANGE] " + evt.configKey() + "/" + evt.itemKey()
                        + ": " + evt.oldValue() + " \u2192 " + evt.newValue());
            });
            step("Global change listener registered");

            // Key-specific listener — fires only for common/max_retries.
            List<ChangeEvent> retryChanges = new ArrayList<>();
            client.config().onChange(
                    retryChanges::add,
                    "common",
                    "max_retries");
            step("Key-specific listener registered for common/max_retries");

            // Update via management API, then refresh to pick up the change.
            Config latestCommon = client.config().getByKey("common");
            client.config().setValue(latestCommon, "max_retries", 7, "production");
            step("Updated max_retries to 7 via management API");

            client.config().refresh();
            step("Manual refresh completed");

            int newRetries = client.config().getInt("common", "max_retries", 1);
            step("max_retries after refresh = " + newRetries);
            // Expected: 7

            step("Global changes: " + changes.size() + ", retry-specific: " + retryChanges.size());

            // ==================================================================
            // 5. CLEANUP
            // ==================================================================
            ConfigRuntimeSetup.teardownDemoConfigs(client, demo);

        } // SmplClient.close() is called here

        // ======================================================================
        // DONE
        // ======================================================================
        section("ALL DONE");
        System.out.println("  The Config Runtime showcase completed successfully.");
        System.out.println("  All created resources have been cleaned up.\n");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void section(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60) + "\n");
    }

    private static void step(String description) {
        System.out.println("  \u2192 " + description);
    }
}
