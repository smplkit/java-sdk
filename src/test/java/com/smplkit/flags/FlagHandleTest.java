package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlagHandleTest {

    @Test
    void boolHandleReturnsDefault_whenNoNamespace() {
        FlagHandle<Boolean> handle = new FlagHandle<>("flag", false, Boolean.class);
        assertFalse(handle.get());
    }

    @Test
    void stringHandleReturnsDefault_whenNoNamespace() {
        FlagHandle<String> handle = new FlagHandle<>("flag", "default", String.class);
        assertEquals("default", handle.get());
    }

    @Test
    void numberHandleReturnsDefault_whenNoNamespace() {
        FlagHandle<Number> handle = new FlagHandle<>("flag", 42, Number.class);
        assertEquals(42, handle.get());
    }

    @Test
    void jsonHandleReturnsDefault_whenNoNamespace() {
        FlagHandle<Object> handle = new FlagHandle<>("flag", null, Object.class);
        assertNull(handle.get());
    }

    @Test
    void keyAndDefaultValue() {
        FlagHandle<Boolean> handle = new FlagHandle<>("my-flag", true, Boolean.class);
        assertEquals("my-flag", handle.key());
        assertTrue(handle.defaultValue());
    }

    @Test
    void getWithExplicitContextReturnsDefault_whenNoNamespace() {
        FlagHandle<String> handle = new FlagHandle<>("flag", "fallback", String.class);
        assertEquals("fallback", handle.get(java.util.List.of()));
    }

    @Test
    void onChangeDoesNotThrow_whenNoNamespace() {
        FlagHandle<Boolean> handle = new FlagHandle<>("flag", false, Boolean.class);
        assertDoesNotThrow(() -> handle.onChange(e -> {}));
    }
}
