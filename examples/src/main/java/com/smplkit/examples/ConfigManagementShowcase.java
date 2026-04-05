package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.Config;
import com.smplkit.config.CreateConfigParams;
import com.smplkit.config.UpdateConfigParams;

import java.util.List;
import java.util.Map;

/**
 * Smpl Config Management Showcase
 * =================================
 *
 * <p>Demonstrates the management-plane API for configurations, covering:</p>
 * <ul>
 *   <li>Client initialization ({@link SmplClient})</li>
 *   <li>Updating the built-in common config with base values</li>
 *   <li>Environment-specific overrides via {@code setValues} / {@code setValue}</li>
 *   <li>Creating child configs with parent inheritance</li>
 *   <li>Multi-level hierarchy: auth_module &rarr; user_service &rarr; common</li>
 *   <li>Listing all configs and fetching by ID</li>
 *   <li>Updating an existing config</li>
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
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigManagementShowcase
 * </pre>
 */
public class ConfigManagementShowcase {

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
            // 2a. UPDATE THE COMMON CONFIG
            // ==================================================================
            section("2a. Update the Common Config");

            Config common = client.config().getByKey("common");
            step("Fetched common config: id=" + common.id() + ", key=" + common.key());

            common = client.config().update(common, UpdateConfigParams.builder()
                    .description("Organization-wide shared configuration")
                    .values(Map.of(
                            "app_name", "Acme SaaS Platform",
                            "support_email", "support@acme.dev",
                            "max_retries", 3,
                            "request_timeout_ms", 5000,
                            "log_level", "info",
                            "feature_analytics", true
                    ))
                    .build());
            step("Common config base values set (6 items)");
            step("  app_name = Acme SaaS Platform");
            step("  support_email = support@acme.dev");
            step("  max_retries = 3");
            step("  request_timeout_ms = 5000");
            step("  log_level = info");
            step("  feature_analytics = true");

            // ==================================================================
            // 2b. ENVIRONMENT OVERRIDES
            // ==================================================================
            section("2b. Environment Overrides");

            common = client.config().setValues(common, Map.of(
                    "max_retries", 5,
                    "request_timeout_ms", 10000,
                    "log_level", "warn"
            ), "production");
            step("Production overrides set on common config");
            step("  max_retries = 5 (was 3)");
            step("  request_timeout_ms = 10000 (was 5000)");
            step("  log_level = warn (was info)");

            common = client.config().setValues(common, Map.of(
                    "max_retries", 10,
                    "log_level", "debug"
            ), "staging");
            step("Staging overrides set on common config");
            step("  max_retries = 10 (was 3)");
            step("  log_level = debug (was info)");

            // ==================================================================
            // 3a. CREATE USER SERVICE CONFIG
            // ==================================================================
            section("3a. Create User Service Config");

            Config userService = client.config().create(CreateConfigParams.builder("User Service")
                    .key("user_service")
                    .parent(common.id())
                    .values(Map.of(
                            "cache_ttl_seconds", 300,
                            "enable_signup", true,
                            "pagination_default_page_size", 50,
                            "session_timeout_minutes", 30
                    ))
                    .build());
            step("Created user_service config: id=" + userService.id()
                    + ", parent=" + common.id());
            step("  Inherits from: common");

            userService = client.config().setValues(userService, Map.of(
                    "cache_ttl_seconds", 600,
                    "enable_signup", false,
                    "session_timeout_minutes", 60
            ), "production");
            step("User service production overrides set");

            // ==================================================================
            // 3b. CREATE AUTH MODULE CONFIG (CHILD)
            // ==================================================================
            section("3b. Create Auth Module Config (child of user_service)");

            Config authModule = client.config().create(CreateConfigParams.builder("Auth Module")
                    .key("auth_module")
                    .parent(userService.id())
                    .values(Map.of(
                            "mfa_enabled", false,
                            "token_expiry_minutes", 15,
                            "max_login_attempts", 5,
                            "lockout_duration_minutes", 30,
                            "password_min_length", 8
                    ))
                    .build());
            step("Created auth_module config: id=" + authModule.id()
                    + ", parent=" + userService.id());
            step("  Inherits from: user_service -> common");

            authModule = client.config().setValues(authModule, Map.of(
                    "mfa_enabled", true,
                    "token_expiry_minutes", 10,
                    "max_login_attempts", 3
            ), "production");
            step("Auth module production overrides set");

            // ==================================================================
            // 4a. LIST ALL CONFIGS
            // ==================================================================
            section("4a. List All Configs");

            List<Config> allConfigs = client.config().list();
            step("Total configs in account: " + allConfigs.size());
            for (Config c : allConfigs) {
                step("  " + c.key() + " (id=" + c.id() + ") — " + c.name());
            }

            // ==================================================================
            // 4b. GET CONFIG BY ID
            // ==================================================================
            section("4b. Get Config by ID");

            Config fetchedUserService = client.config().get(userService.id());
            step("Fetched by ID: key=" + fetchedUserService.key()
                    + ", name=" + fetchedUserService.name());

            Config fetchedAuthModule = client.config().get(authModule.id());
            step("Fetched by ID: key=" + fetchedAuthModule.key()
                    + ", name=" + fetchedAuthModule.name());

            // ==================================================================
            // 5. UPDATE A CONFIG
            // ==================================================================
            section("5. Update a Config");

            userService = client.config().update(userService, UpdateConfigParams.builder()
                    .description("Updated: User service with new pagination settings")
                    .build());
            step("Updated user_service description: " + userService.description());

            authModule = client.config().setValue(authModule, "password_min_length", 12, null);
            step("Updated auth_module/password_min_length base value to 12");

            authModule = client.config().setValue(authModule, "lockout_duration_minutes", 60, "production");
            step("Updated auth_module/lockout_duration_minutes production override to 60");

            // ==================================================================
            // 6. CLEANUP
            // ==================================================================
            section("6. Cleanup");

            // Delete children first (reverse order of creation).
            client.config().delete(authModule.id());
            step("Deleted auth_module (" + authModule.id() + ")");

            client.config().delete(userService.id());
            step("Deleted user_service (" + userService.id() + ")");

            // Reset common config to empty.
            Config latestCommon = client.config().getByKey("common");
            client.config().update(latestCommon, UpdateConfigParams.builder()
                    .description("")
                    .values(Map.of())
                    .environments(Map.of())
                    .build());
            step("Common config reset to empty");

        } // SmplClient.close() is called here

        // ======================================================================
        // DONE
        // ======================================================================
        section("ALL DONE");
        System.out.println("  The Config Management showcase completed successfully.");
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
