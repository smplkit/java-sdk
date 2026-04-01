package com.smplkit.flags;

/**
 * Diagnostic statistics for the flags runtime.
 *
 * @param cacheHits   number of resolution cache hits
 * @param cacheMisses number of resolution cache misses
 */
public record FlagStats(long cacheHits, long cacheMisses) {
}
