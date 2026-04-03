package com.smplkit.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChangeEvent}.
 */
class ChangeEventTest {

    @Test
    void accessors() {
        ChangeEvent event = new ChangeEvent("user_service", "timeout", 30, 60, "websocket");
        assertEquals("user_service", event.configKey());
        assertEquals("timeout", event.itemKey());
        assertEquals(30, event.oldValue());
        assertEquals(60, event.newValue());
        assertEquals("websocket", event.source());
    }

    @Test
    void toString_format() {
        ChangeEvent event = new ChangeEvent("cfg", "key", "old", "new", "manual");
        String str = event.toString();
        assertTrue(str.contains("configKey=cfg"));
        assertTrue(str.contains("itemKey=key"));
        assertTrue(str.contains("oldValue=old"));
        assertTrue(str.contains("newValue=new"));
        assertTrue(str.contains("source=manual"));
    }

    @Test
    void nullValues_allowed() {
        ChangeEvent event = new ChangeEvent("cfg", "key", null, "value", "websocket");
        assertNull(event.oldValue());
        assertEquals("value", event.newValue());
    }

    @Test
    void equality() {
        ChangeEvent e1 = new ChangeEvent("cfg", "k", "a", "b", "websocket");
        ChangeEvent e2 = new ChangeEvent("cfg", "k", "a", "b", "websocket");
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }
}
