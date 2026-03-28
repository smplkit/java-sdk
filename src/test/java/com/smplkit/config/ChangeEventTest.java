package com.smplkit.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChangeEvent}.
 */
class ChangeEventTest {

    @Test
    void accessors() {
        ChangeEvent event = new ChangeEvent("timeout", 30, 60, "websocket");
        assertEquals("timeout", event.key());
        assertEquals(30, event.oldValue());
        assertEquals(60, event.newValue());
        assertEquals("websocket", event.source());
    }

    @Test
    void toString_format() {
        ChangeEvent event = new ChangeEvent("key", "old", "new", "manual");
        String str = event.toString();
        assertTrue(str.contains("key=key"));
        assertTrue(str.contains("oldValue=old"));
        assertTrue(str.contains("newValue=new"));
        assertTrue(str.contains("source=manual"));
    }

    @Test
    void nullValues_allowed() {
        ChangeEvent event = new ChangeEvent("key", null, "value", "websocket");
        assertNull(event.oldValue());
        assertEquals("value", event.newValue());
    }

    @Test
    void equality() {
        ChangeEvent e1 = new ChangeEvent("k", "a", "b", "websocket");
        ChangeEvent e2 = new ChangeEvent("k", "a", "b", "websocket");
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }
}
