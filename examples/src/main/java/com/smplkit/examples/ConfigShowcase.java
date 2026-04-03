package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.ChangeEvent;
import com.smplkit.config.Config;
import com.smplkit.config.CreateConfigParams;
import com.smplkit.config.UpdateConfigParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Smpl Config SDK Showcase
 * ========================
 *
 * <p>Demonstrates the prescriptive programming model:</p>
 * <pre>
 *   SmplClient client = SmplClient.builder()
 *       .apiKey("sk_api_...")
 *       .environment("production")
 *       .build();
 *   client.connect();
 *   String value = client.config().getString("my_config", "key", "default");
 * </pre>
 *
 * <ul>
 *   <li>Client initialization ({@link SmplClient})</li>
 *   <li>Management-plane CRUD: create, update, list, and delete configs</li>
 *   <li>Environment-specific overrides (setValues / setValue)</li>
 *   <li>Multi-level inheritance: auth_module &rarr; user_service &rarr; common</li>
 *   <li>Prescriptive value access: getString, getInt, getBool</li>
 *   <li>Real-time updates via OnChange listeners</li>
 *   <li>Manual refresh</li>
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
 *   ./gradlew :examples:run
 * </pre>
 */
public class ConfigShowcase {

    public static void main(String[] args) throws Exception {
        // ======================================================================
        // 1. SDK INITIALIZATION
        // ======================================================================
        section("1. SDK Initialization");

        String apiKey = System.getenv("SMPLKIT_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: Set the SMPLKIT_API_KEY environment variable before running.");
            System.err.println("  export SMPLKIT_API_KEY='sk_api_...'");
            System.exit(1);
        }

        try (SmplClient client = SmplClient.builder()
                .apiKey(apiKey)
                .environment("production")
                .build()) {

            step("SmplClient initialized with environment=production");

            // ==================================================================
            // 2. MANAGEMENT PLANE — Set up the configuration hierarchy
            // ==================================================================

            // ------------------------------------------------------------------
            // 2a. Update the built-in common config
            // ------------------------------------------------------------------
            section("2a. Update the Common Config");

            Config common = client.config().getByKey("common");
            step("Fetched common config: id=" + common.id() + ", key=" + common.key());

            common = client.config().update(common, UpdateConfigParams.builder()
                    .description("Organization-wide shared configuration")
                    .values(Map.of(
                            "app_name", "Acme SaaS Platform",
                            "support_email", "support@acme.dev",
                            "max_retries", 3,
                            "request_timeout_ms", 5000
                    ))
                    .build());
            step("Common config base values set");

            common = client.config().setValues(common, Map.of(
                    "max_retries", 5,
                    "request_timeout_ms", 10000
            ), "production");
            step("Common config production overrides set");

            // ------------------------------------------------------------------
            // 2b. Create a service-specific config (inherits from common)
            // ------------------------------------------------------------------
            section("2b. Create the User Service Config");

            Config userService = client.config().create(CreateConfigParams.builder("User Service")
                    .key("user_service")
                    .parent(common.id())
                    .values(Map.of(
                            "cache_ttl_seconds", 300,
                            "enable_signup", true,
                            "pagination_default_page_size", 50
                    ))
                    .build());
            step("Created user_service config: id=" + userService.id());

            userService = client.config().setValues(userService, Map.of(
                    "cache_ttl_seconds", 600,
                    "enable_signup", false
            ), "production");
            step("User service production overrides set");

            // ==================================================================
            // 3. PRESCRIPTIVE ACCESS — Connect once, read everywhere
            // ==================================================================

            section("3. Prescriptive Access");

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

            // --- Raw access ---
            Map<String, Object> allValues = client.config().getValues("user_service");
            step("user_service total keys: " + (allValues != null ? allValues.size() : 0));

            Object missing = client.config().getValue("user_service", "nonexistent_item");
            step("nonexistent item = " + missing);

            // ==================================================================
            // 4. REAL-TIME UPDATES — OnChange + refresh()
            // ==================================================================

            section("4. OnChange + Refresh");

            List<ChangeEvent> changes = new ArrayList<>();
            client.config().onChange(evt -> {
                changes.add(evt);
                System.out.println("    [CHANGE] " + evt.configKey() + "/" + evt.itemKey()
                        + ": " + evt.oldValue() + " → " + evt.newValue());
            });
            step("Global change listener registered");

            List<ChangeEvent> retryChanges = new ArrayList<>();
            client.config().onChange(
                    retryChanges::add,
                    "common",
                    "max_retries");
            step("Key-specific listener registered for common/max_retries");

            // Update via management API, then refresh
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
            section("5. Cleanup");

            client.config().delete(userService.id());
            step("Deleted user_service (" + userService.id() + ")");

            Config latestCommonForReset = client.config().getByKey("common");
            client.config().update(latestCommonForReset, UpdateConfigParams.builder()
                    .description("")
                    .values(Map.of())
                    .environments(Map.of())
                    .build());
            step("Common config reset to empty");

        } // SmplClient.close() is called here

        section("ALL DONE");
        System.out.println("  The Config SDK showcase completed successfully.\n");
    }

    private static void section(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60) + "\n");
    }

    private static void step(String description) {
        System.out.println("  → " + description);
    }
}
