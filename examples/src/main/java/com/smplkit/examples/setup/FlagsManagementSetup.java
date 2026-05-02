package com.smplkit.examples.setup;

import com.smplkit.errors.NotFoundError;
import com.smplkit.management.SmplManagementClient;

import java.util.List;

/** Setup / cleanup helpers for {@code FlagsManagementShowcase}. */
public final class FlagsManagementSetup {

    private static final List<String> DEMO_ENVIRONMENTS = List.of("staging", "production");
    private static final List<String> DEMO_FLAG_IDS =
            List.of("checkout-v2", "banner-color", "max-retries", "ui-theme");

    private FlagsManagementSetup() {}

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
        for (String flagId : DEMO_FLAG_IDS) {
            try { manage.flags.delete(flagId); } catch (NotFoundError ignored) {}
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
