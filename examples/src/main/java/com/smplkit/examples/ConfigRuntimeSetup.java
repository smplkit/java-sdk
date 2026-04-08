package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.Config;

import java.util.Map;

/**
 * Smpl Config Runtime Setup
 * ==========================
 *
 * <p>Utility class that creates a realistic three-level configuration hierarchy
 * for use by the runtime showcase. Provides setup and teardown methods so the
 * runtime showcase can focus on prescriptive access, inheritance, change
 * listeners, and refresh without duplicating management-plane boilerplate.</p>
 *
 * <p>Hierarchy created:</p>
 * <pre>
 *   common (built-in, updated with base values + production overrides)
 *     +-- user_service (service config, inherits from common)
 *           +-- auth_module (module config, inherits from user_service)
 * </pre>
 */
public class ConfigRuntimeSetup {

    /**
     * Holds references to the demo configs created during setup.
     */
    public static class DemoConfigs {
        private final String commonKey;
        private final String userServiceKey;
        private final String authModuleKey;

        public DemoConfigs(String commonKey, String userServiceKey, String authModuleKey) {
            this.commonKey = commonKey;
            this.userServiceKey = userServiceKey;
            this.authModuleKey = authModuleKey;
        }

        public String commonKey() { return commonKey; }
        public String userServiceKey() { return userServiceKey; }
        public String authModuleKey() { return authModuleKey; }
    }

    /**
     * Creates the full demo configuration hierarchy using the new API.
     */
    public static DemoConfigs setupDemoConfigs(SmplClient client) throws Exception {
        section("Setup: Creating Demo Config Hierarchy");

        // 1. Update the built-in common config
        Config common = client.config().get("common");
        step("Fetched common config: id=" + common.getId() + ", key=" + common.getKey());

        common.setDescription("Organization-wide shared configuration");
        common.setItems(Map.of(
                "app_name", Map.of("value", "Acme SaaS Platform"),
                "support_email", Map.of("value", "support@acme.dev"),
                "max_retries", Map.of("value", 3),
                "request_timeout_ms", Map.of("value", 5000),
                "log_level", Map.of("value", "info")
        ));

        Map<String, Object> prodEnv = new java.util.HashMap<>();
        prodEnv.put("values", Map.of(
                "max_retries", 5,
                "request_timeout_ms", 10000,
                "log_level", "warn"
        ));
        common.setEnvironments(Map.of("production", prodEnv));
        common.save();
        step("Common config updated with base values + production overrides");

        // 2. Create user_service config (inherits from common)
        Config userService = client.config().new_("user_service", "User Service", null, common.getId());
        userService.setItems(Map.of(
                "cache_ttl_seconds", Map.of("value", 300),
                "enable_signup", Map.of("value", true),
                "pagination_default_page_size", Map.of("value", 50),
                "session_timeout_minutes", Map.of("value", 30)
        ));

        Map<String, Object> usProdEnv = new java.util.HashMap<>();
        usProdEnv.put("values", Map.of(
                "cache_ttl_seconds", 600,
                "enable_signup", false,
                "session_timeout_minutes", 60
        ));
        userService.setEnvironments(Map.of("production", usProdEnv));
        userService.save();
        step("Created user_service config: id=" + userService.getId());

        // 3. Create auth_module config (inherits from user_service)
        Config authModule = client.config().new_("auth_module", "Auth Module", null, userService.getId());
        authModule.setItems(Map.of(
                "mfa_enabled", Map.of("value", false),
                "token_expiry_minutes", Map.of("value", 15),
                "max_login_attempts", Map.of("value", 5),
                "lockout_duration_minutes", Map.of("value", 30)
        ));

        Map<String, Object> amProdEnv = new java.util.HashMap<>();
        amProdEnv.put("values", Map.of(
                "mfa_enabled", true,
                "token_expiry_minutes", 10,
                "max_login_attempts", 3
        ));
        authModule.setEnvironments(Map.of("production", amProdEnv));
        authModule.save();
        step("Created auth_module config: id=" + authModule.getId());

        step("Hierarchy: common -> user_service -> auth_module");
        return new DemoConfigs("common", "user_service", "auth_module");
    }

    /**
     * Tears down the demo configs.
     */
    public static void teardownDemoConfigs(SmplClient client, DemoConfigs demo) throws Exception {
        section("Teardown: Cleaning Up Demo Configs");

        client.config().delete(demo.authModuleKey());
        step("Deleted auth_module");

        client.config().delete(demo.userServiceKey());
        step("Deleted user_service");

        Config latestCommon = client.config().get("common");
        latestCommon.setDescription("");
        latestCommon.setItems(Map.of());
        latestCommon.setEnvironments(Map.of());
        latestCommon.save();
        step("Common config reset to empty");
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
