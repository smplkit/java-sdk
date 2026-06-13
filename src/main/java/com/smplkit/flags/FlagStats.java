package com.smplkit.flags;

/**
 * Evaluation statistics for the flags runtime.
 *
 * @param cacheHits   number of evaluations that reused a cached result
 * @param cacheMisses number of evaluations that required a fresh computation
 */
public record FlagStats(long cacheHits, long cacheMisses) {
}
