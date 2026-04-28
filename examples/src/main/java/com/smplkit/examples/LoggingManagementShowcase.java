package com.smplkit.examples;

import com.smplkit.LogLevel;
import com.smplkit.SmplClient;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.logging.LogGroup;
import com.smplkit.logging.Logger;
import com.smplkit.logging.LoggerSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Smpl Logging Management Showcase
 * ==================================
 *
 * <p>Demonstrates the management-plane API for loggers and log groups:</p>
 * <ul>
 *   <li>Creating loggers and log groups</li>
 *   <li>Fetching a logger/group by id and listing all</li>
 *   <li>Updating via field mutation and {@link Logger#save()}</li>
 *   <li>Environment-level overrides</li>
 *   <li>Cleanup of all created resources</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 *   ./gradlew :examples:run -PmainClass=com.smplkit.examples.LoggingManagementShowcase
 * </pre>
 */
public class LoggingManagementShowcase {

    public static void main(String[] args) throws Exception {
        section("1. SDK Initialization");

        try (SmplClient client = SmplClient.builder()
                .environment("production")
                .service("logging-showcase")
                .build()) {

            step("SmplClient initialized");

            List<String> createdLoggerKeys = new ArrayList<>();
            List<String> createdGroupKeys = new ArrayList<>();

            // Clean up any leftover resources from a previous failed run.
            for (String key : List.of("payment-service", "audit-logger")) {
                try {
                    client.logging().management().delete(key);
                    step("Pre-cleanup: deleted leftover logger " + key);
                } catch (SmplNotFoundException ignored) { }
            }
            for (String key : List.of("infra-db", "infra")) {
                try {
                    client.logging().management().deleteGroup(key);
                    step("Pre-cleanup: deleted leftover group " + key);
                } catch (SmplNotFoundException ignored) { }
            }

            // ==================================================================
            // 2. CREATE LOG GROUPS
            // ==================================================================
            section("2. Create Log Groups");

            LogGroup infraGroup = client.logging().management().newGroup("infra");
            infraGroup.setLevel(LogLevel.WARN);
            infraGroup.save();
            createdGroupKeys.add(infraGroup.getId());
            step("Created group: id=" + infraGroup.getId()
                    + ", level=" + infraGroup.getLevel());

            LogGroup dbGroup = client.logging().management().newGroup("infra-db");
            dbGroup.setName("Database");
            dbGroup.setLevel(LogLevel.ERROR);
            dbGroup.save();
            createdGroupKeys.add(dbGroup.getId());
            step("Created group: id=" + dbGroup.getId()
                    + ", level=" + dbGroup.getLevel());

            // ==================================================================
            // 3. CREATE LOGGERS
            // ==================================================================
            section("3. Create Loggers");

            Logger paymentLogger = client.logging().management().new_("payment-service", "Payment Service", true);
            paymentLogger.setLevel(LogLevel.INFO);
            paymentLogger.setGroup(infraGroup.getId());
            paymentLogger.save();
            createdLoggerKeys.add(paymentLogger.getId());
            step("Created logger: id=" + paymentLogger.getId()
                    + ", managed=" + paymentLogger.isManaged());

            Logger auditLogger = client.logging().management().new_("audit-logger", "Audit Logger", true);
            auditLogger.setLevel(LogLevel.DEBUG);
            auditLogger.save();
            createdLoggerKeys.add(auditLogger.getId());
            step("Created logger: id=" + auditLogger.getId());

            // ==================================================================
            // 4. GET AND LIST
            // ==================================================================
            section("4. Get and List");

            Logger fetched = client.logging().management().get("payment-service");
            step("Fetched logger: id=" + fetched.getId()
                    + ", name=" + fetched.getName()
                    + ", level=" + fetched.getLevel());

            List<Logger> allLoggers = client.logging().management().list();
            step("Total loggers: " + allLoggers.size());

            LogGroup fetchedGroup = client.logging().management().getGroup("infra");
            step("Fetched group: id=" + fetchedGroup.getId()
                    + ", level=" + fetchedGroup.getLevel());

            List<LogGroup> allGroups = client.logging().management().listGroups();
            step("Total groups: " + allGroups.size());

            // ==================================================================
            // 5. UPDATE VIA MUTATION + SAVE
            // ==================================================================
            section("5. Update via mutation + save()");

            paymentLogger.setLevel(LogLevel.WARN);
            paymentLogger.setEnvironmentLevel("staging", LogLevel.DEBUG);
            paymentLogger.save();
            step("Updated payment logger: level=" + paymentLogger.getLevel()
                    + ", environments=" + paymentLogger.getEnvironments());

            infraGroup.setLevel(LogLevel.ERROR);
            infraGroup.save();
            step("Updated infra group: level=" + infraGroup.getLevel());

            // ==================================================================
            // 6. LEVEL CONVENIENCE METHODS
            // ==================================================================
            section("6. Level Convenience Methods");

            auditLogger.setLevel(LogLevel.TRACE);
            step("Set level to TRACE: " + auditLogger.getLevel());

            auditLogger.setEnvironmentLevel("production", LogLevel.ERROR);
            auditLogger.setEnvironmentLevel("staging", LogLevel.DEBUG);
            step("Set env levels: " + auditLogger.getEnvironments());

            auditLogger.clearEnvironmentLevel("staging");
            step("Cleared staging env level: " + auditLogger.getEnvironments());

            auditLogger.clearAllEnvironmentLevels();
            step("Cleared all env levels: " + auditLogger.getEnvironments());

            auditLogger.clearLevel();
            step("Cleared base level: " + auditLogger.getLevel());

            // ==================================================================
            // 7b. REGISTER SYNTHETIC LOGGER SOURCES
            // ==================================================================
            // registerSources() accepts explicit (service, environment) overrides —
            // useful for sample-data seeding, cross-tenant migration, and test fixtures.
            // ==================================================================
            section("7b. Register synthetic logger sources");

            client.logging().management().registerSources(java.util.List.of(
                    new LoggerSource("sqlalchemy.engine", "user-service", "production", LogLevel.WARN),
                    new LoggerSource("sqlalchemy.engine", "payment-service", "production", LogLevel.WARN),
                    new LoggerSource("httpx", "checkout-service", "staging", LogLevel.INFO)
            ));
            step("3 sources registered");

            // ==================================================================
            // 8. CLEANUP
            // ==================================================================
            section("8. Cleanup");

            for (String key : createdLoggerKeys) {
                client.logging().management().delete(key);
                step("Deleted logger: " + key);
            }
            // Delete child group first (referential integrity)
            for (int i = createdGroupKeys.size() - 1; i >= 0; i--) {
                client.logging().management().deleteGroup(createdGroupKeys.get(i));
                step("Deleted group: " + createdGroupKeys.get(i));
            }
        }

        section("ALL DONE");
        System.out.println("  The Logging Management showcase completed successfully.");
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
