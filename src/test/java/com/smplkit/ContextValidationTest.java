package com.smplkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors the boundary-validation tests from the Python SDK's Context tests.
 *
 * <p>Java's static type system already prevents non-string {@code type} / {@code key}
 * at compile time, but null and empty strings still need explicit rejection
 * — they are the closest Java analogue of Python's {@code TypeError} on
 * {@code Context(123, "x")}.</p>
 */
class ContextValidationTest {

    @Test
    void constructor_rejectsNullType() {
        assertThrows(NullPointerException.class, () -> new Context(null, "key"));
    }

    @Test
    void constructor_rejectsNullKey() {
        assertThrows(NullPointerException.class, () -> new Context("user", null));
    }

    @Test
    void constructor_rejectsEmptyType() {
        assertThrows(IllegalArgumentException.class, () -> new Context("", "key"));
    }

    @Test
    void constructor_rejectsEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> new Context("user", ""));
    }

    @Test
    void builder_rejectsEmptyType() {
        assertThrows(IllegalArgumentException.class, () -> Context.builder("", "k"));
    }

    @Test
    void builder_rejectsEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> Context.builder("user", ""));
    }

    @Test
    void id_returnsCompositeTypeKey() {
        Context ctx = new Context("user", "u-123");
        assertEquals("user:u-123", ctx.id());
    }

    @Test
    void twoArgConstructor_yieldsEmptyAttributes() {
        Context ctx = new Context("user", "u-123");
        assertTrue(ctx.attributes().isEmpty());
    }
}
