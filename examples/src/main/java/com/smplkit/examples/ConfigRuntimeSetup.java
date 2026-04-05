package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.Config;
import com.smplkit.config.CreateConfigParams;
import com.smplkit.config.UpdateConfigParams;

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
 *
 * <p>This class is not intended to be run standalone. It is called by
 * {@link ConfigRuntimeShowcase}.</p>
 */
public class ConfigRuntimeSetup {

    /**
     * Holds references to the demo configs created during setup.
     */
    public static class DemoConfigs {
        private final Config common;
        private final Config userService;
        private final Config authModule;

        public DemoConfigs(Config common, Config userService, Config authModule) {
            this.common = common;
            this.userService = userService;
            this.authModule = authModule;
        }

        public Config common() { return common; }
        public Config userService() { return userService; }
        public Config authModule() { return authModule; }
    }

    /**
     * Creates the full demo configuration hierarchy.
     *
     * <ol>
     *   <li>Updates the built-in {@code common} config with base values and
     *       production environment overrides.</li>
     *   <li>Creates a {@code user_service} config that inherits from common,
     *       with its own base values and production overrides.</li>
     *   <li>Creates an {@code auth_module} config that inherits from
     *       user_service, adding authentication-specific values and production
     *       overrides.</li>
     * </ol>
     *
     * @param client an initialized (but not yet connected) SmplClient
     * @return a {@link DemoConfigs} holding references to all three configs
     * @throws Exception if any management-plane call fails
     */
    public static DemoConfigs setupDemoConfigs(SmplClient client) throws Exception {
        section("Setup: Creating Demo Config Hierarchy");

        // ------------------------------------------------------------------
        // 1. Update the built-in common config
        // ------------------------------------------------------------------
        Config common = client.config().getByKey("common");
        step("Fetched common config: id=" + common.id() + ", key=" + common.key());

        common = client.config().update(common, UpdateConfigParams.builder()
                .description("Organization-wide shared configuration")
                .values(Map.of(
                        "app_name", "Acme SaaS Platform",
                        "support_email", "support@acme.dev",
                        "max_retries", 3,
                        "request_timeout_ms", 5000,
                        "log_level", "info"
                ))
                .build());
        step("Common config base values set (5 items)");

        common = client.config().setValues(common, Map.of(
                "max_retries", 5,
                "request_timeout_ms", 10000,
                "log_level", "warn"
        ), "production");
        step("Common config production overrides set");

        // ------------------------------------------------------------------
        // 2. Create user_service config (inherits from common)
        // ------------------------------------------------------------------
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
        step("Created user_service config: id=" + userService.id());

        userService = client.config().setValues(userService, Map.of(
                "cache_ttl_seconds", 600,
                "enable_signup", false,
                "session_timeout_minutes", 60
        ), "production");
        step("User service production overrides set");

        // ------------------------------------------------------------------
        // 3. Create auth_module config (inherits from user_service)
        // ------------------------------------------------------------------
        Config authModule = client.config().create(CreateConfigParams.builder("Auth Module")
                .key("auth_module")
                .parent(userService.id())
                .values(Map.of(
                        "mfa_enabled", false,
                        "token_expiry_minutes", 15,
                        "max_login_attempts", 5,
                        "lockout_duration_minutes", 30
                ))
                .build());
        step("Created auth_module config: id=" + authModule.id());

        authModule = client.config().setValues(authModule, Map.of(
                "mfa_enabled", true,
                "token_expiry_minutes", 10,
                "max_login_attempts", 3
        ), "production");
        step("Auth module production overrides set");

        step("Hierarchy: common -> user_service -> auth_module");
        return new DemoConfigs(common, userService, authModule);
    }

    /**
     * Tears down the demo configs created by {@link #setupDemoConfigs}.
     *
     * <p>Deletes child configs in reverse order (auth_module, user_service)
     * and resets the common config to empty.</p>
     *
     * @param client the SmplClient used during setup
     * @param demo   the {@link DemoConfigs} returned by setup
     * @throws Exception if any management-plane call fails
     */
    public static void teardownDemoConfigs(SmplClient client, DemoConfigs demo) throws Exception {
        section("Teardown: Cleaning Up Demo Configs");

        client.config().delete(demo.authModule().id());
        step("Deleted auth_module (" + demo.authModule().id() + ")");

        client.config().delete(demo.userService().id());
        step("Deleted user_service (" + demo.userService().id() + ")");

        Config latestCommon = client.config().getByKey("common");
        client.config().update(latestCommon, UpdateConfigParams.builder()
                .description("")
                .values(Map.of())
                .environments(Map.of())
                .build());
        step("Common config reset to empty");
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
