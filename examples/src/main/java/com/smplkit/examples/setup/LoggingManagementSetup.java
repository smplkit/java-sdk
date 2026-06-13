package com.smplkit.examples.setup;

import com.smplkit.SmplClient;
import com.smplkit.errors.NotFoundError;

import java.util.List;

/** Setup / cleanup helpers for {@code LoggingManagementShowcase}. */
public final class LoggingManagementSetup {

    private static final List<String> DEMO_LOGGER_IDS = List.of(
            "showcase",
            "showcase.db",
            "showcase.payments");

    private LoggingManagementSetup() {}

    public static void setup(SmplClient client) {
        cleanup(client);
    }

    public static void cleanup(SmplClient client) {
        for (String loggerId : DEMO_LOGGER_IDS) {
            try { client.logging.loggers.delete(loggerId); } catch (NotFoundError ignored) {}
        }
    }
}
