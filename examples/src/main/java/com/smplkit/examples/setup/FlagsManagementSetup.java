package com.smplkit.examples.setup;

import com.smplkit.errors.NotFoundError;
import com.smplkit.management.SmplManagementClient;

import java.util.List;

/** Setup / cleanup helpers for {@code FlagsManagementShowcase}. */
public final class FlagsManagementSetup {

    private static final List<String> DEMO_FLAG_IDS =
            List.of("checkout-v2", "banner-color", "max-retries", "ui-theme");

    private FlagsManagementSetup() {}

    public static void setup(SmplManagementClient manage) {
        cleanup(manage);
    }

    public static void cleanup(SmplManagementClient manage) {
        for (String flagId : DEMO_FLAG_IDS) {
            try { manage.flags.delete(flagId); } catch (NotFoundError ignored) {}
        }
    }
}
