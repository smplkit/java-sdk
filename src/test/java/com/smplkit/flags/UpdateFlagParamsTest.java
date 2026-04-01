package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UpdateFlagParamsTest {

    @Test
    void emptyBuilder() {
        UpdateFlagParams params = UpdateFlagParams.builder().build();
        assertNull(params.name());
        assertNull(params.description());
        assertNull(params.defaultValue());
        assertNull(params.values());
        assertNull(params.environments());
    }

    @Test
    void allFieldsSet() {
        UpdateFlagParams params = UpdateFlagParams.builder()
                .name("New Name")
                .description("New Desc")
                .defaultValue(true)
                .environments(Map.of("prod", Map.of("enabled", true)))
                .build();
        assertEquals("New Name", params.name());
        assertEquals("New Desc", params.description());
        assertEquals(true, params.defaultValue());
        assertNotNull(params.environments());
    }
}
