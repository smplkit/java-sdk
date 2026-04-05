package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.flags.Context;
import com.smplkit.flags.CreateFlagParams;
import com.smplkit.flags.FlagChangeEvent;
import com.smplkit.flags.FlagHandle;
import com.smplkit.flags.FlagResource;
import com.smplkit.flags.FlagStats;
import com.smplkit.flags.FlagType;
import com.smplkit.flags.Rule;
import com.smplkit.flags.UpdateFlagParams;

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
 *   <li>Connecting to a runtime environment via {@code connect(environment)}</li>
 *   <li>Typed flag handles: {@code boolFlag}, {@code stringFlag}, {@code numberFlag}, {@code jsonFlag}</li>
 *   <li>Context providers via {@code setContextProvider}</li>
 *   <li>Explicit context override in {@code FlagHandle.get(List)}</li>
 *   <li>Resolution caching and diagnostic stats</li>
 *   <li>Global and per-flag change listeners via {@code onChange}</li>
 *   <li>Manual refresh and disconnect lifecycle</li>
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
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsRuntimeShowcase
 * </pre>
 */
public class FlagsRuntimeShowcase {

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
                .environment("staging")
                .service("flags-runtime-showcase")
                .build()) {

            step("SmplClient initialized");

            // Track created flags for cleanup
            List<String> createdFlagIds = new ArrayList<>();

            // ==================================================================
            // 2. SET UP FLAGS WITH TARGETING RULES
            // ==================================================================
            section("2. Create Flags with Targeting Rules");

            // Boolean flag with environment-specific rules.
            FlagResource maintenance = client.flags().create(
                    CreateFlagParams.builder("maintenance-mode-rt", "Maintenance Mode", FlagType.BOOLEAN)
                            .defaultValue(false)
                            .description("Puts the application into maintenance mode.")
                            .build());
            createdFlagIds.add(maintenance.id());
            step("Created boolean flag: " + maintenance.key());

            // Add a rule that enables maintenance mode for staging.
            maintenance = maintenance.addRule(new Rule("Enable in staging")
                    .environment("staging")
                    .serve(true)
                    .build());
            step("Added rule: maintenance enabled in staging");

            // String flag.
            FlagResource greeting = client.flags().create(
                    CreateFlagParams.builder("greeting-rt", "Greeting Message", FlagType.STRING)
                            .defaultValue("Hello!")
                            .description("Personalized greeting message for different user segments.")
                            .values(List.of(
                                    Map.of("name", "Default", "value", "Hello!"),
                                    Map.of("name", "Enterprise", "value", "Welcome back, valued partner!"),
                                    Map.of("name", "Trial", "value", "Welcome! Your trial expires soon.")
                            ))
                            .build());
            createdFlagIds.add(greeting.id());
            step("Created string flag: " + greeting.key());

            greeting = greeting.addRule(new Rule("Enterprise greeting")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .serve("Welcome back, valued partner!")
                    .build());
            step("Added rule: enterprise greeting in staging");

            // Numeric flag.
            FlagResource pageSize = client.flags().create(
                    CreateFlagParams.builder("page-size-rt", "Page Size", FlagType.NUMERIC)
                            .defaultValue(25)
                            .description("Number of items per page in list views.")
                            .build());
            createdFlagIds.add(pageSize.id());
            step("Created numeric flag: " + pageSize.key());

            pageSize = pageSize.addRule(new Rule("Larger pages for power users")
                    .environment("staging")
                    .when("user.role", "==", "admin")
                    .serve(100)
                    .build());
            step("Added rule: 100 items per page for admins in staging");

            // JSON flag.
            FlagResource layout = client.flags().create(
                    CreateFlagParams.builder("layout-config-rt", "Layout Config", FlagType.JSON)
                            .defaultValue(Map.of(
                                    "columns", 2,
                                    "showHeader", true,
                                    "compact", false
                            ))
                            .description("Layout configuration controlling the dashboard appearance.")
                            .build());
            createdFlagIds.add(layout.id());
            step("Created JSON flag: " + layout.key());

            layout = layout.addRule(new Rule("Compact layout for mobile users")
                    .environment("staging")
                    .when("user.device", "==", "mobile")
                    .serve(Map.of("columns", 1, "showHeader", false, "compact", true))
                    .build());
            step("Added rule: compact layout for mobile in staging");

            // ==================================================================
            // 3. CONNECT TO RUNTIME
            // ==================================================================
            section("3. Connect to Runtime Environment");

            // connect() fetches all flag definitions for the given environment
            // and starts listening for real-time changes via WebSocket.
            client.connect();
            step("Connected to 'staging' environment");
            step("Connection status: " + client.flags().connectionStatus());

            // ==================================================================
            // 4. TYPED FLAG HANDLES
            // ==================================================================
            section("4. Typed Flag Handles");

            // Declare typed handles — these are lightweight wrappers that provide
            // type-safe access and a code-level default if the flag is missing.
            FlagHandle<Boolean> maintenanceHandle = client.flags().boolFlag("maintenance-mode-rt", false);
            FlagHandle<String> greetingHandle = client.flags().stringFlag("greeting-rt", "Hi there!");
            FlagHandle<Number> pageSizeHandle = client.flags().numberFlag("page-size-rt", 25);
            FlagHandle<Object> layoutHandle = client.flags().jsonFlag("layout-config-rt",
                    Map.of("columns", 2, "showHeader", true, "compact", false));

            step("Declared 4 typed handles");
            step("  boolFlag: " + maintenanceHandle.key() + " (default=" + maintenanceHandle.defaultValue() + ")");
            step("  stringFlag: " + greetingHandle.key() + " (default=" + greetingHandle.defaultValue() + ")");
            step("  numberFlag: " + pageSizeHandle.key() + " (default=" + pageSizeHandle.defaultValue() + ")");
            step("  jsonFlag: " + layoutHandle.key() + " (default=" + layoutHandle.defaultValue() + ")");

            // ==================================================================
            // 5. CONTEXT PROVIDER
            // ==================================================================
            section("5. Context Provider");

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
            // 6. EXPLICIT CONTEXT OVERRIDE
            // ==================================================================
            section("6. Explicit Context Override");

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
            // 7. RESOLUTION CACHING AND STATS
            // ==================================================================
            section("7. Resolution Caching and Stats");

            FlagStats statsBefore = client.flags().stats();
            step("Stats before repeated reads: hits=" + statsBefore.cacheHits()
                    + ", misses=" + statsBefore.cacheMisses());

            // Perform many reads — all should be served from cache.
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

            // Global listener — fires for any flag change.
            List<FlagChangeEvent> globalChanges = new ArrayList<>();
            client.flags().onChange(event -> {
                globalChanges.add(event);
                System.out.println("    [GLOBAL CHANGE] flag=" + event.key()
                        + ", source=" + event.source());
            });
            step("Global change listener registered");

            // Per-flag listener — fires only for the specific flag.
            List<FlagChangeEvent> maintenanceChanges = new ArrayList<>();
            maintenanceHandle.onChange(event -> {
                maintenanceChanges.add(event);
                System.out.println("    [MAINTENANCE CHANGE] source=" + event.source());
            });
            step("Per-flag change listener registered for 'maintenance-mode-rt'");

            // Trigger a change via the management API and wait for propagation.
            step("Updating maintenance-mode default via management API...");
            maintenance = maintenance.update(UpdateFlagParams.builder()
                    .description("Updated: now with change listener tracking")
                    .build());

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
            step("Connection status after refresh: " + client.flags().connectionStatus());

            // Re-evaluate after refresh.
            Boolean maintenanceAfterRefresh = maintenanceHandle.get();
            step("maintenance-mode after refresh = " + maintenanceAfterRefresh);

            // ==================================================================
            // 10. DISCONNECT
            // ==================================================================
            section("10. Disconnect");

            client.flags().disconnect();
            step("Disconnected from runtime");
            step("Connection status: " + client.flags().connectionStatus());

            // After disconnect, handles return their code-level defaults.
            Boolean maintenanceAfterDisconnect = maintenanceHandle.get();
            step("maintenance-mode after disconnect = " + maintenanceAfterDisconnect);
            // Expected: false (code-level default, since no longer connected)

            // ==================================================================
            // 11. CLEANUP
            // ==================================================================
            section("11. Cleanup");

            for (String flagId : createdFlagIds) {
                client.flags().delete(flagId);
                step("Deleted flag: " + flagId);
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
        System.out.println("  → " + description);
    }
}
