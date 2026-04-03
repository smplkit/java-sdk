package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.flags.Context;
import com.smplkit.flags.CreateFlagParams;
import com.smplkit.flags.FlagHandle;
import com.smplkit.flags.FlagResource;
import com.smplkit.flags.FlagType;
import com.smplkit.flags.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Smpl Flags Demo Setup
 * ======================
 *
 * <p>A quick-start setup script that creates a realistic set of feature flags,
 * targeting rules, and context types, then evaluates them against sample
 * contexts. Designed to be a compact, end-to-end walkthrough that can be
 * used as a demo or integration smoke test.</p>
 *
 * <p>This script:</p>
 * <ul>
 *   <li>Creates a "dark-mode" boolean flag</li>
 *   <li>Creates an "items-per-page" numeric flag</li>
 *   <li>Creates a "theme-config" JSON flag</li>
 *   <li>Adds targeting rules for enterprise users</li>
 *   <li>Creates context types for "user" and "account"</li>
 *   <li>Connects to the staging environment</li>
 *   <li>Evaluates flags with sample contexts</li>
 *   <li>Cleans up all created resources</li>
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
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsDemoSetup
 * </pre>
 */
public class FlagsDemoSetup {

    public static void main(String[] args) throws Exception {
        // ======================================================================
        // 1. INITIALIZATION
        // ======================================================================
        section("1. Initialization");

        String apiKey = System.getenv("SMPLKIT_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: Set the SMPLKIT_API_KEY environment variable before running.");
            System.err.println("  export SMPLKIT_API_KEY='sk_api_...'");
            System.exit(1);
        }

