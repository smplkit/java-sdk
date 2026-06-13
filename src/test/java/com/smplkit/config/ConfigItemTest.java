package com.smplkit.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Covers {@link ConfigItem} (all four constructors, accessors, toString) and {@link ItemType}. */
class ConfigItemTest {

    @Test
    void ctor_enumType_noDescription() {
        ConfigItem item = new ConfigItem("k", "v", ItemType.STRING);
        assertEquals("k", item.name());
        assertEquals("v", item.value());
        assertEquals(ItemType.STRING, item.type());
        assertNull(item.description());
    }

    @Test
    void ctor_enumType_withDescription() {
        ConfigItem item = new ConfigItem("k", 5, ItemType.NUMBER, "the count");
        assertEquals("the count", item.description());
        assertEquals(ItemType.NUMBER, item.type());
    }

    @Test
    void ctor_stringType_noDescription() {
        ConfigItem item = new ConfigItem("flag", true, "BOOLEAN");
        assertEquals(ItemType.BOOLEAN, item.type());
        assertNull(item.description());
    }

    @Test
    void ctor_stringType_withDescription() {
        ConfigItem item = new ConfigItem("blob", java.util.Map.of("a", 1), "JSON", "a payload");
        assertEquals(ItemType.JSON, item.type());
        assertEquals("a payload", item.description());
    }

    @Test
    void toString_includesNameValueAndType() {
        ConfigItem item = new ConfigItem("k", "v", ItemType.STRING);
        String s = item.toString();
        assertTrue(s.contains("k"));
        assertTrue(s.contains("v"));
        assertTrue(s.contains("STRING"));
    }

    // --- ItemType ---

    @Test
    void itemType_value_returnsWireString() {
        assertEquals("STRING", ItemType.STRING.value());
        assertEquals("NUMBER", ItemType.NUMBER.value());
        assertEquals("BOOLEAN", ItemType.BOOLEAN.value());
        assertEquals("JSON", ItemType.JSON.value());
    }

    @Test
    void itemType_fromValue_caseInsensitive() {
        assertEquals(ItemType.STRING, ItemType.fromValue("STRING"));
        assertEquals(ItemType.NUMBER, ItemType.fromValue("number"));
        assertEquals(ItemType.BOOLEAN, ItemType.fromValue("Boolean"));
        assertEquals(ItemType.JSON, ItemType.fromValue("json"));
    }

    @Test
    void itemType_fromValue_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> ItemType.fromValue("WHATEVER"));
    }
}
