package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.flags.CreateFlagParams;
import com.smplkit.flags.FlagResource;
import com.smplkit.flags.FlagType;
import com.smplkit.flags.Rule;
import com.smplkit.flags.UpdateFlagParams;

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
 *   <li>Fetching a flag by ID and listing all flags</li>
 *   <li>Updating a flag via {@link FlagResource#update}</li>
 *   <li>Adding targeting rules via {@link FlagResource#addRule} with the {@link Rule} builder</li>
 *   <li>Context type management: create, list, delete</li>
 *   <li>Context listing for a given type</li>
 *   <li>Cleanup of all created resources</li>
 * </ul>
 *
 * <p>Prerequisites:</p>
 * <ul>
 *   <li>A valid smplkit API key (set via {@code SMPLKIT_API_KEY} env var)</li>
 *   <li>The smplkit Flags service running and reachable</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 *   export SMPLKIT_API_KEY="sk_api_..."
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsManagementShowcase
 * </pre>
 */
public class FlagsManagementShowcase {

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

            step("SmplClient initialized");

            // Track created resource IDs for cleanup
            List<String> createdFlagIds = new ArrayList<>();
            List<String> createdContextTypeIds = new ArrayList<>();

            // ==================================================================
            // 2. CREATE FLAGS — one of each type
            // ==================================================================
            section("2. Create Flags (all types)");

            // ------------------------------------------------------------------
            // 2a. Boolean flag
            // ------------------------------------------------------------------
            FlagResource darkMode = client.flags().create(
                    CreateFlagParams.builder("dark-mode-mgmt", "Dark Mode", FlagType.BOOLEAN)
                            .defaultValue(false)
                            .description("Controls whether the dark mode UI is enabled.")
                            .build());
            createdFlagIds.add(darkMode.id());
            step("Created boolean flag: key=" + darkMode.key()
                    + ", id=" + darkMode.id()
                    + ", default=" + darkMode.defaultValue());

            // ------------------------------------------------------------------
            // 2b. String flag
            // ------------------------------------------------------------------
            FlagResource banner = client.flags().create(
                    CreateFlagParams.builder("banner-text-mgmt", "Banner Text", FlagType.STRING)
                            .defaultValue("Welcome!")
                            .description("The promotional banner text shown on the homepage.")
                            .values(List.of(
                                    Map.of("name", "Welcome", "value", "Welcome!"),
                                    Map.of("name", "Sale", "value", "Big Summer Sale!"),
                                    Map.of("name", "Holiday", "value", "Happy Holidays!")
                            ))
                            .build());
            createdFlagIds.add(banner.id());
            step("Created string flag: key=" + banner.key()
                    + ", id=" + banner.id()
                    + ", default=" + banner.defaultValue());

            // ------------------------------------------------------------------
            // 2c. Numeric flag
            // ------------------------------------------------------------------
            FlagResource rateLimit = client.flags().create(
                    CreateFlagParams.builder("rate-limit-mgmt", "Rate Limit", FlagType.NUMERIC)
                            .defaultValue(100)
                            .description("Maximum API requests per minute per user.")
                            .values(List.of(
                                    Map.of("name", "Low", "value", 50),
                                    Map.of("name", "Standard", "value", 100),
                                    Map.of("name", "High", "value", 500),
                                    Map.of("name", "Unlimited", "value", 10000)
                            ))
                            .build());
            createdFlagIds.add(rateLimit.id());
            step("Created numeric flag: key=" + rateLimit.key()
                    + ", id=" + rateLimit.id()
                    + ", default=" + rateLimit.defaultValue());

            // ------------------------------------------------------------------
            // 2d. JSON flag
            // ------------------------------------------------------------------
            FlagResource uiConfig = client.flags().create(
                    CreateFlagParams.builder("ui-config-mgmt", "UI Configuration", FlagType.JSON)
                            .defaultValue(Map.of(
                                    "theme", "light",
                                    "sidebar", true,
                                    "maxTabs", 5
                            ))
                            .description("Complex UI configuration object controlling layout and theme.")
                            .build());
            createdFlagIds.add(uiConfig.id());
            step("Created JSON flag: key=" + uiConfig.key()
                    + ", id=" + uiConfig.id()
                    + ", default=" + uiConfig.defaultValue());

            // ==================================================================
            // 3. GET AND LIST FLAGS
            // ==================================================================
            section("3. Get and List Flags");

            // Fetch a single flag by ID.
            FlagResource fetched = client.flags().get(darkMode.id());
            step("Fetched flag by ID: key=" + fetched.key()
                    + ", type=" + fetched.type()
                    + ", description=" + fetched.description());

            // List all flags in the account.
            List<FlagResource> allFlags = client.flags().list();
            step("Total flags in account: " + allFlags.size());
            for (FlagResource f : allFlags) {
                step("  " + f.key() + " (" + f.type() + ") — " + f.name());
            }

            // ==================================================================
            // 4. UPDATE A FLAG
            // ==================================================================
            section("4. Update a Flag via FlagResource.update()");

            // Update the banner flag's description and default value.
            banner = banner.update(UpdateFlagParams.builder()
                    .description("Updated: promotional banner text for the site header.")
                    .defaultValue("Welcome to Acme!")
                    .build());
            step("Updated banner flag: description=" + banner.description()
                    + ", default=" + banner.defaultValue());

            // Update the rate limit flag to add named values.
            rateLimit = rateLimit.update(UpdateFlagParams.builder()
                    .name("API Rate Limit (per minute)")
                    .defaultValue(200)
                    .build());
            step("Updated rate limit: name=" + rateLimit.name()
                    + ", default=" + rateLimit.defaultValue());

            // ==================================================================
            // 5. ADD TARGETING RULES
            // ==================================================================
            section("5. Add Targeting Rules via FlagResource.addRule()");

            // Add a rule to enable dark mode for enterprise users in production.
            Map<String, Object> enterpriseRule = new Rule("Enable for enterprise users")
                    .environment("production")
                    .when("user.plan", "==", "enterprise")
                    .serve(true)
                    .build();
            darkMode = darkMode.addRule(enterpriseRule);
            step("Added rule to dark-mode: enable for enterprise in production");
            step("  Flag now has environments: " + darkMode.environments().keySet());

            // Add a rule to show sale banner for users in the US.
            Map<String, Object> saleBannerRule = new Rule("Sale banner for US users")
                    .environment("production")
                    .when("user.country", "==", "US")
                    .serve("Big Summer Sale!")
                    .build();
            banner = banner.addRule(saleBannerRule);
            step("Added rule to banner-text: sale for US users in production");

            // Add a rule to increase rate limit for premium accounts.
            Map<String, Object> premiumRateRule = new Rule("Higher rate limit for premium")
                    .environment("production")
                    .when("account.tier", "==", "premium")
                    .serve(500)
                    .build();
            rateLimit = rateLimit.addRule(premiumRateRule);
            step("Added rule to rate-limit: 500 for premium accounts in production");

            // Add a multi-condition rule: enterprise AND in beta program.
            Map<String, Object> betaRule = new Rule("JSON config for enterprise beta")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .when("user.beta", "==", true)
                    .serve(Map.of("theme", "dark", "sidebar", true, "maxTabs", 10, "betaFeatures", true))
                    .build();
            uiConfig = uiConfig.addRule(betaRule);
            step("Added multi-condition rule to ui-config for staging");

            // ==================================================================
            // 6. CONTEXT TYPE MANAGEMENT
            // ==================================================================
            section("6. Context Type Management");

            // Create context types to define the entity schema for targeting.
            Map<String, Object> userType = client.flags().createContextType("user-mgmt", Map.of(
                    "name", "User",
                    "attributes", Map.of(
                            "plan", Map.of("type", "string"),
                            "country", Map.of("type", "string"),
                            "beta", Map.of("type", "boolean")
                    )
            ));
            @SuppressWarnings("unchecked")
            String userTypeId = (String) ((Map<String, Object>) userType.get("attributes")).get("id");
            if (userTypeId == null) {
                userTypeId = (String) userType.get("id");
            }
            createdContextTypeIds.add(userTypeId);
            step("Created context type 'user-mgmt': " + userType);

            Map<String, Object> accountType = client.flags().createContextType("account-mgmt", Map.of(
                    "name", "Account",
                    "attributes", Map.of(
                            "tier", Map.of("type", "string"),
                            "employeeCount", Map.of("type", "number")
                    )
            ));
            @SuppressWarnings("unchecked")
            String accountTypeId = (String) ((Map<String, Object>) accountType.get("attributes")).get("id");
            if (accountTypeId == null) {
                accountTypeId = (String) accountType.get("id");
            }
            createdContextTypeIds.add(accountTypeId);
            step("Created context type 'account-mgmt': " + accountType);

            // List context types.
            List<Map<String, Object>> contextTypes = client.flags().listContextTypes();
            step("Context types in account: " + contextTypes.size());
            for (Map<String, Object> ct : contextTypes) {
                step("  " + ct);
            }

            // ==================================================================
            // 7. LIST CONTEXTS
            // ==================================================================
            section("7. List Contexts");

            // List any contexts that have been registered for the 'user-mgmt' type.
            List<Map<String, Object>> userContexts = client.flags().listContexts("user-mgmt");
            step("Contexts for 'user-mgmt': " + userContexts.size());
            for (Map<String, Object> ctx : userContexts) {
                step("  " + ctx);
            }

            // ==================================================================
            // 8. CLEANUP
            // ==================================================================
            section("8. Cleanup");

            // Delete context types first (no dependency on flags).
            for (String ctId : createdContextTypeIds) {
                if (ctId != null) {
                    client.flags().deleteContextType(ctId);
                    step("Deleted context type: " + ctId);
                }
            }

            // Delete all flags we created.
            for (String flagId : createdFlagIds) {
                client.flags().delete(flagId);
                step("Deleted flag: " + flagId);
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
        System.out.println("  → " + description);
    }
}
