package com.smplkit.flags;

import java.util.List;

/**
 * Per-environment view of a flag.
 *
 * <p>Immutable record; mirrors Python's {@code FlagEnvironment}. Customer
 * code reads {@code flag.environments().get(envKey).enabled()} etc.;
 * mutations go through the resource setters ({@link Flag#setDefault},
 * {@link Flag#enableRules}, etc.) and are persisted via {@link Flag#save()}.</p>
 */
public record FlagEnvironment(boolean enabled, Object defaultValue, List<FlagRule> rules) {

    public FlagEnvironment {
        rules = rules != null ? List.copyOf(rules) : List.of();
    }
}
