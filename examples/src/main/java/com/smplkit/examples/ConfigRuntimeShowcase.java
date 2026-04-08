package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.Config;
import com.smplkit.config.ConfigChangeEvent;
import com.smplkit.config.LiveConfig;

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
 *   <li>Resolved values via {@code resolve()}</li>
 *   <li>Live subscriptions via {@code subscribe()}</li>
 *   <li>Multi-level inheritance: auth_module &rarr; user_service &rarr; common</li>
 *   <li>Real-time updates via global and key-specific {@code onChange} listeners</li>
 *   <li>Manual refresh via {@code refresh()}</li>
 *   <li>Cleanup of all created resources</li>
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
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigRuntimeShowcase
 * </pre>
 */
public class ConfigRuntimeShowcase {

    public static void main(String[] args) throws Exception {
        // ======================================================================
        // 1. SDK INITIALIZATION + DEMO SETUP
        // ======================================================================
        section("1. SDK Initialization + Demo Setup");

        try (SmplClient client = SmplClient.builder()
                .environment("production")
                .service("showcase-service")
                .build()) {

            step("SmplClient initialized with environment=production");

            ConfigRuntimeSetup.DemoConfigs demo = ConfigRuntimeSetup.setupDemoConfigs(client);

            // ==================================================================
            // 2. PRESCRIPTIVE ACCESS via resolve()
            // ==================================================================
            section("2. Prescriptive Access via resolve()");

            Map<String, Object> commonValues = client.config().resolve("common");
            step("common resolved values: " + commonValues.size() + " keys");
            step("  app_name = " + commonValues.get("app_name"));
            step("  max_retries = " + commonValues.get("max_retries"));

            Map<String, Object> userServiceValues = client.config().resolve("user_service");
            step("user_service resolved values: " + userServiceValues.size() + " keys");
            step("  enable_signup = " + userServiceValues.get("enable_signup"));

            // ==================================================================
            // 3. SUBSCRIBE — live updates
            // ==================================================================
            section("3. Subscribe - Live Updates");

            LiveConfig<Map<String, Object>> liveCommon = client.config().subscribe("common");
            step("Subscribed to common config");
            step("  Current max_retries = " + liveCommon.getAsMap().get("max_retries"));

            // ==================================================================
            // 4. MULTI-LEVEL INHERITANCE
            // ==================================================================
            section("4. Multi-Level Inheritance");

            step("Hierarchy: common -> user_service -> auth_module");

            Map<String, Object> authValues = client.config().resolve("auth_module");
            step("auth_module resolved values: " + authValues.size() + " keys");
            step("  mfa_enabled = " + authValues.get("mfa_enabled"));
            step("  app_name (inherited from common) = " + authValues.get("app_name"));

            // ==================================================================
            // 5. ONCHANGE + REFRESH
            // ==================================================================
            section("5. OnChange + Refresh");

            List<ConfigChangeEvent> changes = new ArrayList<>();
            client.config().onChange(evt -> {
                changes.add(evt);
                System.out.println("    [CHANGE] " + evt.configKey() + "/" + evt.itemKey()
                        + ": " + evt.oldValue() + " -> " + evt.newValue());
            });
            step("Global change listener registered");

            List<ConfigChangeEvent> retryChanges = new ArrayList<>();
            client.config().onChange("common", "max_retries", retryChanges::add);
            step("Item-scoped listener registered for common/max_retries");

            Config latestCommon = client.config().get("common");
            Map<String, Object> prodEnv = new java.util.HashMap<>();
            prodEnv.put("values", Map.of(
                    "max_retries", 7,
                    "request_timeout_ms", 10000,
                    "log_level", "warn"
            ));
            latestCommon.setEnvironments(Map.of("production", prodEnv));
            latestCommon.save();
            step("Updated max_retries to 7 via management API");

            client.config().refresh();
            step("Manual refresh completed");

            step("max_retries after refresh = " + client.config().resolve("common").get("max_retries"));
            step("Live common max_retries = " + liveCommon.getAsMap().get("max_retries"));
            step("Global changes: " + changes.size() + ", retry-specific: " + retryChanges.size());

            // ==================================================================
            // 6. CLEANUP
            // ==================================================================
            ConfigRuntimeSetup.teardownDemoConfigs(client, demo);

        }

        section("ALL DONE");
        System.out.println("  The Config Runtime showcase completed successfully.");
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
