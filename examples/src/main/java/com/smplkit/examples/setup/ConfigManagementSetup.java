package com.smplkit.examples.setup;

import com.smplkit.errors.NotFoundError;
import com.smplkit.management.SmplManagementClient;

import java.util.List;

/** Setup / cleanup helpers for {@code ConfigManagementShowcase}. */
public final class ConfigManagementSetup {

    private static final List<String> DEMO_CONFIG_IDS =
            List.of("showcase-user-service", "showcase-common");

    private ConfigManagementSetup() {}

    public static void setup(SmplManagementClient manage) {
        cleanup(manage);
    }

    public static void cleanup(SmplManagementClient manage) {
        for (String configId : DEMO_CONFIG_IDS) {
            try { manage.config.delete(configId); } catch (NotFoundError ignored) {}
        }
    }
}
