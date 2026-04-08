package com.smplkit.logging;

import com.smplkit.LogLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the LoggerChangeEvent record.
 */
class LoggerChangeEventTest {

    @Test
    void record_storesAllFields() {
        LoggerChangeEvent event = new LoggerChangeEvent("com.acme", LogLevel.DEBUG, "start");
        assertEquals("com.acme", event.key());
        assertEquals(LogLevel.DEBUG, event.level());
        assertEquals("start", event.source());
    }

    @Test
    void record_equalityAndHashCode() {
        LoggerChangeEvent a = new LoggerChangeEvent("key", LogLevel.INFO, "refresh");
        LoggerChangeEvent b = new LoggerChangeEvent("key", LogLevel.INFO, "refresh");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void record_inequalityOnDifferentKey() {
        LoggerChangeEvent a = new LoggerChangeEvent("key1", LogLevel.INFO, "start");
        LoggerChangeEvent b = new LoggerChangeEvent("key2", LogLevel.INFO, "start");
        assertNotEquals(a, b);
    }

    @Test
    void record_inequalityOnDifferentLevel() {
        LoggerChangeEvent a = new LoggerChangeEvent("key", LogLevel.INFO, "start");
        LoggerChangeEvent b = new LoggerChangeEvent("key", LogLevel.DEBUG, "start");
        assertNotEquals(a, b);
    }

    @Test
    void record_inequalityOnDifferentSource() {
        LoggerChangeEvent a = new LoggerChangeEvent("key", LogLevel.INFO, "start");
        LoggerChangeEvent b = new LoggerChangeEvent("key", LogLevel.INFO, "websocket");
        assertNotEquals(a, b);
    }

    @Test
    void record_toStringIncludesFields() {
        LoggerChangeEvent event = new LoggerChangeEvent("com.acme", LogLevel.WARN, "start");
        String str = event.toString();
        assertTrue(str.contains("com.acme"));
        assertTrue(str.contains("WARN"));
        assertTrue(str.contains("start"));
    }
}
