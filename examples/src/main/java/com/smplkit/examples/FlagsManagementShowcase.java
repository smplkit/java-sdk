package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.Rule;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.flags.Flag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Smpl Flags Management Showcase
 * ===============================
 *
 * <p>Demonstrates the management-plane API for feature flags, covering:</p>
 * <ul>
 *   <li>Client initialization ({@link SmplClient})</li>
 *   <li>Creating flags of every type: boolean, string, numeric, and JSON</li>
 *   <li>Fetching a flag by key and listing all flags</li>
 *   <li>Updating a flag via field mutation and {@link Flag#save()}</li>
 *   <li>Adding targeting rules via {@link Flag#addRule} with the {@link Rule} builder</li>
 *   <li>Cleanup of all created resources</li>
 * </ul>
 *
 * <p>Prerequisites:</p>
 * <ul>
 *   <li>A valid smplkit API key, provided via {@code SMPLKIT_API_KEY} env var or {@code ~/.smplkit} config file</li>
 *   <li>The smplkit Flags service running and reachable</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsManagementShowcase
 * </pre>
 */
public class FlagsManagementShowcase {

    public static void main(String[] args) throws Exception {
        // ======================================================================
        // 1. SDK INITIALIZATION
        // ======================================================================
        section("1. SDK Initialization");

        // The SmplClient builder resolves three required parameters:
        //
        //   apiKey       -- not passed here; resolved automatically from the
        //                  SMPLKIT_API_KEY environment variable or the
        //                  ~/.smplkit configuration file.
        //
        //   environment  -- the target environment. Can also be resolved from
        //                  SMPLKIT_ENVIRONMENT if not passed.
        //
        //   service      -- identifies this SDK instance. Can also be resolved
        //                  from SMPLKIT_SERVICE if not passed.
        //
        // To pass the API key explicitly:
        //
        //   SmplClient client = SmplClient.builder()
        //       .apiKey("sk_api_...")
        //       .environment("production")
        //       .service("showcase-service")
        //       .build();
        //
        try (SmplClient client = SmplClient.builder()
                .environment("production")
                .service("showcase-service")
                .build()) {

            step("SmplClient initialized");

            // Track created resource keys for cleanup
            List<String> createdFlagKeys = new ArrayList<>();

            // Clean up any leftover flags from a previous failed run.
            for (String key : List.of("dark-mode-mgmt", "banner-text-mgmt", "rate-limit-mgmt", "ui-config-mgmt")) {
                try {
                    client.flags().delete(key);
                    step("Pre-cleanup: deleted leftover flag " + key);
                } catch (SmplNotFoundException ignored) {
                    // Not present -- nothing to clean up.
                }
            }

            // ==================================================================
            // 2. CREATE FLAGS -- one of each type
            // ==================================================================
            section("2. Create Flags (all types)");

            // ------------------------------------------------------------------
            // 2a. Boolean flag
            // ------------------------------------------------------------------
            Flag<Boolean> darkMode = client.flags().newBooleanFlag(
                    "dark-mode-mgmt", false, "Dark Mode",
                    "Controls whether the dark mode UI is enabled.");
            darkMode.save();
            createdFlagKeys.add(darkMode.getKey());
            step("Created boolean flag: key=" + darkMode.getKey()
                    + ", id=" + darkMode.getId()
                    + ", default=" + darkMode.getDefault());

            // ------------------------------------------------------------------
            // 2b. String flag
            // ------------------------------------------------------------------
            Flag<String> banner = client.flags().newStringFlag(
                    "banner-text-mgmt", "Welcome!", "Banner Text",
                    "The promotional banner text shown on the homepage.",
                    List.of(
                            Map.of("name", "Welcome", "value", "Welcome!"),
                            Map.of("name", "Sale", "value", "Big Summer Sale!"),
                            Map.of("name", "Holiday", "value", "Happy Holidays!")
                    ));
            banner.save();
            createdFlagKeys.add(banner.getKey());
            step("Created string flag: key=" + banner.getKey()
                    + ", id=" + banner.getId()
                    + ", default=" + banner.getDefault());

            // ------------------------------------------------------------------
            // 2c. Numeric flag
            // ------------------------------------------------------------------
            Flag<Number> rateLimit = client.flags().newNumberFlag(
                    "rate-limit-mgmt", 100, "Rate Limit",
                    "Maximum API requests per minute per user.",
                    List.of(
                            Map.of("name", "Low", "value", 50),
                            Map.of("name", "Standard", "value", 100),
                            Map.of("name", "High", "value", 500),
                            Map.of("name", "Unlimited", "value", 10000)
                    ));
            rateLimit.save();
            createdFlagKeys.add(rateLimit.getKey());
            step("Created numeric flag: key=" + rateLimit.getKey()
                    + ", id=" + rateLimit.getId()
                    + ", default=" + rateLimit.getDefault());

            // ------------------------------------------------------------------
            // 2d. JSON flag
            // ------------------------------------------------------------------
            Flag<Object> uiConfig = client.flags().newJsonFlag(
                    "ui-config-mgmt",
                    Map.of("theme", "light", "sidebar", true, "maxTabs", 5),
                    "UI Configuration",
                    "Complex UI configuration object controlling layout and theme.",
                    List.of(
                            Map.of("name", "Default", "value", Map.of("theme", "light", "sidebar", true, "maxTabs", 5)),
                            Map.of("name", "Enterprise Beta", "value", Map.of("theme", "dark", "sidebar", true, "maxTabs", 10, "betaFeatures", true))
                    ));
            uiConfig.save();
            createdFlagKeys.add(uiConfig.getKey());
            step("Created JSON flag: key=" + uiConfig.getKey()
                    + ", id=" + uiConfig.getId()
                    + ", default=" + uiConfig.getDefault());

            // ==================================================================
            // 3. GET AND LIST FLAGS
            // ==================================================================
            section("3. Get and List Flags");

            // Fetch a single flag by key.
            Flag<?> fetched = client.flags().get("dark-mode-mgmt");
            step("Fetched flag by key: key=" + fetched.getKey()
                    + ", type=" + fetched.getType()
                    + ", description=" + fetched.getDescription());

            // List all flags in the account.
            List<Flag<?>> allFlags = client.flags().list();
            step("Total flags in account: " + allFlags.size());
            for (Flag<?> f : allFlags) {
                step("  " + f.getKey() + " (" + f.getType() + ") -- " + f.getName());
            }

            // ==================================================================
            // 4. UPDATE A FLAG
            // ==================================================================
            section("4. Update a Flag via mutation + save()");

            // Update the banner flag's description and default value.
            // When changing the default, the new value must exist in the values array.
            banner.setDescription("Updated: promotional banner text for the site header.");
            banner.setValues(List.of(
                    Map.of("name", "Welcome", "value", "Welcome!"),
                    Map.of("name", "Acme", "value", "Welcome to Acme!"),
                    Map.of("name", "Sale", "value", "Big Summer Sale!"),
                    Map.of("name", "Holiday", "value", "Happy Holidays!")
            ));
            banner.setDefault("Welcome to Acme!");
            banner.save();
            step("Updated banner flag: description=" + banner.getDescription()
                    + ", default=" + banner.getDefault());

            // Update the rate limit flag's name and default.
            rateLimit.setName("API Rate Limit (per minute)");
            rateLimit.setValues(List.of(
                    Map.of("name", "Low", "value", 50),
                    Map.of("name", "Standard", "value", 100),
                    Map.of("name", "Medium", "value", 200),
                    Map.of("name", "High", "value", 500),
                    Map.of("name", "Unlimited", "value", 10000)
            ));
            rateLimit.setDefault(200);
            rateLimit.save();
            step("Updated rate limit: name=" + rateLimit.getName()
                    + ", default=" + rateLimit.getDefault());

            // ==================================================================
            // 5. ADD TARGETING RULES
            // ==================================================================
            section("5. Add Targeting Rules via Flag.addRule() + save()");

            // Add a rule to enable dark mode for enterprise users in production.
            darkMode.addRule(new Rule("Enable for enterprise users")
                    .environment("production")
                    .when("user.plan", "==", "enterprise")
                    .serve(true)
                    .build());
            darkMode.save();
            step("Added rule to dark-mode: enable for enterprise in production");
            step("  Flag now has environments: " + darkMode.getEnvironments().keySet());

            // Add a rule to show sale banner for users in the US.
            banner.addRule(new Rule("Sale banner for US users")
                    .environment("production")
                    .when("user.country", "==", "US")
                    .serve("Big Summer Sale!")
                    .build());
            banner.save();
            step("Added rule to banner-text: sale for US users in production");

            // Add a rule to increase rate limit for premium accounts.
            rateLimit.addRule(new Rule("Higher rate limit for premium")
                    .environment("production")
                    .when("account.tier", "==", "premium")
                    .serve(500)
                    .build());
            rateLimit.save();
            step("Added rule to rate-limit: 500 for premium accounts in production");

            // Add a multi-condition rule: enterprise AND in beta program.
            uiConfig.addRule(new Rule("JSON config for enterprise beta")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .when("user.beta", "==", true)
                    .serve(Map.of("theme", "dark", "sidebar", true, "maxTabs", 10, "betaFeatures", true))
                    .build());
            uiConfig.save();
            step("Added multi-condition rule to ui-config for staging");

            // ==================================================================
            // 6. CLEANUP
            // ==================================================================
            section("6. Cleanup");

            // Delete all flags we created (by key).
            for (String flagKey : createdFlagKeys) {
                client.flags().delete(flagKey);
                step("Deleted flag: " + flagKey);
            }

        } // SmplClient.close() called here

        // ======================================================================
        // DONE
        // ======================================================================
        section("ALL DONE");
        System.out.println("  The Flags Management showcase completed successfully.");
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
        System.out.println("  -> " + description);
    }
}
