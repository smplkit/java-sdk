package com.smplkit.errors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the value-type contracts on {@link ApiErrorDetail} and its
 * {@link ApiErrorDetail.Source}: typed accessors plus equals/hashCode.
 */
class ApiErrorDetailEqualsTest {

    @Test
    void typedAccessors_returnConstructorValues() {
        ApiErrorDetail.Source src = new ApiErrorDetail.Source("/data/id");
        ApiErrorDetail err = new ApiErrorDetail("400", "Bad", "Detail", src);

        assertEquals("400", err.status());
        assertEquals("Bad", err.title());
        assertEquals("Detail", err.detail());
        assertSame(src, err.source());
        assertEquals("/data/id", src.pointer());
    }

    @Test
    void equals_sameFields_isEqual() {
        ApiErrorDetail a = new ApiErrorDetail("400", "T", "D",
                new ApiErrorDetail.Source("/x"));
        ApiErrorDetail b = new ApiErrorDetail("400", "T", "D",
                new ApiErrorDetail.Source("/x"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentFields_isNotEqual() {
        ApiErrorDetail base = new ApiErrorDetail("400", "T", "D",
                new ApiErrorDetail.Source("/x"));

        assertNotEquals(base, new ApiErrorDetail("500", "T", "D",
                new ApiErrorDetail.Source("/x")));
        assertNotEquals(base, new ApiErrorDetail("400", "OTHER", "D",
                new ApiErrorDetail.Source("/x")));
        assertNotEquals(base, new ApiErrorDetail("400", "T", "OTHER",
                new ApiErrorDetail.Source("/x")));
        assertNotEquals(base, new ApiErrorDetail("400", "T", "D",
                new ApiErrorDetail.Source("/y")));
        assertNotEquals(base, new ApiErrorDetail("400", "T", "D", null));
    }

    @Test
    void equals_self_isEqual() {
        ApiErrorDetail err = new ApiErrorDetail("400", "T", "D", null);
        assertEquals(err, err);
    }

    @Test
    void equals_otherType_isNotEqual() {
        ApiErrorDetail err = new ApiErrorDetail("400", "T", "D", null);
        assertNotEquals(err, "string");
        assertNotEquals(err, null);
    }

    @Test
    void source_equals_sameFields_isEqual() {
        ApiErrorDetail.Source a = new ApiErrorDetail.Source("/x");
        ApiErrorDetail.Source b = new ApiErrorDetail.Source("/x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void source_equals_differentFields_isNotEqual() {
        ApiErrorDetail.Source a = new ApiErrorDetail.Source("/x");
        ApiErrorDetail.Source b = new ApiErrorDetail.Source("/y");
        assertNotEquals(a, b);
    }

    @Test
    void source_equals_self_isEqual() {
        ApiErrorDetail.Source src = new ApiErrorDetail.Source("/x");
        assertEquals(src, src);
    }

    @Test
    void source_equals_otherType_isNotEqual() {
        ApiErrorDetail.Source src = new ApiErrorDetail.Source("/x");
        assertNotEquals(src, "string");
        assertNotEquals(src, null);
    }

    @Test
    void source_equals_nullPointers_isEqual() {
        ApiErrorDetail.Source a = new ApiErrorDetail.Source(null);
        ApiErrorDetail.Source b = new ApiErrorDetail.Source(null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
