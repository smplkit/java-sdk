package com.smplkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColorTest {

    @Test
    void hex_acceptsRgbForm() {
        assertEquals("#abc", new Color("#abc").hex());
    }

    @Test
    void hex_acceptsRrggbbForm() {
        assertEquals("#aabbcc", new Color("#AABBCC").hex());
    }

    @Test
    void hex_acceptsRrggbbaaForm() {
        assertEquals("#aabbccdd", new Color("#AABBCCDD").hex());
    }

    @Test
    void hex_normalisesToLowercase() {
        assertEquals("#0066cc", new Color("#0066CC").hex());
    }

    @Test
    void hex_rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new Color(null));
    }

    @Test
    void hex_rejectsMissingHash() {
        assertThrows(IllegalArgumentException.class, () -> new Color("aabbcc"));
    }

    @Test
    void hex_rejectsBadLength() {
        assertThrows(IllegalArgumentException.class, () -> new Color("#abcd"));
    }

    @Test
    void hex_rejectsNonHexChars() {
        assertThrows(IllegalArgumentException.class, () -> new Color("#zzzzzz"));
    }

    @Test
    void rgb_acceptsValidComponents() {
        assertEquals("#ff8000", Color.rgb(255, 128, 0).hex());
    }

    @Test
    void rgb_rejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> Color.rgb(-1, 0, 0));
    }

    @Test
    void rgb_rejectsTooLarge() {
        assertThrows(IllegalArgumentException.class, () -> Color.rgb(256, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Color.rgb(0, 256, 0));
        assertThrows(IllegalArgumentException.class, () -> Color.rgb(0, 0, 256));
    }

    @Test
    void toString_returnsHex() {
        assertEquals("#ffffff", new Color("#ffffff").toString());
    }

    @Test
    void equalsAndHashCode_basedOnHex() {
        Color a = new Color("#aabbcc");
        Color b = new Color("#AABBCC");
        Color c = new Color("#000000");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(null, a);
        assertNotEquals("not a color", a);
        assertEquals(a, a);  // self-equality
    }
}
