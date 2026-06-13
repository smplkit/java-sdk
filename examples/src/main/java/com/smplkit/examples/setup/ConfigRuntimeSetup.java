package com.smplkit.examples.setup;

import com.smplkit.SmplClient;
import com.smplkit.config.Config;
import com.smplkit.errors.NotFoundError;

import java.util.List;

/** Setup and simulation helpers for {@code ConfigRuntimeShowcase}. */
public final class ConfigRuntimeSetup {

    private static final List<String> DEMO_CONFIG_IDS = List.of(
            "showcase-billing", "showcase-common", "showcase-database");

    private ConfigRuntimeSetup() {}

    public static void simulateAdminOverride(SmplClient client) {
        // Push pending runtime-side registrations through so the lookup below
        // can find the freshly-declared config.
        client.config.flush();
        Config billing = client.config.get("showcase-billing");
        billing.setNumber("plan.max_seats", 25, "production");
        billing.save();
    }

    public static void cleanup(SmplClient client) {
        for (String configId : DEMO_CONFIG_IDS) {
            try { client.config.delete(configId); } catch (NotFoundError ignored) {}
        }
    }
}
