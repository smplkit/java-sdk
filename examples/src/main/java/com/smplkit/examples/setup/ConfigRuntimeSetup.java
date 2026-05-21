package com.smplkit.examples.setup;

import com.smplkit.config.Config;
import com.smplkit.errors.NotFoundError;
import com.smplkit.management.SmplManagementClient;

import java.util.List;

/**
 * Setup, simulation, and cleanup helpers for {@code ConfigRuntimeShowcase}.
 *
 * <p>The runtime showcase is intentionally runtime-only — declarations,
 * typed getters, change listeners. In a real deployment the configs
 * would either already exist (admin-curated) or be created by the SDK's
 * discovery on first run. Here we pre-create them through the management
 * API so the showcase can also demonstrate a live admin override end-to-end
 * in a single process.</p>
 */
public final class ConfigRuntimeSetup {

    private static final List<String> DEMO_CONFIG_IDS = List.of(
            "showcase-billing", "showcase-common");

    private ConfigRuntimeSetup() {}

    public static void setup(SmplManagementClient manage) {
        cleanup(manage);

        Config common = manage.config.new_(
                "showcase-common", null,
                "Shared defaults for showcase services.", (String) null);
        common.setString("app.name", "Acme SaaS");
        common.setString("support.email", "support@acme.dev");
        common.save();

        Config billing = manage.config.new_(
                "showcase-billing", null,
                "Plan-limit configuration for billing.", "showcase-common");
        billing.setNumber("plan.max_seats", 5);
        billing.setNumber("plan.trial_days", 14);
        billing.setString("plan.tier", "free");
        billing.save();
    }

    public static void simulateAdminOverride(SmplManagementClient manage) {
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
