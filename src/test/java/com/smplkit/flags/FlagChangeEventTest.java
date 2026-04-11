package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlagChangeEventTest {

    @Test
    void recordFields() {
        FlagChangeEvent event = new FlagChangeEvent("my-flag", "websocket");
        assertEquals("my-flag", event.id());
        assertEquals("websocket", event.source());
    }

    @Test
    void equalsAndHashCode() {
        FlagChangeEvent a = new FlagChangeEvent("flag", "manual");
        FlagChangeEvent b = new FlagChangeEvent("flag", "manual");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
