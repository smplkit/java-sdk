package com.smplkit.examples.setup;

import com.smplkit.SmplClient;
import com.smplkit.errors.NotFoundError;

import java.util.List;

/** Setup / cleanup helpers for {@code FlagsManagementShowcase}. */
public final class FlagsManagementSetup {

    private static final List<String> DEMO_FLAG_IDS =
            List.of("checkout-v2", "banner-color", "max-retries", "ui-theme");

    private FlagsManagementSetup() {}

    public static void setup(SmplClient client) {
        cleanup(client);
    }

    public static void cleanup(SmplClient client) {
        for (String flagId : DEMO_FLAG_IDS) {
            try { client.flags.delete(flagId); } catch (NotFoundError ignored) {}
        }
    }
}
