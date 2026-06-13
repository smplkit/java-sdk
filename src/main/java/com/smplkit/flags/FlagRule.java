package com.smplkit.flags;

import java.util.Map;

/**
 * A single targeting rule on a {@link Flag}.
 *
 * <p>Lives in {@link FlagEnvironment#rules}. Frozen — author rules via the
 * {@link com.smplkit.Rule} fluent builder and pass through {@link Flag#addRule}.</p>
 *
 * @param logic       JSON Logic predicate. Empty map means "always match".
 * @param value       Value to serve when {@code logic} evaluates truthy.
 * @param description Human-readable label (optional).
 */
public record FlagRule(Map<String, Object> logic, Object value, String description) {

    public FlagRule {
        // Defensive copy — the rule must be observably immutable even if the
        // caller-provided map mutates afterward.
        logic = logic != null ? Map.copyOf(logic) : Map.of();
    }
}
