package com.smplkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogLevelTest {

    @Test
    void allLevelsHaveExpectedValues() {
        assertEquals("TRACE", LogLevel.TRACE.getValue());
        assertEquals("DEBUG", LogLevel.DEBUG.getValue());
        assertEquals("INFO", LogLevel.INFO.getValue());
        assertEquals("WARN", LogLevel.WARN.getValue());
        assertEquals("ERROR", LogLevel.ERROR.getValue());
        assertEquals("FATAL", LogLevel.FATAL.getValue());
        assertEquals("SILENT", LogLevel.SILENT.getValue());
    }

    @Test
    void valuesReturnsAllLevels() {
        LogLevel[] values = LogLevel.values();
        assertEquals(7, values.length);
    }

    @Test
    void valueOfRoundTrips() {
        for (LogLevel level : LogLevel.values()) {
            assertEquals(level, LogLevel.valueOf(level.name()));
        }
    }
}
