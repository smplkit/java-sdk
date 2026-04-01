package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextTest {

    @Test
    void constructorWithAttributes() {
        Context ctx = new Context("user", "u-123", Map.of("plan", "enterprise"));
        assertEquals("user", ctx.type());
        assertEquals("u-123", ctx.key());
        assertNull(ctx.name());
        assertEquals(Map.of("plan", "enterprise"), ctx.attributes());
    }

    @Test
    void constructorWithNullAttributes() {
        Context ctx = new Context("user", "u-123", null);
        assertTrue(ctx.attributes().isEmpty());
    }

    @Test
    void constructorRejectsNullType() {
        assertThrows(NullPointerException.class, () -> new Context(null, "key", Map.of()));
    }

    @Test
    void constructorRejectsNullKey() {
        assertThrows(NullPointerException.class, () -> new Context("user", null, Map.of()));
    }

    @Test
    void builderPattern() {
        Context ctx = Context.builder("account", "acme")
                .name("Acme Corp")
                .attr("region", "us")
                .attr("tier", "premium")
                .build();
        assertEquals("account", ctx.type());
        assertEquals("acme", ctx.key());
        assertEquals("Acme Corp", ctx.name());
        assertEquals("us", ctx.attributes().get("region"));
        assertEquals("premium", ctx.attributes().get("tier"));
    }

    @Test
    void toEvalDictInjectsKey() {
        Context ctx = new Context("user", "u-123", Map.of("plan", "enterprise"));
        Map<String, Object> dict = ctx.toEvalDict();
        assertEquals("u-123", dict.get("key"));
        assertEquals("enterprise", dict.get("plan"));
    }

    @Test
    void attributesAreImmutable() {
        Context ctx = new Context("user", "u-123", Map.of("plan", "enterprise"));
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.attributes().put("new", "value"));
    }

    @Test
    void equalsAndHashCode() {
        Context a = new Context("user", "u-123", Map.of("plan", "enterprise"));
        Context b = new Context("user", "u-123", Map.of("plan", "enterprise"));
        Context c = new Context("user", "u-456", Map.of("plan", "enterprise"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void toStringIncludesFields() {
        Context ctx = new Context("user", "u-123", Map.of());
        String str = ctx.toString();
        assertTrue(str.contains("user"));
        assertTrue(str.contains("u-123"));
    }
}
