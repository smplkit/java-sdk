package com.smplkit.examples;

import com.smplkit.SmplkitClient;
import com.smplkit.config.Config;
import com.smplkit.config.ConfigRuntime;
import com.smplkit.config.ConfigStats;
import com.smplkit.config.CreateConfigParams;
import com.smplkit.config.UpdateConfigParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Smpl Config SDK Showcase
 * ========================
 *
 * <p>Demonstrates the smplkit Java SDK for Smpl Config, covering:</p>
 * <ul>
 *   <li>Client initialization ({@link SmplkitClient})</li>
 *   <li>Management-plane CRUD: create, update, list, and delete configs</li>
 *   <li>Environment-specific overrides and multi-level inheritance</li>
 *   <li>Runtime value resolution: connect(), get(), typed accessors</li>
 *   <li>Real-time updates via WebSocket and change listeners</li>
 *   <li>Manual refresh and cache diagnostics</li>
 *   <li>AutoCloseable pattern for automatic cleanup</li>
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

        // SmplkitClient is the entry point. API key is the only required argument.
        // It implements AutoCloseable for try-with-resources.
        try (SmplkitClient client = SmplkitClient.builder()
                .apiKey(apiKey)
                .build()) {

            step("SmplkitClient initialized");

            // ==================================================================
            // 2. MANAGEMENT PLANE — Set up the configuration hierarchy
            // ==================================================================
            //
            // This section uses the management API to create and populate configs.
            // In real life, a customer might do this via the console UI, Terraform,
            // or a setup script. The SDK supports all of it programmatically.
            // ==================================================================

            // ------------------------------------------------------------------
            // 2a. Update the built-in common config
            // ------------------------------------------------------------------
            section("2a. Update the Common Config");

            // Every account has a 'common' config auto-created at provisioning.
            // It serves as the default parent for all other configs. Let's populate
            // it with shared baseline values that every service in our org needs.
            Config common = client.config().getByKey("common");
            step("Fetched common config: id=" + common.id() + ", key=" + common.key());

            // Set base values — these apply to ALL environments by default.
            common = client.config().update(common, UpdateConfigParams.builder()
                    .description("Organization-wide shared configuration")
                    .values(Map.of(
                            "app_name", "Acme SaaS Platform",
                            "support_email", "support@acme.dev",
                            "max_retries", 3,
                            "request_timeout_ms", 5000,
                            "pagination_default_page_size", 25,
                            "credentials", Map.of(
                                    "oauth_provider", "https://auth.acme.dev",
                                    "client_id", "acme_default_client",
                                    "scopes", List.of("read")
                            ),
                            "feature_flags", Map.of(
                                    "provider", "smplkit",
                                    "endpoint", "https://flags.smplkit.com",
                                    "refresh_interval_seconds", 30
                            )
                    ))
                    .build());
            step("Common config base values set");

            // Override specific values for production — these flow through to every
            // config that inherits from common, unless overridden further down.
            common = client.config().setValues(common, Map.of(
                    "max_retries", 5,
                    "request_timeout_ms", 10000,
                    "credentials", Map.of(
                            "scopes", List.of("read", "write", "admin")
                    )
            ), "production");
            step("Common config production overrides set");

            // Staging gets its own tweaks.
            common = client.config().setValues(common, Map.of(
                    "max_retries", 2,
                    "credentials", Map.of(
                            "scopes", List.of("read", "write")
                    )
            ), "staging");
            step("Common config staging overrides set");

            // ------------------------------------------------------------------
            // 2b. Create a service-specific config (inherits from common)
            // ------------------------------------------------------------------
            section("2b. Create the User Service Config");

            // When we don't specify a parent, the API defaults to common.
            // This config adds service-specific keys and overrides a few common ones.
            Config userService = client.config().create(CreateConfigParams.builder("User Service")
                    .key("user_service")
                    .description("Configuration for the user microservice and its dependencies.")
                    .parent(common.id())
                    .values(Map.of(
                            "database", Map.of(
                                    "host", "localhost",
                                    "port", 5432,
                                    "name", "users_dev",
                                    "pool_size", 5,
                                    "ssl_mode", "prefer"
                            ),
                            "cache_ttl_seconds", 300,
                            "enable_signup", true,
                            "allowed_email_domains", List.of("acme.dev", "acme.com"),
                            // Override the common pagination default for this service
                            "pagination_default_page_size", 50
                    ))
                    .build());
            step("Created user_service config: id=" + userService.id());

            // Production overrides for the user service.
            userService = client.config().setValues(userService, Map.of(
                    "database", Map.of(
                            "host", "prod-users-rds.internal.acme.dev",
                            "name", "users_prod",
                            "pool_size", 20,
                            "ssl_mode", "require"
                    ),
                    "cache_ttl_seconds", 600
            ), "production");
            step("User service production overrides set");

            // Staging overrides.
            userService = client.config().setValues(userService, Map.of(
                    "database", Map.of(
                            "host", "staging-users-rds.internal.acme.dev",
                            "name", "users_staging",
                            "pool_size", 10
                    )
            ), "staging");
            step("User service staging overrides set");

            // Add keys that only exist in the development environment.
            userService = client.config().setValues(userService, Map.of(
                    "debug_sql", true,
                    "seed_test_data", true
            ), "development");
            step("User service development-only keys set");

            // Set a single value using the convenience method.
            userService = client.config().setValue(userService, "enable_signup", false, "production");
            step("Disabled signup in production via setValue");

            // ------------------------------------------------------------------
            // 2c. Create a second config to show multi-level inheritance
            // ------------------------------------------------------------------
            section("2c. Create the Auth Module Config (child of User Service)");

            // This config's parent is userService (not common), demonstrating
            // multi-level inheritance: auth_module → user_service → common.
            Config authModule = client.config().create(CreateConfigParams.builder("Auth Module")
                    .key("auth_module")
                    .description("Authentication module within the user service.")
                    .parent(userService.id())
                    .values(Map.of(
                            "session_ttl_minutes", 60,
                            "max_failed_attempts", 5,
                            "lockout_duration_minutes", 15,
                            "mfa_enabled", false
                    ))
                    .build());
            step("Created auth_module config: id=" + authModule.id() + ", parent=" + userService.id());

            authModule = client.config().setValues(authModule, Map.of(
                    "session_ttl_minutes", 30,
                    "mfa_enabled", true,
                    "max_failed_attempts", 3
            ), "production");
            step("Auth module production overrides set");

            // ------------------------------------------------------------------
            // 2d. List all configs — verify hierarchy
            // ------------------------------------------------------------------
            section("2d. List All Configs");

            List<Config> configs = client.config().list();
            for (Config cfg : configs) {
                String parentInfo = cfg.parent() != null
                        ? " (parent: " + cfg.parent() + ")"
                        : " (root)";
                step(cfg.key() + parentInfo);
            }

            // ==================================================================
            // 3. RUNTIME PLANE — Resolve configuration in a running application
            // ==================================================================
            //
            // This is the heart of the SDK experience. A customer's application
            // connects to a config for a specific environment, and the SDK:
            //
            //   - Eagerly fetches the config and its entire parent chain
            //   - Resolves values via deep merge (inheritance + env overrides)
            //   - Caches everything in-process — get() is a local map read
            //   - Maintains a WebSocket for real-time server-pushed updates
            //   - Notifies registered listeners when values change
            //
            // get() and all value-access methods are SYNCHRONOUS. They never
            // touch the network.
            //
            // ==================================================================

            // ------------------------------------------------------------------
            // 3a. Connect to a config for runtime use
            // ------------------------------------------------------------------
            section("3a. Connect to Runtime Config");

            // connect() eagerly fetches the config and its full parent chain,
            // resolves all values for the given environment, and establishes
            // a WebSocket connection for real-time updates. When it returns,
            // the cache is fully populated and ready.
            ConfigRuntime runtime = client.config().connect(userService, "production");
            step("Runtime config connected and fully loaded");

            // ------------------------------------------------------------------
            // 3b. Read resolved values — all synchronous, all from local cache
            // ------------------------------------------------------------------
            section("3b. Read Resolved Values");

            Object dbConfig = runtime.get("database");
            step("database = " + dbConfig);
            // Expected (deep-merged): user_service prod override + user_service base
            // {host=prod-users-rds.internal.acme.dev, port=5432, name=users_prod,
            //  pool_size=20, ssl_mode=require}

            Object retries = runtime.get("max_retries");
            step("max_retries = " + retries);
            // Expected: 5 (from common's production override — inherited through)

            Object creds = runtime.get("credentials");
            step("credentials = " + creds);

            Object cacheTtl = runtime.get("cache_ttl_seconds");
            step("cache_ttl_seconds = " + cacheTtl);
            // Expected: 600 (user_service production override)

            Object pageSize = runtime.get("pagination_default_page_size");
            step("pagination_default_page_size = " + pageSize);
            // Expected: 50 (user_service base overrides common's 25)

            Object support = runtime.get("support_email");
            step("support_email = " + support);
            // Expected: "support@acme.dev" (inherited all the way from common base)

            Object missingVal = runtime.get("this_key_does_not_exist");
            step("nonexistent key = " + missingVal);
            // Expected: null

            Object withDefault = runtime.get("this_key_does_not_exist", "fallback");
            step("nonexistent key with default = " + withDefault);
            // Expected: "fallback"

            // Typed convenience accessors for common JSON types.
            boolean signupEnabled = runtime.getBool("enable_signup", false);
            step("enable_signup (bool) = " + signupEnabled);
            // Expected: false (user_service production override via setValue)

            int timeoutMs = runtime.getInt("request_timeout_ms", 3000);
            step("request_timeout_ms (int) = " + timeoutMs);
            // Expected: 10000 (common production override)

            String appName = runtime.getString("app_name", "Unknown");
            step("app_name (str) = " + appName);
            // Expected: "Acme SaaS Platform" (common base)

            // Check whether a key exists (regardless of its value).
            step("'database' exists = " + runtime.exists("database"));
            // Expected: true
            step("'ghost_key' exists = " + runtime.exists("ghost_key"));
            // Expected: false

            // ------------------------------------------------------------------
            // 3c. Verify local caching — no network requests on repeated reads
            // ------------------------------------------------------------------
            section("3c. Verify Local Caching");

            // connect() fetched everything eagerly. All get() calls are pure
            // local map reads with zero network overhead. The stats object
            // lets us verify this.
            ConfigStats stats = runtime.stats();
            step("Network fetches so far: " + stats.fetchCount());
            // Expected: 2 (user_service + common, fetched during connect)

            // Read a bunch of keys — none should trigger a fetch.
            for (int i = 0; i < 100; i++) {
                runtime.get("max_retries");
                runtime.get("database");
                runtime.get("credentials");
            }

            ConfigStats statsAfter = runtime.stats();
            step("Network fetches after 300 reads: " + statsAfter.fetchCount());
            // Expected: still 2

            if (statsAfter.fetchCount() != stats.fetchCount()) {
                throw new AssertionError(
                        "SDK made unexpected network calls! Before: " + stats.fetchCount()
                                + ", After: " + statsAfter.fetchCount());
            }
            step("PASSED — all reads served from local cache");

            // ------------------------------------------------------------------
            // 3d. Get ALL resolved values as a map
            // ------------------------------------------------------------------
            section("3d. Get Full Resolved Configuration");

            // Sometimes you want the entire resolved config as a map — for
            // logging at startup, passing to a framework, or debugging.
            Map<String, Object> allValues = runtime.getAll();
            step("Total resolved keys: " + allValues.size());
            for (String key : allValues.keySet().stream().sorted().toList()) {
                step("  " + key + " = " + allValues.get(key));
            }

            // ------------------------------------------------------------------
            // 3e. Multi-level inheritance — connect to auth_module in production
            // ------------------------------------------------------------------
            section("3e. Multi-Level Inheritance (auth_module)");

            try (ConfigRuntime authRuntime = client.config().connect(authModule, "production")) {
                Object sessionTtl = authRuntime.get("session_ttl_minutes");
                step("session_ttl_minutes = " + sessionTtl);
                // Expected: 30 (auth_module production override)

                Object mfa = authRuntime.get("mfa_enabled");
                step("mfa_enabled = " + mfa);
                // Expected: true (auth_module production override)

                // Keys inherited from user_service:
                Object db = authRuntime.get("database");
                step("database (inherited from user_service) = " + db);

                // Keys inherited all the way from common:
                Object app = authRuntime.get("app_name");
                step("app_name (inherited from common) = " + app);
            }
            step("auth_runtime closed via try-with-resources");

            // ==================================================================
            // 4. REAL-TIME UPDATES — WebSocket-driven cache invalidation
            // ==================================================================
            //
            // The SDK maintains a WebSocket connection to the config service. When
            // a config value is changed (via the console, API, or another SDK
            // instance), the server pushes an update and the SDK refreshes its
            // local cache. The application can register listeners to react to
            // changes without polling.
            // ==================================================================

            section("4. Real-Time Updates via WebSocket");

            // ------------------------------------------------------------------
            // 4a. Register a change listener
            // ------------------------------------------------------------------
            List<Map<String, Object>> changesReceived = new ArrayList<>();

            runtime.onChange(event -> {
                changesReceived.add(Map.of(
                        "key", event.key(),
                        "old_value", String.valueOf(event.oldValue()),
                        "new_value", String.valueOf(event.newValue()),
                        "source", event.source()
                ));
                System.out.println("    [CHANGE] " + event.key() + ": "
                        + event.oldValue() + " → " + event.newValue());
            });
            step("Change listener registered");

            // You can also listen for changes to a specific key.
            List<Object> retryChanges = new ArrayList<>();
            runtime.onChange("max_retries", e -> retryChanges.add(e));
            step("Key-specific listener registered for 'max_retries'");

            // ------------------------------------------------------------------
            // 4b. Simulate a config change via the management API
            // ------------------------------------------------------------------
            step("Updating max_retries on common (production) via management API...");

            // Re-fetch common to get latest state
            Config latestCommon = client.config().getByKey("common");
            client.config().setValue(latestCommon, "max_retries", 7, "production");

            // Give the WebSocket a moment to deliver the update.
            Thread.sleep(2000);

            // The runtime cache should now reflect the new value WITHOUT us
            // having to do anything — the WebSocket pushed the update.
            Object newRetries = runtime.get("max_retries");
            step("max_retries after live update = " + newRetries);
            // Expected: 7

            step("Changes received by listener: " + changesReceived.size());
            step("Retry-specific changes received: " + retryChanges.size());

            // ------------------------------------------------------------------
            // 4c. Connection lifecycle
            // ------------------------------------------------------------------
            section("4c. WebSocket Connection Lifecycle");

            String wsStatus = runtime.connectionStatus();
            step("WebSocket status: " + wsStatus);
            // Expected: "connected"

            // The SDK reconnects automatically if the connection drops, using
            // exponential backoff (1s, 2s, 4s, ... capped at 60s, retries forever).
            // You can also manually force a refresh if needed.
            runtime.refresh();
            step("Manual refresh completed");

            // ==================================================================
            // 5. ENVIRONMENT COMPARISON
            // ==================================================================

            section("5. Environment Comparison");

            // A developer might want to see how the same config resolves across
            // environments — useful for debugging "works in staging but not prod."
            for (String env : List.of("development", "staging", "production")) {
                try (ConfigRuntime envRuntime = client.config().connect(userService, env)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dbMap = (Map<String, Object>) envRuntime.get("database");
                    String dbHost = dbMap != null ? (String) dbMap.get("host") : "N/A";
                    int envRetries = envRuntime.getInt("max_retries", -1);
                    step("[" + String.format("%-12s", env) + "] db.host=" + dbHost
                            + ", retries=" + envRetries);
                }
            }

            // ==================================================================
            // 6. SYNC CLIENT DEMO
            // ==================================================================
            section("6. Sync Client (same API, no async)");

            // Java's SmplkitClient is always synchronous — there is no separate
            // async variant. All management methods block until the server responds.
            // ConfigRuntime.get() and typed accessors are always synchronous.
            //
            // Example (same pattern used throughout this showcase):
            //
            //   SmplkitClient client = SmplkitClient.builder().apiKey("sk_api_...").build();
            //   Config cfg = client.config().getByKey("user_service");
            //   cfg = client.config().setValues(cfg, Map.of("max_retries", 10), "production");
            //   try (ConfigRuntime rt = client.config().connect(cfg, "production")) {
            //       int r = rt.getInt("max_retries", 3);   // sync, zero network
            //       rt.refresh();                           // manual re-sync
            //   }

            step("(See code comments for API surface summary)");

            // ==================================================================
            // 7. CLEANUP
            // ==================================================================
            section("7. Cleanup");

            // Close the runtime connection (WebSocket teardown).
            runtime.close();
            step("Runtime connection closed");

            // Delete configs in dependency order (children first).
            client.config().delete(authModule.id());
            step("Deleted auth_module (" + authModule.id() + ")");

            client.config().delete(userService.id());
            step("Deleted user_service (" + userService.id() + ")");

            // Restore common to empty state (can't delete, but can clear values).
            Config latestCommonForReset = client.config().getByKey("common");
            client.config().update(latestCommonForReset, UpdateConfigParams.builder()
                    .description("")
                    .values(Map.of())
                    .environments(Map.of())
                    .build());
            step("Common config reset to empty");

        } // SmplkitClient.close() is called here

        // ======================================================================
        // DONE
        // ======================================================================
        section("ALL DONE");
        System.out.println("  The Config SDK showcase completed successfully.");
        System.out.println("  If you got here, Smpl Config is ready to ship.\n");
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
        System.out.println("  → " + description);
    }
}