        try (SmplClient client = SmplClient.builder()
                .apiKey(apiKey)
                .environment("staging")
                .build()) {

            step("SmplClient initialized");

            List<String> createdFlagIds = new ArrayList<>();
            List<String> createdContextTypeIds = new ArrayList<>();

            // ==================================================================
            // 2. CREATE FLAGS
            // ==================================================================
            section("2. Create Feature Flags");

            // --- dark-mode: a simple boolean toggle ---
            FlagResource darkMode = client.flags().create(
                    CreateFlagParams.builder("dark-mode-demo", "Dark Mode", FlagType.BOOLEAN)
                            .defaultValue(false)
                            .description("Enables dark mode across the application UI.")
                            .build());
            createdFlagIds.add(darkMode.id());
            step("Created 'dark-mode-demo' (boolean, default=false)");

            // --- items-per-page: controls pagination ---
            FlagResource itemsPerPage = client.flags().create(
                    CreateFlagParams.builder("items-per-page-demo", "Items Per Page", FlagType.NUMERIC)
                            .defaultValue(20)
                            .description("Number of items displayed per page in list views.")
                            .values(List.of(
                                    Map.of("name", "Small", "value", 10),
                                    Map.of("name", "Medium", "value", 20),
                                    Map.of("name", "Large", "value", 50),
                                    Map.of("name", "Extra Large", "value", 100)
                            ))
                            .build());
            createdFlagIds.add(itemsPerPage.id());
            step("Created 'items-per-page-demo' (numeric, default=20)");

            // --- theme-config: a complex JSON configuration ---
            FlagResource themeConfig = client.flags().create(
                    CreateFlagParams.builder("theme-config-demo", "Theme Config", FlagType.JSON)
                            .defaultValue(Map.of(
                                    "primaryColor", "#1976d2",
                                    "fontFamily", "Inter, sans-serif",
                                    "borderRadius", 8,
                                    "density", "comfortable"
                            ))
                            .description("Theme configuration controlling colors, fonts, and spacing.")
                            .build());
            createdFlagIds.add(themeConfig.id());
            step("Created 'theme-config-demo' (JSON, complex default)");

            // ==================================================================
            // 3. ADD TARGETING RULES FOR ENTERPRISE USERS
            // ==================================================================
            section("3. Add Targeting Rules");

            // Enterprise users get dark mode enabled.
            darkMode = darkMode.addRule(new Rule("Dark mode for enterprise")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .serve(true)
                    .build());
            step("Rule: dark-mode ON for enterprise users in staging");

            // Enterprise users get more items per page.
            itemsPerPage = itemsPerPage.addRule(new Rule("More items for enterprise")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .serve(50)
                    .build());
            step("Rule: 50 items/page for enterprise users in staging");

            // Enterprise users get a branded theme.
            themeConfig = themeConfig.addRule(new Rule("Enterprise theme")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .serve(Map.of(
                            "primaryColor", "#6200ea",
                            "fontFamily", "Roboto, sans-serif",
                            "borderRadius", 4,
                            "density", "compact",
                            "brandLogo", true
                    ))
                    .build());
            step("Rule: custom enterprise theme in staging");

            // Additional rule: large accounts get extra items per page.
            itemsPerPage = itemsPerPage.addRule(new Rule("Extra items for large accounts")
                    .environment("staging")
                    .when("account.size", ">=", 1000)
                    .serve(100)
                    .build());
            step("Rule: 100 items/page for accounts with 1000+ users in staging");

            // ==================================================================
            // 4. CREATE CONTEXT TYPES
            // ==================================================================
            section("4. Create Context Types");

            Map<String, Object> userType = client.flags().createContextType("user-demo", Map.of(
                    "name", "User",
                    "attributes", Map.of(
                            "plan", Map.of("type", "string"),
                            "email", Map.of("type", "string"),
                            "role", Map.of("type", "string")
                    )
            ));
            @SuppressWarnings("unchecked")
            String userTypeId = (String) ((Map<String, Object>) userType.get("attributes")).get("id");
            if (userTypeId == null) {
                userTypeId = (String) userType.get("id");
            }
            createdContextTypeIds.add(userTypeId);
            step("Created context type 'user-demo'");

            Map<String, Object> accountType = client.flags().createContextType("account-demo", Map.of(
                    "name", "Account",
                    "attributes", Map.of(
                            "size", Map.of("type", "number"),
                            "industry", Map.of("type", "string")
                    )
            ));
            @SuppressWarnings("unchecked")
            String accountTypeId = (String) ((Map<String, Object>) accountType.get("attributes")).get("id");
            if (accountTypeId == null) {
                accountTypeId = (String) accountType.get("id");
            }
            createdContextTypeIds.add(accountTypeId);
            step("Created context type 'account-demo'");

            // ==================================================================
            // 5. CONNECT TO STAGING ENVIRONMENT
            // ==================================================================
            section("5. Connect to Staging");

            client.connect();
            step("Connected to 'staging' environment");

            // Declare typed handles for evaluation.
            FlagHandle<Boolean> darkModeHandle = client.flags().boolFlag("dark-mode-demo", false);
            FlagHandle<Number> itemsHandle = client.flags().numberFlag("items-per-page-demo", 20);
            FlagHandle<Object> themeHandle = client.flags().jsonFlag("theme-config-demo",
                    Map.of("primaryColor", "#1976d2"));

            step("Declared typed flag handles");

            // ==================================================================
            // 6. EVALUATE FLAGS WITH SAMPLE CONTEXTS
            // ==================================================================
            section("6. Evaluate Flags — Enterprise User");

            // Simulate an enterprise user from a large account.
            List<Context> enterpriseCtx = List.of(
                    Context.builder("user", "user-1001")
                            .name("Carol (Enterprise)")
                            .attr("plan", "enterprise")
                            .attr("email", "carol@bigcorp.com")
                            .attr("role", "admin")
                            .build(),
                    Context.builder("account", "acct-500")
                            .attr("size", 5000)
                            .attr("industry", "fintech")
                            .build()
            );

            Boolean darkModeEnterprise = darkModeHandle.get(enterpriseCtx);
            step("dark-mode = " + darkModeEnterprise);
            // Expected: true (enterprise rule matches)

            Number itemsEnterprise = itemsHandle.get(enterpriseCtx);
            step("items-per-page = " + itemsEnterprise);
            // Expected: 50 (enterprise rule matches first)

            Object themeEnterprise = themeHandle.get(enterpriseCtx);
            step("theme-config = " + themeEnterprise);
            // Expected: enterprise branded theme

            // ------------------------------------------------------------------
            section("6b. Evaluate Flags — Free User");

            // Simulate a free-tier user from a small account.
            List<Context> freeCtx = List.of(
                    Context.builder("user", "user-2001")
                            .name("Dave (Free)")
                            .attr("plan", "free")
                            .attr("email", "dave@startup.io")
                            .attr("role", "viewer")
                            .build(),
                    Context.builder("account", "acct-800")
                            .attr("size", 10)
                            .attr("industry", "saas")
                            .build()
            );

            Boolean darkModeFree = darkModeHandle.get(freeCtx);
            step("dark-mode = " + darkModeFree);
            // Expected: false (no rule match, falls back to default)

            Number itemsFree = itemsHandle.get(freeCtx);
            step("items-per-page = " + itemsFree);
            // Expected: 20 (no rule match, falls back to default)

            Object themeFree = themeHandle.get(freeCtx);
            step("theme-config = " + themeFree);
            // Expected: default theme

            // ------------------------------------------------------------------
            section("6c. Evaluate Flags — Large Account Free User");

            // Free user but at a large account (>= 1000) — tests the account size rule.
            List<Context> largeFreeCtx = List.of(
                    Context.builder("user", "user-3001")
                            .name("Eve (Free, Large Org)")
                            .attr("plan", "free")
                            .attr("email", "eve@megacorp.com")
                            .attr("role", "editor")
                            .build(),
                    Context.builder("account", "acct-900")
                            .attr("size", 2500)
                            .attr("industry", "healthcare")
                            .build()
            );

            Boolean darkModeLargeFree = darkModeHandle.get(largeFreeCtx);
            step("dark-mode = " + darkModeLargeFree);
            // Expected: false (not enterprise)

            Number itemsLargeFree = itemsHandle.get(largeFreeCtx);
            step("items-per-page = " + itemsLargeFree);
            // Expected: 100 (account.size >= 1000 rule matches)

            Object themeLargeFree = themeHandle.get(largeFreeCtx);
            step("theme-config = " + themeLargeFree);
            // Expected: default theme (no enterprise rule match)

            // ==================================================================
            // 7. CLEANUP
            // ==================================================================
            section("7. Cleanup");

            // Disconnect from runtime.
            client.flags().disconnect();
            step("Disconnected from staging");

            // Delete context types.
            for (String ctId : createdContextTypeIds) {
                if (ctId != null) {
                    client.flags().deleteContextType(ctId);
                    step("Deleted context type: " + ctId);
                }
            }

            // Delete flags.
            for (String flagId : createdFlagIds) {
                client.flags().delete(flagId);
                step("Deleted flag: " + flagId);
            }

        } // SmplClient.close() called here

        // ======================================================================
        // DONE
        // ======================================================================
        section("ALL DONE");
        System.out.println("  The Flags Demo Setup completed successfully.");
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
