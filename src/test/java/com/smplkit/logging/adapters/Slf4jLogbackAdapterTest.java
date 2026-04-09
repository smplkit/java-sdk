package com.smplkit.logging.adapters;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SLF4J + Logback logging adapter.
 */
class Slf4jLogbackAdapterTest {

    private Slf4jLogbackAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new Slf4jLogbackAdapter();
    }

    @AfterEach
    void tearDown() {
        adapter.uninstallHook();
    }

    @Test
    void name_returnsSlf4jLogback() {
        assertEquals("slf4j-logback", adapter.name());
    }

    @Test
    void discover_findsExistingLoggers() {
        // Create logback loggers
        LoggerFactory.getLogger("com.smplkit.logback.test1");
        LoggerFactory.getLogger("com.smplkit.logback.test2");

        List<DiscoveredLogger> discovered = adapter.discover();

        // Should find at least the loggers we created (plus ROOT)
        assertTrue(discovered.size() >= 2);
        assertTrue(discovered.stream().anyMatch(dl -> dl.name().equals("com.smplkit.logback.test1")));
        assertTrue(discovered.stream().anyMatch(dl -> dl.name().equals("com.smplkit.logback.test2")));
    }

    @Test
    void discover_returnsCorrectLevels() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger("com.smplkit.logback.leveled");
        logger.setLevel(Level.WARN);

        List<DiscoveredLogger> discovered = adapter.discover();
        DiscoveredLogger found = discovered.stream()
                .filter(dl -> dl.name().equals("com.smplkit.logback.leveled"))
                .findFirst()
                .orElseThrow();

        assertEquals("WARN", found.level());
    }

    @Test
    void applyLevel_setsLogbackLevel() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger("com.smplkit.logback.apply");

        adapter.applyLevel("com.smplkit.logback.apply", "ERROR");

        assertEquals(Level.ERROR, logger.getLevel());
    }

    @Test
    void applyLevel_allLevels() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        String name = "com.smplkit.logback.allapply";
        Logger logger = context.getLogger(name);

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
        assertEquals(Level.ERROR, logger.getLevel()); // Logback has no FATAL

        adapter.applyLevel(name, "SILENT");
        assertEquals(Level.OFF, logger.getLevel());
    }

    // -----------------------------------------------------------------------
    // Level mapping
    // -----------------------------------------------------------------------

    @Test
    void smplToLogbackLevel_mapsAllLevels() {
        assertEquals(Level.TRACE, Slf4jLogbackAdapter.smplToLogbackLevel("TRACE"));
        assertEquals(Level.DEBUG, Slf4jLogbackAdapter.smplToLogbackLevel("DEBUG"));
        assertEquals(Level.INFO, Slf4jLogbackAdapter.smplToLogbackLevel("INFO"));
        assertEquals(Level.WARN, Slf4jLogbackAdapter.smplToLogbackLevel("WARN"));
        assertEquals(Level.ERROR, Slf4jLogbackAdapter.smplToLogbackLevel("ERROR"));
        assertEquals(Level.ERROR, Slf4jLogbackAdapter.smplToLogbackLevel("FATAL"));
        assertEquals(Level.OFF, Slf4jLogbackAdapter.smplToLogbackLevel("SILENT"));
        assertEquals(Level.DEBUG, Slf4jLogbackAdapter.smplToLogbackLevel("UNKNOWN"));
    }

    @Test
    void logbackToSmplLevel_mapsAllLevels() {
        assertEquals("TRACE", Slf4jLogbackAdapter.logbackToSmplLevel(Level.TRACE));
        assertEquals("DEBUG", Slf4jLogbackAdapter.logbackToSmplLevel(Level.DEBUG));
        assertEquals("INFO", Slf4jLogbackAdapter.logbackToSmplLevel(Level.INFO));
        assertEquals("WARN", Slf4jLogbackAdapter.logbackToSmplLevel(Level.WARN));
        assertEquals("ERROR", Slf4jLogbackAdapter.logbackToSmplLevel(Level.ERROR));
        assertEquals("SILENT", Slf4jLogbackAdapter.logbackToSmplLevel(Level.OFF));
        assertEquals("DEBUG", Slf4jLogbackAdapter.logbackToSmplLevel(null));
    }

    // -----------------------------------------------------------------------
    // Hook
    // -----------------------------------------------------------------------

    @Test
    void installHook_detectsNewLoggers() {
        // Initial discover to populate known names
        adapter.discover();

        List<String> newNames = new CopyOnWriteArrayList<>();
        adapter.installHook((name, level) -> newNames.add(name));

        // Create a new logger — the TurboFilter should detect it on first log call
        String newLoggerName = "com.smplkit.logback.hooked." + System.nanoTime();
        org.slf4j.Logger newLogger = LoggerFactory.getLogger(newLoggerName);
        // Trigger the TurboFilter by performing a log call
        newLogger.debug("trigger hook");

        assertTrue(newNames.contains(newLoggerName));
    }

    @Test
    void uninstallHook_removesFilter() {
        adapter.installHook((name, level) -> {});
        adapter.uninstallHook();

        // Creating a new logger after uninstall should not fire the callback
        List<String> newNames = new CopyOnWriteArrayList<>();
        adapter.installHook((name, level) -> newNames.add(name));
        adapter.uninstallHook();

        String name = "com.smplkit.logback.unhooked." + System.nanoTime();
        org.slf4j.Logger logger = LoggerFactory.getLogger(name);
        logger.debug("no hook");

        assertFalse(newNames.contains(name));
    }

    @Test
    void uninstallHook_noOpWhenNoFilter() {
        // Should not throw when called without prior install
        adapter.uninstallHook();
    }
}
