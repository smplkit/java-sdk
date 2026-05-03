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
import com.smplkit.examples.setup.LoggingManagementSetup;
import com.smplkit.logging.Logger;
import com.smplkit.management.SmplManagementClient;

public final class LoggingManagementShowcase {

    public static void main(String[] args) {
        // create the client (use SmplManagementClient for synchronous use)
        try (SmplManagementClient manage = SmplManagementClient.create()) {
            LoggingManagementSetup.setup(manage);

            // create a parent logger with a default level
            Logger root = manage.loggers.new_("showcase");
            root.setLevel(LogLevel.INFO);
            root.save();
            System.out.println("Created: " + root.getId() + " (level=" + root.getLevel() + ")");
            assert "INFO".equals(root.getLevel());

            // child logger with no level (inherits from parent)
            Logger db = manage.loggers.new_("showcase.db");
            db.save();
            System.out.println("Created: " + db.getId() + " (inherits)");
            assert db.getLevel() == null;

            // child logger with explicit level (overrides parent)
            Logger payments = manage.loggers.new_("showcase.payments");
            payments.setLevel(LogLevel.WARN);
            payments.save();
            System.out.println("Created: " + payments.getId()
                    + " (level=" + payments.getLevel() + ")");
            assert "WARN".equals(payments.getLevel());

            // override log level for different environments
            root.setLevel(LogLevel.ERROR, "production");
            root.setLevel(LogLevel.DEBUG, "staging");
            root.save();
            System.out.println("Set environment overrides: " + root.environments());
            assert root.environments().get("production").level() == LogLevel.ERROR;
            assert root.environments().get("staging").level() == LogLevel.DEBUG;

            // clear environment override (inherits from the default level again)
            root.clearLevel("staging");
            root.save();
            System.out.println("Cleared staging override: " + root.environments());
            assert !root.environments().containsKey("staging");
            assert root.environments().get("production").level() == LogLevel.ERROR;

            // fetch a logger by id
            Logger fetched = manage.loggers.get("showcase");
            assert "INFO".equals(fetched.getLevel());

            LoggingManagementSetup.cleanup(manage);
            System.out.println("Done!");
        }
    }
}
