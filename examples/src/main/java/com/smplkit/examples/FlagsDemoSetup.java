package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.Context;
import com.smplkit.flags.Flag;
import com.smplkit.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Smpl Flags Demo Setup
 * ======================
 *
 * <p>A quick-start setup script that creates a realistic set of feature flags,
 * targeting rules, and evaluates them against sample contexts. Designed to be
 * a compact, end-to-end walkthrough that can be used as a demo or integration
 * smoke test.</p>
 *
 * <p>This script:</p>
 * <ul>
 *   <li>Creates a "dark-mode" boolean flag</li>
 *   <li>Creates an "items-per-page" numeric flag</li>
 *   <li>Creates a "theme-config" JSON flag</li>
 *   <li>Adds targeting rules for enterprise users</li>
 *   <li>Evaluates flags with sample contexts (lazy-initialized)</li>
 *   <li>Cleans up all created resources</li>
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
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsDemoSetup
 * </pre>
 */
public class FlagsDemoSetup {

    public static void main(String[] args) throws Exception {
        // ======================================================================
        // 1. INITIALIZATION
        // ======================================================================
        section("1. Initialization");

        try (SmplClient client = SmplClient.builder()
                .environment("staging")
                .service("showcase-service")
                .build()) {

            step("SmplClient initialized");

            List<String> createdFlagKeys = new ArrayList<>();

            // ==================================================================
            // 2. CREATE FLAGS
            // ==================================================================
            section("2. Create Feature Flags");

            // --- dark-mode: a simple boolean toggle ---
            Flag<Boolean> darkMode = client.flags().newBooleanFlag(
                    "dark-mode-demo", false, "Dark Mode",
                    "Enables dark mode across the application UI.");
            darkMode.save();
            createdFlagKeys.add(darkMode.getKey());
            step("Created 'dark-mode-demo' (boolean, default=false), id=" + darkMode.getId());

            // --- items-per-page: controls pagination ---
            Flag<Number> itemsPerPage = client.flags().newNumberFlag(
                    "items-per-page-demo", 20, "Items Per Page",
                    "Number of items displayed per page in list views.",
                    List.of(
                            Map.of("name", "Small", "value", 10),
                            Map.of("name", "Medium", "value", 20),
                            Map.of("name", "Large", "value", 50),
                            Map.of("name", "Extra Large", "value", 100)
                    ));
            itemsPerPage.save();
            createdFlagKeys.add(itemsPerPage.getKey());
            step("Created 'items-per-page-demo' (numeric, default=20), id=" + itemsPerPage.getId());

            // --- theme-config: a complex JSON configuration ---
            Flag<Object> themeConfig = client.flags().newJsonFlag(
                    "theme-config-demo",
                    Map.of("primaryColor", "#1976d2", "fontFamily", "Inter, sans-serif",
                           "borderRadius", 8, "density", "comfortable"),
                    "Theme Config",
                    "Theme configuration controlling colors, fonts, and spacing.");
            themeConfig.save();
            createdFlagKeys.add(themeConfig.getKey());
            step("Created 'theme-config-demo' (JSON, complex default), id=" + themeConfig.getId());

            // ==================================================================
            // 3. ADD TARGETING RULES FOR ENTERPRISE USERS
            // ==================================================================
            section("3. Add Targeting Rules");

            // Enterprise users get dark mode enabled.
            darkMode.addRule(new Rule("Dark mode for enterprise")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .serve(true)
                    .build());
            darkMode.save();
            step("Rule: dark-mode ON for enterprise users in staging");

            // Enterprise users get more items per page.
            itemsPerPage.addRule(new Rule("More items for enterprise")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .serve(50)
                    .build());
            itemsPerPage.save();
            step("Rule: 50 items/page for enterprise users in staging");

            // Enterprise users get a branded theme.
            themeConfig.addRule(new Rule("Enterprise theme")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .serve(Map.of("primaryColor", "#6200ea", "fontFamily", "Roboto, sans-serif",
                                  "borderRadius", 4, "density", "compact", "brandLogo", true))
                    .build());
            themeConfig.save();
            step("Rule: custom enterprise theme in staging");

            // Additional rule: large accounts get extra items per page.
            itemsPerPage.addRule(new Rule("Extra items for large accounts")
                    .environment("staging")
                    .when("account.size", ">=", 1000)
                    .serve(100)
                    .build());
            itemsPerPage.save();
            step("Rule: 100 items/page for accounts with 1000+ users in staging");

            // ==================================================================
            // 4. DECLARE TYPED HANDLES (lazy-init -- no explicit connect needed)
            // ==================================================================
            section("4. Declare Typed Handles");

            Flag<Boolean> darkModeHandle = client.flags().booleanFlag("dark-mode-demo", false);
            Flag<Number> itemsHandle = client.flags().numberFlag("items-per-page-demo", 20);
            Flag<Object> themeHandle = client.flags().jsonFlag("theme-config-demo",
                    Map.of("primaryColor", "#1976d2"));

            step("Declared typed flag handles (lazy-init on first get())");

            // ==================================================================
            // 5. EVALUATE FLAGS WITH SAMPLE CONTEXTS
            // ==================================================================
            section("5. Evaluate Flags -- Enterprise User");

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

            Number itemsEnterprise = itemsHandle.get(enterpriseCtx);
            step("items-per-page = " + itemsEnterprise);

            Object themeEnterprise = themeHandle.get(enterpriseCtx);
            step("theme-config = " + themeEnterprise);

            section("5b. Evaluate Flags -- Free User");

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

            Number itemsFree = itemsHandle.get(freeCtx);
            step("items-per-page = " + itemsFree);

            Object themeFree = themeHandle.get(freeCtx);
            step("theme-config = " + themeFree);

            section("5c. Evaluate Flags -- Large Account Free User");

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

            step("dark-mode = " + darkModeHandle.get(largeFreeCtx));
            step("items-per-page = " + itemsHandle.get(largeFreeCtx));
            step("theme-config = " + themeHandle.get(largeFreeCtx));

            // ==================================================================
            // 6. CLEANUP
            // ==================================================================
            section("6. Cleanup");

            for (String flagKey : createdFlagKeys) {
                client.flags().delete(flagKey);
                step("Deleted flag: " + flagKey);
            }

        } // SmplClient.close() called here

        section("ALL DONE");
        System.out.println("  The Flags Demo Setup completed successfully.");
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
