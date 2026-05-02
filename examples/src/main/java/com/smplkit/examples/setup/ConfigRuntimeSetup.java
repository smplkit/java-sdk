package com.smplkit.examples.setup;

import com.smplkit.config.Config;
import com.smplkit.errors.NotFoundError;
import com.smplkit.management.SmplManagementClient;

import java.util.List;

/** Setup / cleanup helpers for {@code ConfigRuntimeShowcase}. */
public final class ConfigRuntimeSetup {

    private static final List<String> DEMO_ENVIRONMENTS = List.of("staging", "production");
    private static final List<String> DEMO_CONFIG_IDS = List.of(
            "showcase-user-service",
            "showcase-auth-module",
            "showcase-common");

    private ConfigRuntimeSetup() {}

    public static void setup(SmplManagementClient manage) {
        var existing = manage.environments.list().stream()
                .map(com.smplkit.management.Environment::getId).toList();
        for (String envId : DEMO_ENVIRONMENTS) {
            if (!existing.contains(envId)) {
                manage.environments.new_(envId, capitalize(envId), null, null).save();
            }
        }
        cleanup(manage);

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
        userService.setString("database.host",
                "prod-users-rds.internal.acme.dev", "production");
        userService.setString("database.name", "users_prod", "production");
        userService.setNumber("database.pool_size", 20, "production");
        userService.setNumber("cache_ttl_seconds", 600, "production");
        userService.setBoolean("enable_signup", false, "production");
        userService.save();

        Config authModule = manage.config.new_(
                "showcase-auth-module", "Showcase Auth Module",
                "Authentication module within the user service.", shared);
        authModule.setNumber("session_ttl_minutes", 60);
        authModule.setBoolean("mfa_enabled", false);
        authModule.setNumber("session_ttl_minutes", 30, "production");
        authModule.setBoolean("mfa_enabled", true, "production");
        authModule.save();
    }

    public static void cleanup(SmplManagementClient manage) {
        for (String configId : DEMO_CONFIG_IDS) {
            try { manage.config.delete(configId); } catch (NotFoundError ignored) {}
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
