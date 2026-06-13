package com.smplkit.logging;

import java.util.Map;
import java.util.TreeMap;

/**
 * Bidirectional mapping between Python logging levels and smplkit canonical levels.
 *
 * <p>Mirrors the Python SDK's {@code _levels.py}. The numeric column is the
 * Python {@code logging} level integer; the canonical column is the smplkit
 * level string. The Java logging-framework adapters speak smplkit level
 * strings directly and convert to their framework-native level themselves, so
 * the numeric mapping is provided for parity with the canonical spec and for
 * adapters that need the standard integer scale.</p>
 */
final class Levels {

    private Levels() {}

    static final String[] SMPL_LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "SILENT"};

    /** Python level → smplkit level (exact matches only). */
    static final Map<Integer, String> PYTHON_TO_SMPL = Map.of(
            5, "TRACE",
            10, "DEBUG",
            20, "INFO",
            30, "WARN",
            40, "ERROR",
            50, "FATAL",
            99, "SILENT");

    /** smplkit level → Python level. */
    static final Map<String, Integer> SMPL_TO_PYTHON = Map.of(
            "TRACE", 5,
            "DEBUG", 10,
            "INFO", 20,
            "WARN", 30,
            "ERROR", 40,
            "FATAL", 50,
            "SILENT", 99);

    /** Sorted breakpoints for nearest-level lookup. */
    private static final TreeMap<Integer, String> SORTED_BREAKPOINTS = new TreeMap<>(PYTHON_TO_SMPL);

    /**
     * Map a Python logging level int to the nearest smplkit canonical level.
     *
     * <p>Exact matches are preferred.  For non-standard levels the nearest lower
     * breakpoint is used; if below all breakpoints, returns {@code "TRACE"}.</p>
     */
    static String pythonLevelToSmpl(int level) {
        String exact = PYTHON_TO_SMPL.get(level);
        if (exact != null) {
            return exact;
        }
        // Find nearest lower breakpoint
        Map.Entry<Integer, String> floor = SORTED_BREAKPOINTS.floorEntry(level);
        if (floor == null) {
            return SORTED_BREAKPOINTS.firstEntry().getValue();
        }
        return floor.getValue();
    }

    /** Map a smplkit canonical level string to a Python logging level int. */
    static int smplLevelToPython(String level) {
        return SMPL_TO_PYTHON.get(level);
    }
}
