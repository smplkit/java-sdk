package com.smplkit.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigStats}.
 */
class ConfigStatsTest {

    @Test
    void fetchCount_accessor() {
        ConfigStats stats = new ConfigStats(5);
        assertEquals(5, stats.fetchCount());
    }

    @Test
    void equality() {
        ConfigStats s1 = new ConfigStats(3);
        ConfigStats s2 = new ConfigStats(3);
        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
    }

    @Test
    void notEqual_differentFetchCount() {
        assertNotEquals(new ConfigStats(1), new ConfigStats(2));
    }
}
