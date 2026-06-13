/*
 * Demonstrates the smplkit management SDK for Smpl Logging.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.LoggingManagementShowcase
 */
package com.smplkit.examples;

import com.smplkit.LogLevel;
import com.smplkit.SmplClient;
import com.smplkit.examples.setup.LoggingManagementSetup;
import com.smplkit.logging.Logger;

public final class LoggingManagementShowcase {

    public static void main(String[] args) {

        // or AsyncSmplClient for asynchronous use
        try (SmplClient client = SmplClient.create()) {
            LoggingManagementSetup.setup(client);

            // create a parent logger with a default level
            Logger root = client.logging.loggers.new_("showcase");
            root.setLevel(LogLevel.INFO);
            root.save();
            System.out.println("Created: " + root.getId() + " (level=" + root.getLevel() + ")");
            assert "INFO".equals(root.getLevel());

            // child logger with no level (inherits from parent)
            Logger db = client.logging.loggers.new_("showcase.db");
            db.save();
            System.out.println("Created: " + db.getId() + " (inherits)");
            assert db.getLevel() == null;

            // child logger with explicit level (overrides parent)
            Logger payments = client.logging.loggers.new_("showcase.payments");
            payments.setLevel(LogLevel.WARN);
            payments.save();
            System.out.println("Created: " + payments.getId()
                    + " (level=" + payments.getLevel() + ")");
            assert "WARN".equals(payments.getLevel());

            // override log level for the production environment
            root.setLevel(LogLevel.ERROR, "production");
            root.save();
            System.out.println("Set environment overrides: " + root.environments());
            assert root.environments().get("production").level() == LogLevel.ERROR;

            // clear environment override (inherits from the default level again)
            root.clearLevel("production");
            root.save();
            System.out.println("Cleared production override: " + root.environments());
            assert !root.environments().containsKey("production");

            // get a logger
            Logger fetched = client.logging.loggers.get("showcase");
            assert "INFO".equals(fetched.getLevel());

            LoggingManagementSetup.cleanup(client);
            System.out.println("Done!");
        }
    }
}
