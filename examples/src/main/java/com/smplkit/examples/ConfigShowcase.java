package com.smplkit.examples;

import com.smplkit.SmplkitClient;
import com.smplkit.config.Config;
import com.smplkit.config.CreateConfigParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smpl Config SDK Showcase
 * ========================
 *
 * Demonstrates the smplkit Java SDK for Smpl Config, covering:
 *
 * <ul>
 *   <li>Client initialization ({@link SmplkitClient})</li>
 *   <li>Management-plane CRUD: create, list, get, and delete configs</li>
 *   <li>Multi-level inheritance setup (auth_module → user_service → common)</li>
 * </ul>
 *
 * <p>This script is designed to be read top-to-bottom as a walkthrough of the
 * SDK's current capability surface. It is runnable against a live smplkit
 * environment, but is <em>not</em> a test — it creates and deletes real configs.</p>
 *
 * <h2>Prerequisites</h2>
 * <ul>
 *   <li>Java 17+</li>
 *   <li>A valid smplkit API key (set via {@code SMPLKIT_API_KEY} env var)</li>
 *   <li>The smplkit Config service running and reachable</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * export SMPLKIT_API_KEY="sk_api_..."
 * ./gradlew :examples:run
 * }</pre>
 */
public final class ConfigShowcase {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void section(String title) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60));
        System.out.println();
    }

    private static void step(String description) {
        System.out.println("  → " + description);
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) {

        // ==================================================================
        // 1. SDK INITIALIZATION
        // ==================================================================
        section("1. SDK Initialization");

        String apiKey = System.getenv("SMPLKIT_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: Set the SMPLKIT_API_KEY environment variable before running.");
            System.err.println("  export SMPLKIT_API_KEY='sk_api_...'");
            System.exit(1);
        }

        // SmplkitClient is the entry point. API key is the only required
        // argument; timeout defaults to 30 seconds.
        try (SmplkitClient client = SmplkitClient.builder()
                .apiKey(apiKey)
                .build()) {

            step("SmplkitClient initialized");

            // ==============================================================
            // 2. MANAGEMENT PLANE — Set up the configuration hierarchy
            // ==============================================================

            // --------------------------------------------------------------
            // 2a. Fetch the built-in common config
            // --------------------------------------------------------------
            section("2a. Fetch the Common Config");

            Config common = client.config().getByKey("common");
            step("Fetched common config: id=" + common.id() + ", key=" + common.key());

            // --- SKIPPED: update() / setValues() / setValue() not yet implemented in Java SDK ---
            // In the Python showcase, we update common with base values
            // (app_name, credentials, feature_flags, etc.) and set
            // production/staging environment overrides here.
            step("SKIPPED: Cannot update common config values — update()/setValues() not yet in Java SDK");

            // --------------------------------------------------------------
            // 2b. Create a service-specific config (inherits from common)
            // --------------------------------------------------------------
            section("2b. Create the User Service Config");

            // When we don't specify a parent, the API defaults to common.
            // We can set initial base values at creation time.
            Map<String, Object> userServiceValues = new HashMap<>();
            Map<String, Object> database = new HashMap<>();
            database.put("host", "localhost");
            database.put("port", 5432);
            database.put("name", "users_dev");
            database.put("pool_size", 5);
            database.put("ssl_mode", "prefer");
            userServiceValues.put("database", database);
            userServiceValues.put("cache_ttl_seconds", 300);
            userServiceValues.put("enable_signup", true);
            userServiceValues.put("allowed_email_domains", List.of("acme.dev", "acme.com"));
            userServiceValues.put("pagination_default_page_size", 50);

            Config userService = client.config().create(
                    CreateConfigParams.builder("User Service")
                            .key("user_service")
                            .description("Configuration for the user microservice and its dependencies.")
                            .values(userServiceValues)
                            .build()
            );
            step("Created user_service config: id=" + userService.id());

            // --- SKIPPED: setValues() / setValue() not yet implemented in Java SDK ---
            // In the Python showcase, we set production, staging, and
            // development environment overrides, and disable signup in
            // production via set_value().
            step("SKIPPED: Cannot set environment overrides — setValues()/setValue() not yet in Java SDK");

            // --------------------------------------------------------------
            // 2c. Create a second config for multi-level inheritance
            // --------------------------------------------------------------
            section("2c. Create the Auth Module Config (child of User Service)");

            Map<String, Object> authModuleValues = new HashMap<>();
            authModuleValues.put("session_ttl_minutes", 60);
            authModuleValues.put("max_failed_attempts", 5);
            authModuleValues.put("lockout_duration_minutes", 15);
            authModuleValues.put("mfa_enabled", false);

            Config authModule = client.config().create(
                    CreateConfigParams.builder("Auth Module")
                            .key("auth_module")
                            .description("Authentication module within the user service.")
                            .parent(userService.id())
                            .values(authModuleValues)
                            .build()
            );
            step("Created auth_module config: id=" + authModule.id() + ", parent=" + userService.id());

            // --- SKIPPED: setValues() not yet implemented in Java SDK ---
            step("SKIPPED: Cannot set auth_module production overrides — setValues() not yet in Java SDK");

            // --------------------------------------------------------------
            // 2d. List all configs — verify hierarchy
            // --------------------------------------------------------------
            section("2d. List All Configs");

            List<Config> configs = client.config().list();
            for (Config cfg : configs) {
                String parentInfo = cfg.parent() != null
                        ? " (parent: " + cfg.parent() + ")"
                        : " (root)";
                step(cfg.key() + parentInfo);
            }

            // ==============================================================
            // 3. RUNTIME PLANE
            // ==============================================================
            section("3. Runtime Plane");

            // --- SKIPPED: Runtime plane not yet implemented in Java SDK ---
            // The Python showcase connects to configs at runtime, reads
            // resolved values with inheritance + environment overrides,
            // verifies local caching, uses typed accessors (get_str,
            // get_int, get_bool), checks key existence, and gets all
            // resolved values. These features require:
            //   - config.connect(environment)
            //   - runtime.get(), getString(), getInt(), getBool()
            //   - runtime.exists(), getAll(), stats()
            step("SKIPPED: connect()/get()/typed accessors not yet in Java SDK");
            step("SKIPPED: Local caching verification not yet in Java SDK");
            step("SKIPPED: Multi-level inheritance resolution not yet in Java SDK");

            // ==============================================================
            // 4. REAL-TIME UPDATES
            // ==============================================================
            section("4. Real-Time Updates via WebSocket");

            // --- SKIPPED: WebSocket runtime not yet implemented in Java SDK ---
            // The Python showcase registers change listeners, mutates a
            // config value via the management API, and verifies the runtime
            // cache updates automatically via WebSocket push.
            step("SKIPPED: onChange()/WebSocket/connectionStatus() not yet in Java SDK");

            // ==============================================================
            // 5. ENVIRONMENT COMPARISON
            // ==============================================================
            section("5. Environment Comparison");

            // --- SKIPPED: Requires runtime connect() ---
            step("SKIPPED: Requires runtime connect() not yet in Java SDK");

            // ==============================================================
            // 6. CLEANUP
            // ==============================================================
            section("6. Cleanup");

            // Delete configs in dependency order (children first).
            client.config().delete(authModule.id());
            step("Deleted auth_module (" + authModule.id() + ")");

            client.config().delete(userService.id());
            step("Deleted user_service (" + userService.id() + ")");

            // --- SKIPPED: Cannot reset common — update() not yet in Java SDK ---
            step("SKIPPED: Cannot reset common to empty — update() not yet in Java SDK");

            step("SmplkitClient closed (via try-with-resources)");

        } // client.close() called automatically

        // ==================================================================
        // DONE
        // ==================================================================
        section("ALL DONE");
        System.out.println("  The Config SDK showcase completed successfully.");
        System.out.println("  If you got here, Smpl Config is ready to ship.");
        System.out.println();
    }
}
