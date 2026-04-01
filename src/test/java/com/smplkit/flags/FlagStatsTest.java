package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlagStatsTest {

    @Test
    void recordFields() {
        FlagStats stats = new FlagStats(42, 7);
        assertEquals(42, stats.cacheHits());
        assertEquals(7, stats.cacheMisses());
    }

    @Test
    void equalsAndHashCode() {
        FlagStats a = new FlagStats(1, 2);
        FlagStats b = new FlagStats(1, 2);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
