package com.smplkit.logging.adapters;

import com.smplkit.LogLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JUL logging adapter.
 */
class JulAdapterTest {

    private JulAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JulAdapter();
    }

    @Test
    void name_returnsJul() {
        assertEquals("jul", adapter.name());
    }

    @Test
    void discover_findsExistingLoggers() {
        // Create some JUL loggers
        java.util.logging.Logger.getLogger("com.smplkit.jul.test1");
        java.util.logging.Logger.getLogger("com.smplkit.jul.test2");

        List<DiscoveredLogger> discovered = adapter.discover();

        // Should find at least the loggers we created (plus root and others)
        assertTrue(discovered.size() >= 2);
        assertTrue(discovered.stream().anyMatch(dl -> dl.name().equals("com.smplkit.jul.test1")));
        assertTrue(discovered.stream().anyMatch(dl -> dl.name().equals("com.smplkit.jul.test2")));
    }

    @Test
    void discover_returnsCorrectLevels() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("com.smplkit.jul.leveled");
        logger.setLevel(Level.WARNING);

        List<DiscoveredLogger> discovered = adapter.discover();
        DiscoveredLogger found = discovered.stream()
                .filter(dl -> dl.name().equals("com.smplkit.jul.leveled"))
                .findFirst()
                .orElseThrow();

        assertEquals("WARN", found.level());
    }

    @Test
    void discover_returnsDebugForNullLevel() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("com.smplkit.jul.inherited");
        logger.setLevel(null); // inherit from parent

        List<DiscoveredLogger> discovered = adapter.discover();
        DiscoveredLogger found = discovered.stream()
                .filter(dl -> dl.name().equals("com.smplkit.jul.inherited"))
                .findFirst()
                .orElseThrow();

        assertEquals("DEBUG", found.level());
    }

    @Test
    void applyLevel_setsJulLevel() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("com.smplkit.jul.apply");

        adapter.applyLevel("com.smplkit.jul.apply", "WARN");

        assertEquals(Level.WARNING, logger.getLevel());
    }

    @Test
    void applyLevel_allLevels() {
        String loggerName = "com.smplkit.jul.allapply";
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(loggerName);

        adapter.applyLevel(loggerName, "TRACE");
        assertEquals(Level.FINEST, logger.getLevel());

        adapter.applyLevel(loggerName, "DEBUG");
        assertEquals(Level.FINE, logger.getLevel());

        adapter.applyLevel(loggerName, "INFO");
        assertEquals(Level.INFO, logger.getLevel());

        adapter.applyLevel(loggerName, "WARN");
        assertEquals(Level.WARNING, logger.getLevel());

        adapter.applyLevel(loggerName, "ERROR");
        assertEquals(Level.SEVERE, logger.getLevel());

        adapter.applyLevel(loggerName, "FATAL");
        assertEquals(Level.SEVERE, logger.getLevel());

        adapter.applyLevel(loggerName, "SILENT");
        assertEquals(Level.OFF, logger.getLevel());
    }

    // -----------------------------------------------------------------------
    // Level mapping: smplkit -> JUL
    // -----------------------------------------------------------------------

    @Test
    void smplToJulLevel_mapsAllLevels() {
        assertEquals(Level.FINEST, JulAdapter.smplToJulLevel(LogLevel.TRACE));
        assertEquals(Level.FINE, JulAdapter.smplToJulLevel(LogLevel.DEBUG));
        assertEquals(Level.INFO, JulAdapter.smplToJulLevel(LogLevel.INFO));
        assertEquals(Level.WARNING, JulAdapter.smplToJulLevel(LogLevel.WARN));
        assertEquals(Level.SEVERE, JulAdapter.smplToJulLevel(LogLevel.ERROR));
        assertEquals(Level.SEVERE, JulAdapter.smplToJulLevel(LogLevel.FATAL));
        assertEquals(Level.OFF, JulAdapter.smplToJulLevel(LogLevel.SILENT));
    }

    @Test
    void smplStringToJulLevel_mapsCorrectly() {
        assertEquals(Level.FINE, JulAdapter.smplStringToJulLevel("DEBUG"));
        assertEquals(Level.WARNING, JulAdapter.smplStringToJulLevel("WARN"));
        assertEquals(Level.FINEST, JulAdapter.smplStringToJulLevel("TRACE"));
        assertEquals(Level.INFO, JulAdapter.smplStringToJulLevel("INFO"));
        assertEquals(Level.SEVERE, JulAdapter.smplStringToJulLevel("ERROR"));
        assertEquals(Level.SEVERE, JulAdapter.smplStringToJulLevel("FATAL"));
        assertEquals(Level.OFF, JulAdapter.smplStringToJulLevel("SILENT"));
    }

    // -----------------------------------------------------------------------
    // Level mapping: JUL -> smplkit
    // -----------------------------------------------------------------------

    @Test
    void julToSmplLevel_mapsAllLevels() {
        assertEquals("TRACE", JulAdapter.julToSmplLevel(Level.FINEST));
        assertEquals("TRACE", JulAdapter.julToSmplLevel(Level.FINER));
        assertEquals("DEBUG", JulAdapter.julToSmplLevel(Level.FINE));
        assertEquals("DEBUG", JulAdapter.julToSmplLevel(Level.CONFIG));
        assertEquals("INFO", JulAdapter.julToSmplLevel(Level.INFO));
        assertEquals("WARN", JulAdapter.julToSmplLevel(Level.WARNING));
        assertEquals("ERROR", JulAdapter.julToSmplLevel(Level.SEVERE));
        assertEquals("SILENT", JulAdapter.julToSmplLevel(Level.OFF));
        assertEquals("DEBUG", JulAdapter.julToSmplLevel(null));
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
        // Polling after uninstall should not fire callbacks
        adapter.pollForNewLoggers();
    }

    @Test
    void pollForNewLoggers_detectsNewLoggers() {
        // Initial discover
        adapter.discover();

        // Install hook
        List<String> newNames = new CopyOnWriteArrayList<>();
        adapter.installHook((name, level) -> newNames.add(name));

        // Create a new logger after discovery
        String newLoggerName = "com.smplkit.jul.polled." + System.nanoTime();
        java.util.logging.Logger.getLogger(newLoggerName);

        // Poll should detect it
        adapter.pollForNewLoggers();

        assertTrue(newNames.contains(newLoggerName));
    }

    @Test
    void pollForNewLoggers_doesNotDuplicate() {
        adapter.discover();

        List<String> newNames = new CopyOnWriteArrayList<>();
        adapter.installHook((name, level) -> newNames.add(name));

        String newLoggerName = "com.smplkit.jul.nodup." + System.nanoTime();
        java.util.logging.Logger.getLogger(newLoggerName);

        adapter.pollForNewLoggers();
        int countAfterFirst = (int) newNames.stream().filter(n -> n.equals(newLoggerName)).count();

        adapter.pollForNewLoggers();
        int countAfterSecond = (int) newNames.stream().filter(n -> n.equals(newLoggerName)).count();

        assertEquals(1, countAfterFirst);
        assertEquals(1, countAfterSecond);
    }

    @Test
    void pollForNewLoggers_noOpWithoutHook() {
        adapter.discover();
        // No hook installed, should not throw
        java.util.logging.Logger.getLogger("com.smplkit.jul.nohook." + System.nanoTime());
        adapter.pollForNewLoggers();
    }
}
