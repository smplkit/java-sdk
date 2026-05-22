package com.smplkit.examples.setup;

import com.smplkit.errors.NotFoundError;
import com.smplkit.management.SmplManagementClient;

import java.util.List;

/** Setup / cleanup helpers for {@code LoggingManagementShowcase}. */
public final class LoggingManagementSetup {

    private static final List<String> DEMO_LOGGER_IDS = List.of(
            "showcase",
            "showcase.db",
            "showcase.payments");

    private LoggingManagementSetup() {}

    public static void setup(SmplManagementClient manage) {
        cleanup(manage);
    }

    public static void cleanup(SmplManagementClient manage) {
        for (String loggerId : DEMO_LOGGER_IDS) {
            try { manage.loggers.delete(loggerId); } catch (NotFoundError ignored) {}
        }
    }
}
