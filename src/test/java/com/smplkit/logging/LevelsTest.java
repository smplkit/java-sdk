package com.smplkit.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link Levels} Python&lt;-&gt;smplkit level mapping utility.
 *
 * <p>{@code Levels} is a package-private static helper provided for parity with
 * the Python SDK's canonical level scale; it is exercised here so the mapping
 * tables and nearest-breakpoint lookup stay correct.</p>
 */
class LevelsTest {

    @Test
    void pythonLevelToSmpl_exactMatches() {
        assertEquals("TRACE", Levels.pythonLevelToSmpl(5));
        assertEquals("DEBUG", Levels.pythonLevelToSmpl(10));
        assertEquals("INFO", Levels.pythonLevelToSmpl(20));
        assertEquals("WARN", Levels.pythonLevelToSmpl(30));
        assertEquals("ERROR", Levels.pythonLevelToSmpl(40));
        assertEquals("FATAL", Levels.pythonLevelToSmpl(50));
        assertEquals("SILENT", Levels.pythonLevelToSmpl(99));
    }

    @Test
    void pythonLevelToSmpl_nonStandardLevel_usesNearestLowerBreakpoint() {
        // 25 is between INFO (20) and WARN (30) -> floor is INFO.
        assertEquals("INFO", Levels.pythonLevelToSmpl(25));
        // 15 is between DEBUG (10) and INFO (20) -> floor is DEBUG.
        assertEquals("DEBUG", Levels.pythonLevelToSmpl(15));
        // 1000 is above all breakpoints -> floor is the highest, SILENT.
        assertEquals("SILENT", Levels.pythonLevelToSmpl(1000));
    }

    @Test
    void pythonLevelToSmpl_belowAllBreakpoints_returnsFirstEntry() {
        // 1 is below the lowest breakpoint (5) -> falls back to the first entry, TRACE.
        assertEquals("TRACE", Levels.pythonLevelToSmpl(1));
    }

    @Test
    void smplLevelToPython_roundTripsAllLevels() {
        assertEquals(5, Levels.smplLevelToPython("TRACE"));
        assertEquals(10, Levels.smplLevelToPython("DEBUG"));
        assertEquals(20, Levels.smplLevelToPython("INFO"));
        assertEquals(30, Levels.smplLevelToPython("WARN"));
        assertEquals(40, Levels.smplLevelToPython("ERROR"));
        assertEquals(50, Levels.smplLevelToPython("FATAL"));
        assertEquals(99, Levels.smplLevelToPython("SILENT"));
    }

    @Test
    void smplLevels_tableIsOrderedTraceToSilent() {
        assertArrayEquals(
                new String[]{"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "SILENT"},
                Levels.SMPL_LEVELS);
    }
}
