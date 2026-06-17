/*
 * Demonstrates the smplkit management SDK for Smpl Config.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigManagementShowcase
 */
package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.Config;
import com.smplkit.examples.setup.ConfigManagementSetup;

public final class ConfigManagementShowcase {

    public static void main(String[] args) {

        // or AsyncSmplClient for asynchronous use
        SmplClient client = SmplClient.create();
        try {
            ConfigManagementSetup.setup(client);

            // create a "parent" configuration that all other configs inherit from
            Config shared = client.config.new_(
                    "showcase-common", "Showcase Common",
                    "Showcase-only shared configuration.", (String) null);
            shared.setString("app_name", "Acme SaaS Platform");
            shared.setString("support_email", "support@acme.dev");
            shared.setNumber("max_retries", 3);
            shared.setNumber("request_timeout_ms", 5000);
            shared.setNumber("pagination_default_page_size", 25);
            shared.setNumber("max_retries", 5, "production");
            shared.setNumber("request_timeout_ms", 10000, "production");
            shared.save();
            System.out.println("Created config: " + shared.getId());

            // create a config (inherits from showcase-common)
            Config userService = client.config.new_(
                    "showcase-user-service", "Showcase User Service",
                    "Configuration for the user microservice.", shared);
            userService.setString("database.host", "localhost");
            userService.setNumber("database.port", 5432);
            userService.setString("database.name", "users_dev");
            userService.setNumber("database.pool_size", 5);
            userService.setNumber("cache_ttl_seconds", 300);
            userService.setBoolean("enable_signup", true);
            userService.setNumber("pagination_default_page_size", 50);
            userService.save();

            // update a config
            userService.setString("database.host",
                    "prod-users-rds.internal.acme.dev", "production");
            userService.setString("database.name", "users_prod", "production");
            userService.setNumber("database.pool_size", 20, "production");
            userService.setNumber("cache_ttl_seconds", 600, "production");
            userService.setBoolean("enable_signup", false, "production");
            userService.save();
            System.out.println("Updated config: " + userService.getId());

            // list configs
            for (Config cfg : client.config.list()) {
                String parentInfo = cfg.getParent() != null
                        ? " (parent: " + cfg.getParent() + ")" : " (root)";
                System.out.println("  " + cfg.getId() + parentInfo);
            }

            // get a config
            Config fetched = client.config.get("showcase-user-service");
            System.out.println("Fetched: id=" + fetched.getId()
                    + ", name=" + fetched.getName());
            System.out.println("  description=" + fetched.getDescription());
            System.out.println("  parent=" +
                    (fetched.getParent() != null ? fetched.getParent() : "(none)"));
            System.out.println("  items: " + fetched.items().keySet());

            // delete configs
            userService.delete();
            shared.delete();
            System.out.println("Deleted configs");

            System.out.println("Done!");
        } finally {
            // Always tear down — even if an error occurred above — so a
            // failed run never leaves orphaned configs for the next run.
            ConfigManagementSetup.cleanup(client);
            client.close();
        }
    }
}
