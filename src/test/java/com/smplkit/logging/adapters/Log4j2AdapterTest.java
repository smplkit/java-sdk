package com.smplkit.logging.adapters;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Log4j2 logging adapter.
 */
class Log4j2AdapterTest {

    private Log4j2Adapter adapter;

    /**
     * Strong references to loggers created during a test.
     *
     * <p>Log4j2 2.24+ keeps loggers as {@link java.lang.ref.WeakReference}s in its internal
     * registry ({@code InternalLoggerRegistry}). A logger created via {@code LogManager.getLogger}
     * whose reference is then dropped can be garbage-collected — and disappear from
     * {@code context.getLoggers()} — before {@code discover()} or {@code pollForNewLoggers()} runs.
     * Under CI memory pressure that made these tests intermittently fail (the logger was missing, so
     * the {@code findFirst().orElseThrow()} threw {@code NoSuchElementException}). Real applications
     * hold their loggers in static fields so they never vanish; pinning them here mirrors that and
     * keeps discovery deterministic. {@code Configurator.setLevel} does <em>not</em> keep a logger
     * alive — it creates a {@code LoggerConfig} (held by the configuration), not a reference back to
     * the {@code Logger} object.</p>
     */
    private final List<Object> pinnedLoggers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        adapter = new Log4j2Adapter();
    }

    /**
     * Creates (or fetches) a Log4j2 logger and pins a strong reference for the lifetime of the
     * test instance, so the weakly-registered logger survives GC until discovery runs.
     */
    private void createLogger(String name) {
        pinnedLoggers.add(LogManager.getLogger(name));
    }

    @Test
    void name_returnsLog4j2() {
        assertEquals("log4j2", adapter.name());
    }

    @Test
    void discover_findsExistingLoggers() {
        // Create Log4j2 loggers by getting them from LogManager (this creates the logger objects)
        createLogger("com.smplkit.log4j2.test1");
        createLogger("com.smplkit.log4j2.test2");

        List<DiscoveredLogger> discovered = adapter.discover();

        assertTrue(discovered.stream().anyMatch(dl -> dl.name().equals("com.smplkit.log4j2.test1")));
        assertTrue(discovered.stream().anyMatch(dl -> dl.name().equals("com.smplkit.log4j2.test2")));
    }

    @Test
    void discover_returnsCorrectLevels() {
        createLogger("com.smplkit.log4j2.leveled");
        Configurator.setLevel("com.smplkit.log4j2.leveled", Level.WARN);

        List<DiscoveredLogger> discovered = adapter.discover();
        DiscoveredLogger found = discovered.stream()
                .filter(dl -> dl.name().equals("com.smplkit.log4j2.leveled"))
                .findFirst()
                .orElseThrow();

        assertEquals("WARN", found.level());
    }

    @Test
    void discover_populatesBothLevelAndResolvedLevel() {
        // Create a logger with an explicit level set
        String name = "com.smplkit.log4j2.explicit." + System.nanoTime();
        createLogger(name);
        Configurator.setLevel(name, Level.ERROR);

        List<DiscoveredLogger> discovered = adapter.discover();
        DiscoveredLogger found = discovered.stream()
                .filter(dl -> dl.name().equals(name))
                .findFirst()
                .orElseThrow();

        // Both level and resolvedLevel should be set to ERROR
        assertEquals("ERROR", found.level());
        assertEquals("ERROR", found.resolvedLevel());
    }

    @Test
    void discover_resolvedLevelFallsBackToAncestor() {
        // Ensure parent has a known level
        String parent = "com.smplkit.log4j2.inherit." + System.nanoTime();
        String child = parent + ".child";
        createLogger(parent);
        createLogger(child);
        Configurator.setLevel(parent, Level.WARN);
        // Do not set explicit level on child — it should inherit from parent

        List<DiscoveredLogger> discovered = adapter.discover();
        DiscoveredLogger foundParent = discovered.stream()
                .filter(dl -> dl.name().equals(parent))
                .findFirst()
                .orElseThrow();

        // Parent has explicit level
        assertEquals("WARN", foundParent.level());
        assertEquals("WARN", foundParent.resolvedLevel());

        // Child has no explicitly set level — getExplicitLevel() returns null for it.
        DiscoveredLogger foundChild = discovered.stream()
                .filter(dl -> dl.name().equals(child))
                .findFirst()
                .orElseThrow();

        assertNull(foundChild.level(),
                "Child level should be null — no explicit level configured on child logger");
        assertEquals("WARN", foundChild.resolvedLevel(),
                "Child resolvedLevel should inherit WARN from parent");
    }

    @Test
    void applyLevel_setsLog4j2Level() {
        Configurator.setLevel("com.smplkit.log4j2.apply", Level.INFO);

        adapter.applyLevel("com.smplkit.log4j2.apply", "ERROR");

        org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger)
                LogManager.getLogger("com.smplkit.log4j2.apply");
        assertEquals(Level.ERROR, logger.getLevel());
    }

    @Test
    void applyLevel_allLevels() {
        String name = "com.smplkit.log4j2.allapply";
        Configurator.setLevel(name, Level.INFO);
        org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger)
                LogManager.getLogger(name);

        adapter.applyLevel(name, "TRACE");
        assertEquals(Level.TRACE, logger.getLevel());

        adapter.applyLevel(name, "DEBUG");
        assertEquals(Level.DEBUG, logger.getLevel());

        adapter.applyLevel(name, "INFO");
        assertEquals(Level.INFO, logger.getLevel());

        adapter.applyLevel(name, "WARN");
        assertEquals(Level.WARN, logger.getLevel());

        adapter.applyLevel(name, "ERROR");
        assertEquals(Level.ERROR, logger.getLevel());

        adapter.applyLevel(name, "FATAL");
        assertEquals(Level.FATAL, logger.getLevel());

        adapter.applyLevel(name, "SILENT");
        assertEquals(Level.OFF, logger.getLevel());
    }

    // -----------------------------------------------------------------------
    // Level mapping
    // -----------------------------------------------------------------------

    @Test
    void smplToLog4j2Level_mapsAllLevels() {
        assertEquals(Level.TRACE, Log4j2Adapter.smplToLog4j2Level("TRACE"));
        assertEquals(Level.DEBUG, Log4j2Adapter.smplToLog4j2Level("DEBUG"));
        assertEquals(Level.INFO, Log4j2Adapter.smplToLog4j2Level("INFO"));
        assertEquals(Level.WARN, Log4j2Adapter.smplToLog4j2Level("WARN"));
        assertEquals(Level.ERROR, Log4j2Adapter.smplToLog4j2Level("ERROR"));
        assertEquals(Level.FATAL, Log4j2Adapter.smplToLog4j2Level("FATAL"));
        assertEquals(Level.OFF, Log4j2Adapter.smplToLog4j2Level("SILENT"));
        assertEquals(Level.DEBUG, Log4j2Adapter.smplToLog4j2Level("UNKNOWN"));
    }

    @Test
    void log4j2ToSmplLevel_mapsAllLevels() {
        assertEquals("TRACE", Log4j2Adapter.log4j2ToSmplLevel(Level.TRACE));
        assertEquals("DEBUG", Log4j2Adapter.log4j2ToSmplLevel(Level.DEBUG));
        assertEquals("INFO", Log4j2Adapter.log4j2ToSmplLevel(Level.INFO));
        assertEquals("WARN", Log4j2Adapter.log4j2ToSmplLevel(Level.WARN));
        assertEquals("ERROR", Log4j2Adapter.log4j2ToSmplLevel(Level.ERROR));
        assertEquals("FATAL", Log4j2Adapter.log4j2ToSmplLevel(Level.FATAL));
        assertEquals("SILENT", Log4j2Adapter.log4j2ToSmplLevel(Level.OFF));
        // null means the level is inherited — not explicitly configured on this logger
        assertNull(Log4j2Adapter.log4j2ToSmplLevel(null));
    }

    // -----------------------------------------------------------------------
    // Hook / polling
    // -----------------------------------------------------------------------

    @Test
    void installHook_setsCallback() {
        adapter.installHook((name, level) -> {});
        // No exception is success
    }

    @Test
    void uninstallHook_clearsCallback() {
        adapter.installHook((name, level) -> {});
        adapter.uninstallHook();
        adapter.pollForNewLoggers();
    }

    @Test
    void pollForNewLoggers_detectsNewLoggers() {
        adapter.discover();

        List<String> newNames = new CopyOnWriteArrayList<>();
        adapter.installHook((name, level) -> newNames.add(name));

        // Create a new logger after discovery (must use LogManager.getLogger to create the object)
        String newLoggerName = "com.smplkit.log4j2.polled." + System.nanoTime();
        createLogger(newLoggerName);

        adapter.pollForNewLoggers();

        assertTrue(newNames.contains(newLoggerName));
    }

    @Test
    void pollForNewLoggers_doesNotDuplicate() {
        adapter.discover();

        List<String> newNames = new CopyOnWriteArrayList<>();
        adapter.installHook((name, level) -> newNames.add(name));

        String newLoggerName = "com.smplkit.log4j2.nodup." + System.nanoTime();
        createLogger(newLoggerName);

        adapter.pollForNewLoggers();
        long countAfterFirst = newNames.stream().filter(n -> n.equals(newLoggerName)).count();

        adapter.pollForNewLoggers();
        long countAfterSecond = newNames.stream().filter(n -> n.equals(newLoggerName)).count();

        assertEquals(1, countAfterFirst);
        assertEquals(1, countAfterSecond);
    }

    @Test
    void pollForNewLoggers_noOpWithoutHook() {
        adapter.discover();
        createLogger("com.smplkit.log4j2.nohook." + System.nanoTime());
        adapter.pollForNewLoggers();
    }

    @Test
    void resolveLog4j2Level_nullEffectiveLevel_fallsBackToInfo() {
        // Log4j2's getLevel() is documented as always non-null in real use, but
        // the resolver defensively falls back to "INFO" when it is null. Mock a
        // core Logger whose getLevel() returns null to exercise that false arm.
        org.apache.logging.log4j.core.Logger logger =
                mock(org.apache.logging.log4j.core.Logger.class);
        when(logger.getLevel()).thenReturn(null);

        assertEquals("INFO", Log4j2Adapter.resolveLog4j2Level(logger));
    }
}
