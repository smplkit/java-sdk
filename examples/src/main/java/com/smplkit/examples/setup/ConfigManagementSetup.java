package com.smplkit.examples.setup;

import com.smplkit.errors.NotFoundError;
import com.smplkit.management.SmplManagementClient;

import java.util.List;

/** Setup / cleanup helpers for {@code ConfigManagementShowcase}. */
public final class ConfigManagementSetup {

    private static final List<String> DEMO_ENVIRONMENTS = List.of("staging", "production");
    private static final List<String> DEMO_CONFIG_IDS =
            List.of("showcase-user-service", "showcase-common");

    private ConfigManagementSetup() {}

    public static void setup(SmplManagementClient manage) {
        var existing = manage.environments.list().stream()
                .map(com.smplkit.management.Environment::getId).toList();
        for (String envId : DEMO_ENVIRONMENTS) {
            if (!existing.contains(envId)) {
                manage.environments.new_(envId, capitalize(envId), null, null).save();
            }
        }
        cleanup(manage);
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
