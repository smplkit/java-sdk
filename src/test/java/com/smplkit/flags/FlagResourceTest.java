package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlagResourceTest {

    @Test
    void fieldsAreAccessible() {
        Instant now = Instant.now();
        FlagResource resource = new FlagResource(
                "id-1", "my-flag", "My Flag", "A flag", "BOOLEAN",
                false, List.of(Map.of("name", "True", "value", true)),
                Map.of("prod", Map.of("enabled", true)),
                now, now
        );
        assertEquals("id-1", resource.id());
        assertEquals("my-flag", resource.key());
        assertEquals("My Flag", resource.name());
        assertEquals("A flag", resource.description());
        assertEquals("BOOLEAN", resource.type());
        assertEquals(false, resource.defaultValue());
        assertEquals(1, resource.values().size());
        assertFalse(resource.environments().isEmpty());
        assertEquals(now, resource.createdAt());
        assertEquals(now, resource.updatedAt());
    }

    @Test
    void nullValuesDefaultToEmpty() {
        FlagResource resource = new FlagResource(
                "id", "key", "name", null, "BOOLEAN",
                null, null, null, null, null
        );
        assertTrue(resource.values().isEmpty());
        assertTrue(resource.environments().isEmpty());
        assertNull(resource.description());
        assertNull(resource.defaultValue());
    }

    @Test
    void updateThrowsWithoutClient() {
        FlagResource resource = new FlagResource(
                "id", "key", "name", null, "BOOLEAN",
                false, null, null, null, null
        );
        assertThrows(IllegalStateException.class,
                () -> resource.update(UpdateFlagParams.builder().build()));
    }

    @Test
    void addRuleThrowsWithoutClient() {
        FlagResource resource = new FlagResource(
                "id", "key", "name", null, "BOOLEAN",
                false, null, null, null, null
        );
        assertThrows(IllegalStateException.class,
                () -> resource.addRule(Map.of()));
    }
}
