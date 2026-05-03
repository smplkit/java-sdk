package com.smplkit.flags;

/**
 * Constrained-value entry on a flag — a (name, value) pair shown in dashboards
 * and used for value-pinning rules. Immutable record; mirrors Python's
 * {@code FlagValue}.
 */
public record FlagValue(String name, Object value) {}
