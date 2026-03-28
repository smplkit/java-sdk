package com.smplkit.config;

/**
 * Diagnostic statistics for a {@link ConfigRuntime} instance.
 *
 * @param fetchCount total number of HTTP chain-fetches performed, including the initial
 *                   connect and any reconnection re-syncs or manual refreshes
 */
public record ConfigStats(int fetchCount) {}
