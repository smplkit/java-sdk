package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.Config;
import com.smplkit.errors.SmplNotFoundException;

import java.util.List;
import java.util.Map;

/**
 * Smpl Config Management Showcase
 * =================================
 *
 * <p>Demonstrates the management-plane API for configurations, covering:</p>
 * <ul>
 *   <li>Client initialization ({@link SmplClient})</li>
 *   <li>Creating configs with {@code new_()} + {@code save()}</li>
 *   <li>Getting configs by id</li>
 *   <li>Listing all configs</li>
 *   <li>Updating configs via mutation + {@code save()}</li>
 *   <li>Multi-level hierarchy: auth_module &rarr; user_service &rarr; common</li>
 *   <li>Deleting configs by id</li>
 * </ul>
 *
 * <p>Prerequisites:</p>
 * <ul>
 *   <li>A valid smplkit API key, provided via {@code SMPLKIT_API_KEY} env var or {@code ~/.smplkit} config file</li>
 *   <li>The smplkit Config service running and reachable</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigManagementShowcase
 * </pre>
 */
public class ConfigManagementShowcase {

    public static void main(String[] args) throws Exception {
        // ======================================================================
        // 1. SDK INITIALIZATION
        // ======================================================================
        section("1. SDK Initialization");

        try (SmplClient client = SmplClient.builder()
                .environment("production")
                .service("showcase-service")
                .build()) {

            step("SmplClient initialized with environment=production");

            // Clean up any leftover configs from a previous failed run.
            for (String key : new String[]{"auth_module", "user_service"}) {
                try {
                    client.config().delete(key);
                    step("Pre-cleanup: deleted leftover config " + key);
                } catch (SmplNotFoundException ignored) { }
            }

            // ==================================================================
            // 2a. UPDATE THE COMMON CONFIG
            // ==================================================================
            section("2a. Update the Common Config");

            Config common = client.config().get("common");
            step("Fetched common config: id=" + common.getId());

            common.setDescription("Organization-wide shared configuration");
            common.setItems(Map.of(
                    "app_name", Map.of("value", "Acme SaaS Platform"),
                    "support_email", Map.of("value", "support@acme.dev"),
                    "max_retries", Map.of("value", 3),
                    "request_timeout_ms", Map.of("value", 5000),
                    "log_level", Map.of("value", "info"),
                    "feature_analytics", Map.of("value", true)
            ));
            common.save();
            step("Common config base values set (6 items)");

            // ==================================================================
            // 2b. ENVIRONMENT OVERRIDES
            // ==================================================================
            section("2b. Environment Overrides");

            Map<String, Object> prodEnv = new java.util.HashMap<>();
            prodEnv.put("values", Map.of(
                    "max_retries", 5,
                    "request_timeout_ms", 10000,
                    "log_level", "warn"
            ));
            Map<String, Object> stagingEnv = new java.util.HashMap<>();
            stagingEnv.put("values", Map.of(
                    "max_retries", 10,
                    "log_level", "debug"
            ));
            common.setEnvironments(Map.of("production", prodEnv, "staging", stagingEnv));
            common.save();
            step("Production and staging overrides set on common config");

            // ==================================================================
            // 3a. CREATE USER SERVICE CONFIG
            // ==================================================================
            section("3a. Create User Service Config");

            Config userService = client.config().new_("user_service", "User Service", null, common.getId());
            userService.setItems(Map.of(
                    "cache_ttl_seconds", Map.of("value", 300),
                    "enable_signup", Map.of("value", true),
                    "pagination_default_page_size", Map.of("value", 50),
                    "session_timeout_minutes", Map.of("value", 30)
            ));
            userService.save();
            step("Created user_service config: id=" + userService.getId()
                    + ", parent=" + common.getId());

            // ==================================================================
            // 3b. CREATE AUTH MODULE CONFIG (CHILD)
            // ==================================================================
            section("3b. Create Auth Module Config (child of user_service)");

            Config authModule = client.config().new_("auth_module", "Auth Module", null, userService.getId());
            authModule.setItems(Map.of(
                    "mfa_enabled", Map.of("value", false),
                    "token_expiry_minutes", Map.of("value", 15),
                    "max_login_attempts", Map.of("value", 5),
                    "lockout_duration_minutes", Map.of("value", 30),
                    "password_min_length", Map.of("value", 8)
            ));
            authModule.save();
            step("Created auth_module config: id=" + authModule.getId()
                    + ", parent=" + userService.getId());

            // ==================================================================
            // 4a. LIST ALL CONFIGS
            // ==================================================================
            section("4a. List All Configs");

            List<Config> allConfigs = client.config().list();
            step("Total configs in account: " + allConfigs.size());
            for (Config c : allConfigs) {
                step("  " + c.getId() + " - " + c.getName());
            }

            // ==================================================================
            // 4b. GET CONFIG BY KEY
            // ==================================================================
            section("4b. Get Config by ID");

            Config fetchedUserService = client.config().get("user_service");
            step("Fetched by id: id=" + fetchedUserService.getId()
                    + ", name=" + fetchedUserService.getName());

            Config fetchedAuthModule = client.config().get("auth_module");
            step("Fetched by id: id=" + fetchedAuthModule.getId()
                    + ", name=" + fetchedAuthModule.getName());

            // ==================================================================
            // 5. UPDATE A CONFIG
            // ==================================================================
            section("5. Update a Config");

            userService = client.config().get("user_service");
            userService.setDescription("Updated: User service with new pagination settings");
            userService.save();
            step("Updated user_service description: " + userService.getDescription());

            // ==================================================================
            // 6. CLEANUP
            // ==================================================================
            section("6. Cleanup");

            client.config().delete("auth_module");
            step("Deleted auth_module");

            client.config().delete("user_service");
            step("Deleted user_service");

            Config latestCommon = client.config().get("common");
            latestCommon.setDescription("");
            latestCommon.setItems(Map.of());
            latestCommon.setEnvironments(Map.of());
            latestCommon.save();
            step("Common config reset to empty");

        }

        section("ALL DONE");
        System.out.println("  The Config Management showcase completed successfully.");
        System.out.println("  All created resources have been cleaned up.\n");
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
