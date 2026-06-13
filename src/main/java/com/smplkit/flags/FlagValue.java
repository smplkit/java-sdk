package com.smplkit.flags;

/**
 * A constrained value entry on a {@link Flag}.
 *
 * <p>Lives in {@link Flag#values}. Frozen — author values via
 * {@link Flag#addValue} / {@link Flag#removeValue} / {@link Flag#clearValues}.</p>
 */
public record FlagValue(String name, Object value) {}
