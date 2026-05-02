package com.smplkit.flags;

import java.util.Map;

/**
 * Targeted rule on a flag environment.
 *
 * <p>Immutable record; mirrors Python's {@code FlagRule}. The {@code logic}
 * map is a JSON Logic predicate ({@code {}} means "always match"). Mutations
 * go through {@link Flag#addRule}, never by mutating the rule directly.</p>
 */
public record FlagRule(Map<String, Object> logic, Object value, String description) {

    public FlagRule {
        // Defensive copy — the rule must be observably immutable even if the
        // caller-provided map mutates afterward.
        logic = logic != null ? Map.copyOf(logic) : Map.of();
    }
}
