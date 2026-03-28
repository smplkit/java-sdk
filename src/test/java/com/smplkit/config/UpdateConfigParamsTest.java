package com.smplkit.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UpdateConfigParams}.
 */
class UpdateConfigParamsTest {

    @Test
    void builder_setAllFields() {
        Map<String, Object> values = Map.of("a", 1);
        Map<String, Map<String, Object>> environments = Map.of("production", Map.of("b", 2));

        UpdateConfigParams params = UpdateConfigParams.builder()
                .name("New Name")
                .description("New description")
                .values(values)
                .environments(environments)
                .build();

        assertEquals("New Name", params.name());
        assertEquals("New description", params.description());
        assertEquals(values, params.values());
        assertEquals(environments, params.environments());
    }

    @Test
    void builder_allFieldsNullByDefault() {
        UpdateConfigParams params = UpdateConfigParams.builder().build();

        assertNull(params.name());
        assertNull(params.description());
        assertNull(params.values());
        assertNull(params.environments());
    }

    @Test
    void builder_partialFields() {
        UpdateConfigParams params = UpdateConfigParams.builder()
                .name("Only Name")
                .build();

        assertEquals("Only Name", params.name());
        assertNull(params.description());
        assertNull(params.values());
        assertNull(params.environments());
    }
}
