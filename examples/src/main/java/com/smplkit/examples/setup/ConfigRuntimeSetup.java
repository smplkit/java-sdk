package com.smplkit.examples.setup;

import com.smplkit.config.Config;
import com.smplkit.errors.NotFoundError;
import com.smplkit.management.SmplManagementClient;

import java.util.List;

/** Setup and simulation helpers for {@code ConfigRuntimeShowcase}. */
public final class ConfigRuntimeSetup {

    private static final List<String> DEMO_CONFIG_IDS = List.of(
            "showcase-billing", "showcase-common", "showcase-database");

    private ConfigRuntimeSetup() {}

    public static void simulateAdminOverride(SmplManagementClient manage) {
        // Real customers never read back through the management API
        // immediately after binding via the runtime client — this is a
        // simulation-only step. Push pending runtime-side registrations
        // through so the lookup below can find the freshly-declared config.
        manage.config.flush();
        Config billing = manage.config.get("showcase-billing");
        billing.setNumber("plan.max_seats", 25, "production");
        billing.save();
    }

    public static void cleanup(SmplManagementClient manage) {
        for (String configId : DEMO_CONFIG_IDS) {
            try { manage.config.delete(configId); } catch (NotFoundError ignored) {}
        }
    }
}
