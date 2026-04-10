package com.smplkit.flags;

/**
 * Diagnostic statistics for the flags runtime.
 *
 * @param cacheHits   number of evaluations served from cache
 * @param cacheMisses number of evaluations that required fresh computation
 */
public record FlagStats(long cacheHits, long cacheMisses) {
}
