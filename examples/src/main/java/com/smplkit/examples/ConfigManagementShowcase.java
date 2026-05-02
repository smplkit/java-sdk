/*
 * Demonstrates the smplkit management SDK for Smpl Config.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *     - The smplkit Config service running and reachable
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigManagementShowcase
 */
package com.smplkit.examples;

import com.smplkit.config.Config;
import com.smplkit.examples.setup.ConfigManagementSetup;
import com.smplkit.management.SmplManagementClient;

public final class ConfigManagementShowcase {

    public static void main(String[] args) {
        // create the client (use SmplManagementClient for synchronous use)
        try (SmplManagementClient manage = SmplManagementClient.create()) {
            ConfigManagementSetup.setup(manage);

            // create a "parent" configuration that all other configs inherit from
            Config shared = manage.config.new_(
                    "showcase-common", "Showcase Common",
                    "Showcase-only shared configuration.", (String) null);
            shared.setString("app_name", "Acme SaaS Platform");
            shared.setString("support_email", "support@acme.dev");
            shared.setNumber("max_retries", 3);
            shared.setNumber("request_timeout_ms", 5000);
            shared.setNumber("pagination_default_page_size", 25);
            shared.setNumber("max_retries", 5, "production");
            shared.setNumber("request_timeout_ms", 10000, "production");
            shared.setNumber("max_retries", 2, "staging");
            shared.save();
            System.out.println("Created config: " + shared.getId());

            // create a config (inherits from showcase-common)
            Config userService = manage.config.new_(
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
            for (Config cfg : manage.config.list()) {
                String parentInfo = cfg.getParent() != null
                        ? " (parent: " + cfg.getParent() + ")" : " (root)";
                System.out.println("  " + cfg.getId() + parentInfo);
            }

            // get a config
            Config fetched = manage.config.get("showcase-user-service");
            System.out.println("Fetched: id=" + fetched.getId()
                    + ", name=" + fetched.getName());
            System.out.println("  description=" + fetched.getDescription());
            System.out.println("  parent=" +
                    (fetched.getParent() != null ? fetched.getParent() : "(none)"));
            System.out.println("  items: " + fetched.getResolvedItems().keySet());

            // delete configs
            manage.config.delete(userService.getId());
            manage.config.delete(shared.getId());
            System.out.println("Deleted configs");

            // cleanup
            ConfigManagementSetup.cleanup(manage);
            System.out.println("Done!");
        }
    }
}
