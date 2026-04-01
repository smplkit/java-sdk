package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlagTypeTest {

    @Test
    void allTypesExist() {
        assertEquals(4, FlagType.values().length);
        assertNotNull(FlagType.BOOLEAN);
        assertNotNull(FlagType.STRING);
        assertNotNull(FlagType.NUMERIC);
        assertNotNull(FlagType.JSON);
    }

    @Test
    void valueOfRoundTrips() {
        for (FlagType type : FlagType.values()) {
            assertEquals(type, FlagType.valueOf(type.name()));
        }
    }
}
