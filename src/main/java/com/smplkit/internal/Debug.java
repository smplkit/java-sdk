package com.smplkit.internal;

import java.io.PrintStream;
import java.time.Instant;

/**
 * Internal SMPLKIT_DEBUG diagnostic facility.
 *
 * <p>Set the environment variable {@code SMPLKIT_DEBUG=1} (or {@code true} / {@code yes},
 * case-insensitive) to enable verbose output to stderr. All other values (including unset)
 * disable output. The variable is read once at class-load time and cached.</p>
 *
 * <p>Output bypasses the managed logging framework to avoid interference with level management
 * and infinite recursion. It writes directly to {@code System.err}.</p>
 *
 * <p>This class is internal and must not be part of the public API.</p>
 */
public final class Debug {

    static boolean ENABLED = parseDebugEnv(System.getenv("SMPLKIT_DEBUG"));

    /** Output stream — package-private so tests can substitute it. */
    static PrintStream out = System.err;

    private Debug() {}

    /**
     * Parses the {@code SMPLKIT_DEBUG} environment variable value.
     *
     * @param value raw env value (may be {@code null})
     * @return {@code true} for {@code "1"}, {@code "true"}, or {@code "yes"} (case-insensitive,
     *         whitespace stripped); {@code false} otherwise
     */
    static boolean parseDebugEnv(String value) {
        if (value == null) return false;
        String v = value.strip().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes");
    }

    /** Returns {@code true} when debug output is enabled. */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * Writes a single diagnostic line to stderr when debug is enabled.
     *
     * <p>Output format: {@code [smplkit:{subsystem}] {ISO-8601 timestamp} {message}\n}</p>
     *
     * @param subsystem short tag identifying the calling subsystem
     * @param message   human-readable diagnostic message
     */
    public static void log(String subsystem, String message) {
        if (!ENABLED) return;
        String ts = Instant.now().toString();
        out.println("[smplkit:" + subsystem + "] " + ts + " " + message);
    }
}
