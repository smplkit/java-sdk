package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CreateFlagParamsTest {

    @Test
    void builderWithRequiredFields() {
        CreateFlagParams params = CreateFlagParams.builder("my-flag", "My Flag", FlagType.BOOLEAN)
                .build();
        assertEquals("my-flag", params.key());
        assertEquals("My Flag", params.name());
        assertEquals(FlagType.BOOLEAN, params.type());
        assertNull(params.defaultValue());
        assertNull(params.description());
        assertNull(params.values());
    }

    @Test
    void builderWithAllFields() {
        CreateFlagParams params = CreateFlagParams.builder("color", "Color", FlagType.STRING)
                .defaultValue("red")
                .description("Primary color")
                .values(List.of(
                        Map.of("name", "Red", "value", "red"),
                        Map.of("name", "Blue", "value", "blue")
                ))
                .build();
        assertEquals("color", params.key());
        assertEquals("Color", params.name());
        assertEquals(FlagType.STRING, params.type());
        assertEquals("red", params.defaultValue());
        assertEquals("Primary color", params.description());
        assertEquals(2, params.values().size());
    }

    @Test
    void builderRejectsNullKey() {
        assertThrows(NullPointerException.class,
                () -> CreateFlagParams.builder(null, "name", FlagType.BOOLEAN));
    }

    @Test
    void builderRejectsNullName() {
        assertThrows(NullPointerException.class,
                () -> CreateFlagParams.builder("key", null, FlagType.BOOLEAN));
    }

    @Test
    void builderRejectsNullType() {
        assertThrows(NullPointerException.class,
                () -> CreateFlagParams.builder("key", "name", null));
    }
}
