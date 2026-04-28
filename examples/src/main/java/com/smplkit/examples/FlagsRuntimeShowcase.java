package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.Context;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.flags.Flag;
import com.smplkit.flags.FlagChangeEvent;
import com.smplkit.flags.FlagStats;
import com.smplkit.Rule;
import java.util.List;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Smpl Flags Runtime Showcase
 * ============================
 *
 * <p>Demonstrates the runtime (prescriptive) tier of the Flags SDK, covering:</p>
 * <ul>
 *   <li>Client initialization and flag creation with targeting rules</li>
 *   <li>Typed flag handles: {@code booleanFlag}, {@code stringFlag}, {@code numberFlag}, {@code jsonFlag}</li>
 *   <li>Context providers via {@code setContextProvider}</li>
 *   <li>Explicit context override in {@code Flag.get(List)}</li>
 *   <li>Context registration via {@code register()}</li>
 *   <li>Resolution caching and diagnostic stats</li>
 *   <li>Global and per-flag change listeners via {@code onChange}</li>
 *   <li>Manual refresh lifecycle</li>
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
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsRuntimeShowcase
 * </pre>
 */
public class FlagsRuntimeShowcase {

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
        //       .environment("staging")
        //       .service("showcase-service")
        //       .build();
        //
        try (SmplClient client = SmplClient.builder()
                .environment("staging")
                .service("showcase-service")
                .build()) {

            step("SmplClient initialized");

            // Track created flag keys for cleanup
            List<String> createdFlagKeys = List.of(
                    "maintenance-mode-rt", "greeting-rt", "page-size-rt", "layout-config-rt");

            // Clean up any leftover flags from a previous failed run.
            for (String key : createdFlagKeys) {
                try {
                    client.flags().management().delete(key);
                    step("Pre-cleanup: deleted leftover flag " + key);
                } catch (SmplNotFoundException ignored) {
                    // Not present -- nothing to clean up.
                }
            }

            // ==================================================================
            // 2. SET UP FLAGS WITH TARGETING RULES
            // ==================================================================
            section("2. Create Flags with Targeting Rules");

            // Boolean flag with environment-specific rules.
            Flag<Boolean> maintenance = client.flags().management().newBooleanFlag(
                    "maintenance-mode-rt", false, "Maintenance Mode",
                    "Puts the application into maintenance mode.");
            maintenance.save();

            step("Created boolean flag: " + maintenance.getId());

            // Add a rule that enables maintenance mode for staging.
            maintenance.addRule(new Rule("Enable in staging")
                    .environment("staging")
                    .serve(true)
                    .build());
            maintenance.save();
            step("Added rule: maintenance enabled in staging");

            // String flag.
            Flag<String> greeting = client.flags().management().newStringFlag(
                    "greeting-rt", "Hello!", "Greeting Message",
                    "Personalized greeting message for different user segments.",
                    List.of(
                            Map.of("name", "Default", "value", "Hello!"),
                            Map.of("name", "Enterprise", "value", "Welcome back, valued partner!"),
                            Map.of("name", "Trial", "value", "Welcome! Your trial expires soon.")
                    ));
            greeting.save();

            step("Created string flag: " + greeting.getId());

            greeting.addRule(new Rule("Enterprise greeting")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .serve("Welcome back, valued partner!")
                    .build());
            greeting.save();
            step("Added rule: enterprise greeting in staging");

            // Numeric flag.
            Flag<Number> pageSize = client.flags().management().newNumberFlag(
                    "page-size-rt", 25, "Page Size",
                    "Number of items per page in list views.",
                    List.of(
                            Map.of("name", "Default", "value", 25),
                            Map.of("name", "Power User", "value", 100)
                    ));
            pageSize.save();

            step("Created numeric flag: " + pageSize.getId());

            pageSize.addRule(new Rule("Larger pages for power users")
                    .environment("staging")
                    .when("user.role", "==", "admin")
                    .serve(100)
                    .build());
            pageSize.save();
            step("Added rule: 100 items per page for admins in staging");

            // JSON flag.
            Flag<Object> layout = client.flags().management().newJsonFlag(
                    "layout-config-rt",
                    Map.of("columns", 2, "showHeader", true, "compact", false),
                    "Layout Config",
                    "Layout configuration controlling the dashboard appearance.",
                    List.of(
                            Map.of("name", "Default", "value", Map.of("columns", 2, "showHeader", true, "compact", false)),
                            Map.of("name", "Compact", "value", Map.of("columns", 1, "showHeader", false, "compact", true))
                    ));
            layout.save();

            step("Created JSON flag: " + layout.getId());

            layout.addRule(new Rule("Compact layout for mobile users")
                    .environment("staging")
                    .when("user.device", "==", "mobile")
                    .serve(Map.of("columns", 1, "showHeader", false, "compact", true))
                    .build());
            layout.save();
            step("Added rule: compact layout for mobile in staging");

            // ==================================================================
            // 3. TYPED FLAG HANDLES
            // ==================================================================
            section("3. Typed Flag Handles");

            // Declare typed handles -- these are lightweight wrappers that provide
            // type-safe access and a code-level default if the flag is missing.
            // The first call to get() lazily initializes the runtime connection.
            Flag<Boolean> maintenanceHandle = client.flags().booleanFlag("maintenance-mode-rt", false);
            Flag<String> greetingHandle = client.flags().stringFlag("greeting-rt", "Hi there!");
            Flag<Number> pageSizeHandle = client.flags().numberFlag("page-size-rt", 25);
            Flag<Object> layoutHandle = client.flags().jsonFlag("layout-config-rt",
                    Map.of("columns", 2, "showHeader", true, "compact", false));

            step("Declared 4 typed handles");
            step("  booleanFlag: " + maintenanceHandle.getId() + " (default=" + maintenanceHandle.getDefault() + ")");
            step("  stringFlag: " + greetingHandle.getId() + " (default=" + greetingHandle.getDefault() + ")");
            step("  numberFlag: " + pageSizeHandle.getId() + " (default=" + pageSizeHandle.getDefault() + ")");
            step("  jsonFlag: " + layoutHandle.getId() + " (default=" + layoutHandle.getDefault() + ")");

            // ==================================================================
            // 4. CONTEXT PROVIDER
            // ==================================================================
            section("4. Context Provider");

            // A context provider supplies the default evaluation context.
            // In a real app this might read from the current request, session, etc.
            client.flags().setContextProvider(() -> List.of(
                    Context.builder("user", "user-42")
                            .name("Alice")
                            .attr("plan", "enterprise")
                            .attr("role", "admin")
                            .attr("device", "desktop")
                            .build()
            ));
            step("Context provider set (user=Alice, plan=enterprise, role=admin)");

            // Evaluate flags with the provider context.
            Boolean maintenanceVal = maintenanceHandle.get();
            step("maintenance-mode = " + maintenanceVal);
            // Expected: true (staging rule serves true regardless of context)

            String greetingVal = greetingHandle.get();
            step("greeting = " + greetingVal);
            // Expected: "Welcome back, valued partner!" (enterprise user rule matches)

            Number pageSizeVal = pageSizeHandle.get();
            step("page-size = " + pageSizeVal);
            // Expected: 100 (admin role rule matches)

            Object layoutVal = layoutHandle.get();
            step("layout-config = " + layoutVal);
            // Expected: default (desktop user, mobile rule does not match)

            // ==================================================================
            // 5. EXPLICIT CONTEXT OVERRIDE
            // ==================================================================
            section("5. Explicit Context Override");

            // Override the provider context by passing explicit contexts to get().
            // This is useful for evaluating flags on behalf of another user.
            List<Context> mobileUser = List.of(
                    Context.builder("user", "user-99")
                            .name("Bob")
                            .attr("plan", "free")
                            .attr("role", "viewer")
                            .attr("device", "mobile")
                            .build()
            );

            String greetingForBob = greetingHandle.get(mobileUser);
            step("greeting (Bob, free) = " + greetingForBob);
            // Expected: default "Hello!" (not enterprise, no rule match)

            Number pageSizeForBob = pageSizeHandle.get(mobileUser);
            step("page-size (Bob, viewer) = " + pageSizeForBob);
            // Expected: 25 (not admin, no rule match)

            Object layoutForBob = layoutHandle.get(mobileUser);
            step("layout-config (Bob, mobile) = " + layoutForBob);
            // Expected: compact layout (mobile rule matches)

            // ==================================================================
            // 6. CONTEXT REGISTRATION
            // ==================================================================
            section("6. Context Registration");

            // register() buffers contexts for bulk reporting to the smplkit
            // backend. This lets the dashboard show which entities are active.
            Context aliceCtx = Context.builder("user", "user-42")
                    .name("Alice")
                    .attr("plan", "enterprise")
                    .attr("role", "admin")
                    .build();
            Context bobCtx = Context.builder("user", "user-99")
                    .name("Bob")
                    .attr("plan", "free")
                    .attr("role", "viewer")
                    .build();

            client.management().contexts.register(List.of(aliceCtx, bobCtx));
            step("Registered 2 contexts (Alice, Bob)");

            // Flush any buffered contexts immediately (normally happens on a timer).
            client.management().contexts.flush();
            step("Flushed context buffer");

            // ==================================================================
            // 7. RESOLUTION CACHING AND STATS
            // ==================================================================
            section("7. Resolution Caching and Stats");

            FlagStats statsBefore = client.flags().stats();
            step("Stats before repeated reads: hits=" + statsBefore.cacheHits()
                    + ", misses=" + statsBefore.cacheMisses());

            // Perform many reads -- all should be served from cache.
            for (int i = 0; i < 200; i++) {
                maintenanceHandle.get();
                greetingHandle.get();
                pageSizeHandle.get();
                layoutHandle.get();
            }

            FlagStats statsAfter = client.flags().stats();
            step("Stats after 800 reads: hits=" + statsAfter.cacheHits()
                    + ", misses=" + statsAfter.cacheMisses());
            step("Cache hit increase: " + (statsAfter.cacheHits() - statsBefore.cacheHits()));

            // ==================================================================
            // 8. CHANGE LISTENERS
            // ==================================================================
            section("8. Change Listeners");

            // Global listener -- fires for any flag change.
            List<FlagChangeEvent> globalChanges = new ArrayList<>();
            client.flags().onChange(event -> {
                globalChanges.add(event);
                System.out.println("    [GLOBAL CHANGE] flag=" + event.id()
                        + ", source=" + event.source());
            });
            step("Global change listener registered");

            // Per-flag listener -- fires only for the specific flag.
            List<FlagChangeEvent> maintenanceChanges = new ArrayList<>();
            client.flags().onChange("maintenance-mode-rt", event -> {
                maintenanceChanges.add(event);
                System.out.println("    [MAINTENANCE CHANGE] source=" + event.source());
            });
            step("Per-flag change listener registered for 'maintenance-mode-rt'");

            // Trigger a change via the management API and wait for propagation.
            step("Updating maintenance-mode description via management API...");
            maintenance.setDescription("Updated: now with change listener tracking");
            maintenance.save();

            Thread.sleep(2000);

            step("Global changes received: " + globalChanges.size());
            step("Maintenance-specific changes received: " + maintenanceChanges.size());

            // ==================================================================
            // 9. MANUAL REFRESH
            // ==================================================================
            section("9. Manual Refresh");

            // refresh() re-fetches all flag definitions and clears the cache.
            // Useful if you suspect stale data or want to force a sync.
            client.flags().refresh();
            step("Manual refresh completed");

            // Re-evaluate after refresh.
            Boolean maintenanceAfterRefresh = maintenanceHandle.get();
            step("maintenance-mode after refresh = " + maintenanceAfterRefresh);

            // ==================================================================
            // 10. CLEANUP
            // ==================================================================
            section("10. Cleanup");

            for (String flagKey : createdFlagKeys) {
                client.flags().management().delete(flagKey);
                step("Deleted flag: " + flagKey);
            }

        } // SmplClient.close() called here

        // ======================================================================
        // DONE
        // ======================================================================
        section("ALL DONE");
        System.out.println("  The Flags Runtime showcase completed successfully.");
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
