package com.smplkit.examples.setup;

import com.smplkit.SmplClient;
import com.smplkit.errors.NotFoundError;

import java.util.List;

/** Setup / cleanup helpers for {@code ConfigManagementShowcase}. */
public final class ConfigManagementSetup {

    private static final List<String> DEMO_CONFIG_IDS =
            List.of("showcase-user-service", "showcase-common");

    private ConfigManagementSetup() {}

    public static void setup(SmplClient client) {
        cleanup(client);
    }

    public static void cleanup(SmplClient client) {
        for (String configId : DEMO_CONFIG_IDS) {
            try { client.config.delete(configId); } catch (NotFoundError ignored) {}
        }
    }
}
