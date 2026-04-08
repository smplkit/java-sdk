package com.smplkit.examples;

import com.smplkit.LogLevel;
import com.smplkit.SmplClient;
import com.smplkit.logging.Logger;
import com.smplkit.logging.LoggingClient;

import java.util.logging.Level;

/**
 * Smpl Logging Runtime Showcase
 * ==============================
 *
 * <p>Demonstrates runtime logging control features:</p>
 * <ul>
 *   <li>Calling {@link LoggingClient#start()} to opt in to managed logging</li>
 *   <li>Registering change listeners (global and key-scoped)</li>
 *   <li>Observing JUL logger levels being managed by smplkit</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.LoggingRuntimeShowcase
 * </pre>
 */
public class LoggingRuntimeShowcase {

    public static void main(String[] args) throws Exception {
        section("1. SDK Initialization");

        try (SmplClient client = SmplClient.builder()
                .environment("production")
                .service("logging-runtime-showcase")
                .build()) {

            step("SmplClient initialized");

            // ==================================================================
            // 2. REGISTER CHANGE LISTENERS
            // ==================================================================
            section("2. Register Change Listeners");

            // Global listener: fires for any logger change
            client.logging().onChange(event ->
                    step("[GLOBAL] Logger '" + event.key() + "' changed to "
                            + event.level() + " (source: " + event.source() + ")")
            );
            step("Registered global change listener");

            // Key-scoped listener: fires only for "com.acme.payments"
            client.logging().onChange("com.acme.payments", event ->
                    step("[SCOPED] Payments logger changed to " + event.level())
            );
            step("Registered key-scoped listener for 'com.acme.payments'");

            // ==================================================================
            // 3. START RUNTIME LOGGING CONTROL
            // ==================================================================
            section("3. Start Runtime Logging Control");

            // Create some JUL loggers before start() -- they will be discovered
            java.util.logging.Logger appLogger = java.util.logging.Logger.getLogger("com.acme.app");
            java.util.logging.Logger payLogger = java.util.logging.Logger.getLogger("com.acme.payments");
            step("Created JUL loggers: com.acme.app, com.acme.payments");

            // start() discovers existing JUL loggers, fetches managed levels,
            // and applies them. It's idempotent -- safe to call multiple times.
            client.logging().start();
            step("Logging runtime started");

            // Calling start() again is a no-op
            client.logging().start();
            step("Second start() call was safely ignored (idempotent)");

            // ==================================================================
            // 4. OBSERVE JUL LEVEL MAPPING
            // ==================================================================
            section("4. JUL Level Mapping");

            step("LogLevel.TRACE  -> JUL " + julLevelName(LogLevel.TRACE));
            step("LogLevel.DEBUG  -> JUL " + julLevelName(LogLevel.DEBUG));
            step("LogLevel.INFO   -> JUL " + julLevelName(LogLevel.INFO));
            step("LogLevel.WARN   -> JUL " + julLevelName(LogLevel.WARN));
            step("LogLevel.ERROR  -> JUL " + julLevelName(LogLevel.ERROR));
            step("LogLevel.FATAL  -> JUL " + julLevelName(LogLevel.FATAL));
            step("LogLevel.SILENT -> JUL " + julLevelName(LogLevel.SILENT));

            // ==================================================================
            // 5. CREATE A MANAGED LOGGER
            // ==================================================================
            section("5. Create and Manage a Logger");

            Logger mgr = client.logging().new_("com.acme.payments", "Payments Logger", true);
            mgr.setLevel(LogLevel.DEBUG);
            mgr.save();
            step("Created managed logger: key=" + mgr.getKey()
                    + ", level=" + mgr.getLevel()
                    + ", managed=" + mgr.isManaged());

            // Update level
            mgr.setLevel(LogLevel.WARN);
            mgr.setEnvironmentLevel("production", LogLevel.ERROR);
            mgr.save();
            step("Updated logger: level=" + mgr.getLevel()
                    + ", environments=" + mgr.getEnvironments());

            // Clean up
            client.logging().delete(mgr.getKey());
            step("Deleted managed logger");
        }

        section("ALL DONE");
        System.out.println("  The Logging Runtime showcase completed successfully.");
    }

    private static String julLevelName(LogLevel level) {
        Level julLevel = LoggingClient.smplToJulLevel(level);
        return julLevel.getName() + " (" + julLevel.intValue() + ")";
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
