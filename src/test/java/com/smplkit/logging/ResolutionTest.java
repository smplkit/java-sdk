package com.smplkit.logging;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests for the level resolution algorithm. Mirrors the Python SDK's
 * {@code tests/unit/logging/test_resolution.py}.
 */
class ResolutionTest {

    @Nested
    class Basic {

        @Test
        void loggerEnvLevelWins() {
            Map<String, Map<String, Object>> loggers = Map.of(
                    "com.example.sql", Map.of(
                            "level", "DEBUG",
                            "environments", Map.of("production", Map.of("level", "ERROR"))
                    )
            );
            assertEquals("ERROR",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, Map.of()));
        }

        @Test
        void loggerBaseLevelWhenNoEnv() {
            Map<String, Map<String, Object>> loggers = Map.of(
                    "com.example.sql", Map.of(
                            "level", "DEBUG",
                            "environments", Map.of()
                    )
            );
            assertEquals("DEBUG",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, Map.of()));
        }

        @Test
        void loggerBaseLevelWhenDifferentEnv() {
            Map<String, Map<String, Object>> loggers = Map.of(
                    "com.example.sql", Map.of(
                            "level", "DEBUG",
                            "environments", Map.of("staging", Map.of("level", "TRACE"))
                    )
            );
            assertEquals("DEBUG",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, Map.of()));
        }

        @Test
        void fallbackToInfo() {
            assertEquals("INFO",
                    Resolution.resolveLevel("unknown.logger", "production", Map.of(), Map.of()));
        }
    }

    @Nested
    class GroupChain {

        @Test
        void groupEnvLevel() {
            Map<String, Object> logger = withNullLevel("group-1");
            Map<String, Map<String, Object>> loggers = Map.of("com.example.sql", logger);

            Map<String, Map<String, Object>> groups = Map.of(
                    "group-1", Map.of(
                            "level", "WARN",
                            "environments", Map.of("production", Map.of("level", "ERROR"))
                    )
            );
            assertEquals("ERROR",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, groups));
        }

        @Test
        void groupBaseLevel() {
            Map<String, Object> logger = withNullLevel("group-1");
            Map<String, Map<String, Object>> loggers = Map.of("com.example.sql", logger);

            Map<String, Map<String, Object>> groups = Map.of(
                    "group-1", Map.of("level", "WARN", "environments", Map.of())
            );
            assertEquals("WARN",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, groups));
        }

        @Test
        void nestedGroupChain() {
            Map<String, Object> logger = withNullLevel("group-child");
            Map<String, Map<String, Object>> loggers = Map.of("com.example.sql", logger);

            Map<String, Object> childGroup = new HashMap<>();
            childGroup.put("level", null);
            childGroup.put("group", "group-parent");
            childGroup.put("environments", Map.of());

            Map<String, Map<String, Object>> groups = new HashMap<>();
            groups.put("group-child", childGroup);
            groups.put("group-parent", Map.of("level", "FATAL", "environments", Map.of()));

            assertEquals("FATAL",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, groups));
        }

        @Test
        void groupCycleDoesNotInfiniteLoop() {
            Map<String, Object> logger = withNullLevel("group-a");
            Map<String, Map<String, Object>> loggers = Map.of("com.example.sql", logger);

            Map<String, Object> grpA = new HashMap<>();
            grpA.put("level", null);
            grpA.put("group", "group-b");
            grpA.put("environments", Map.of());
            Map<String, Object> grpB = new HashMap<>();
            grpB.put("level", null);
            grpB.put("group", "group-a");
            grpB.put("environments", Map.of());

            Map<String, Map<String, Object>> groups = new HashMap<>();
            groups.put("group-a", grpA);
            groups.put("group-b", grpB);

            assertEquals("INFO",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, groups));
        }
    }

    @Nested
    class DotAncestry {

        @Test
        void parentLoggerLevel() {
            Map<String, Map<String, Object>> loggers = Map.of(
                    "com.example", Map.of("level", "WARN", "environments", Map.of())
            );
            assertEquals("WARN",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, Map.of()));
        }

        @Test
        void grandparentLoggerLevel() {
            Map<String, Map<String, Object>> loggers = Map.of(
                    "com", Map.of("level", "ERROR", "environments", Map.of())
            );
            assertEquals("ERROR",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, Map.of()));
        }

        @Test
        void closestAncestorWins() {
            Map<String, Map<String, Object>> loggers = Map.of(
                    "com", Map.of("level", "ERROR", "environments", Map.of()),
                    "com.example", Map.of("level", "DEBUG", "environments", Map.of())
            );
            assertEquals("DEBUG",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, Map.of()));
        }

        @Test
        void groupTakesPrecedenceOverDotAncestor() {
            Map<String, Map<String, Object>> loggers = Map.of(
                    "com.example.sql", withNullLevel("group-1"),
                    "com.example", Map.of("level", "DEBUG", "environments", Map.of())
            );
            Map<String, Map<String, Object>> groups = Map.of(
                    "group-1", Map.of("level", "ERROR", "environments", Map.of())
            );
            assertEquals("ERROR",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, groups));
        }

        @Test
        void ancestorEnvLevel() {
            Map<String, Map<String, Object>> loggers = Map.of(
                    "com.example", Map.of(
                            "level", "DEBUG",
                            "environments", Map.of("production", Map.of("level", "FATAL"))
                    )
            );
            assertEquals("FATAL",
                    Resolution.resolveLevel("com.example.sql", "production", loggers, Map.of()));
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void loggerNotInLoggersDict() {
            assertEquals("INFO", Resolution.resolveLevel("nonexistent", "prod", Map.of(), Map.of()));
        }

        @Test
        void groupIdNotInGroupsDict() {
            Map<String, Object> loggerEntry = new HashMap<>();
            loggerEntry.put("level", null);
            loggerEntry.put("group", "missing-group-id");
            loggerEntry.put("environments", Map.of());

            Map<String, Map<String, Object>> loggers = Map.of("com.example", loggerEntry);
            assertEquals("INFO",
                    Resolution.resolveLevel("com.example", "prod", loggers, Map.of()));
        }

        @Test
        void nullEnvironments() {
            Map<String, Object> entry = new HashMap<>();
            entry.put("level", "WARN");
            entry.put("environments", null);
            Map<String, Map<String, Object>> loggers = Map.of("test", entry);

            assertEquals("WARN", Resolution.resolveLevel("test", "prod", loggers, Map.of()));
        }

        @Test
        void nonMapEnvironmentsValueIsSkipped() {
            Map<String, Object> entry = new HashMap<>();
            entry.put("level", "DEBUG");
            entry.put("environments", "not-a-map");
            Map<String, Map<String, Object>> loggers = Map.of("com.acme", entry);

            assertEquals("DEBUG", Resolution.resolveLevel("com.acme", "prod", loggers, Map.of()));
        }

        @Test
        void nonMapEnvDataIsSkipped() {
            Map<String, Object> entry = new HashMap<>();
            entry.put("level", "DEBUG");
            entry.put("environments", Map.of("prod", "not-a-map"));
            Map<String, Map<String, Object>> loggers = Map.of("com.acme", entry);

            assertEquals("DEBUG", Resolution.resolveLevel("com.acme", "prod", loggers, Map.of()));
        }

        @Test
        void nonStringLevelIsSkipped() {
            Map<String, Object> entry = new HashMap<>();
            entry.put("level", "DEBUG");
            entry.put("environments", Map.of("prod", Map.of("level", 42)));
            Map<String, Map<String, Object>> loggers = Map.of("com.acme", entry);

            assertEquals("DEBUG", Resolution.resolveLevel("com.acme", "prod", loggers, Map.of()));
        }

        @Test
        void groupBaseLevelNonStringIsSkipped() {
            Map<String, Object> logger = withNullLevel("g1");
            Map<String, Map<String, Object>> loggers = Map.of("com.acme", logger);

            Map<String, Object> g1 = new HashMap<>();
            g1.put("level", 999);
            g1.put("environments", Map.of());
            Map<String, Map<String, Object>> groups = Map.of("g1", g1);

            assertEquals("INFO", Resolution.resolveLevel("com.acme", "prod", loggers, groups));
        }
    }

    @Nested
    class FindResolutionSource {

        private final Map<String, Map<String, Object>> loggers = Map.of(
                "with.env", Map.of(
                        "level", "DEBUG",
                        "environments", Map.of("production", Map.of("level", "ERROR"))
                ),
                "with.base", Map.of(
                        "level", "WARN",
                        "environments", Map.of()
                ),
                "with.group", withNullLevel("g1"),
                "no.resolution", noResolution()
        );
        private final Map<String, Map<String, Object>> groups = Map.of(
                "g1", Map.of("level", "DEBUG", "environments", Map.of())
        );

        @Test
        void envOverrideSource() {
            assertEquals("env override \"production\"",
                    Resolution.findResolutionSource("with.env", "production", loggers, groups));
        }

        @Test
        void baseLevelSource() {
            assertEquals("base level",
                    Resolution.findResolutionSource("with.base", "production", loggers, groups));
        }

        @Test
        void groupSource() {
            assertEquals("group \"g1\"",
                    Resolution.findResolutionSource("with.group", "production", loggers, groups));
        }

        @Test
        void unknownSourceWhenNoResolution() {
            assertEquals("unknown",
                    Resolution.findResolutionSource("no.resolution", "production", loggers, groups));
        }

        @Test
        void notFoundWhenLoggerMissing() {
            assertEquals("not found",
                    Resolution.findResolutionSource("missing", "production", Map.of(), Map.of()));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> withNullLevel(String group) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("level", null);
        entry.put("group", group);
        entry.put("environments", Map.of());
        return entry;
    }

    private static Map<String, Object> noResolution() {
        Map<String, Object> entry = new HashMap<>();
        entry.put("level", null);
        entry.put("group", null);
        entry.put("environments", Map.of());
        return entry;
    }
}
