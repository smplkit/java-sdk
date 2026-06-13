package com.smplkit.flags;

import java.util.List;

/**
 * Per-environment configuration on a {@link Flag}.
 *
 * <p>Lives at {@code flag.environments().get(envName)} (a
 * {@code Map<String, FlagEnvironment>}). Frozen — mutate via {@link Flag#addRule} /
 * {@link Flag#enableRules} / {@link Flag#disableRules} / {@link Flag#setDefault} /
 * {@link Flag#clearRules} (with an {@code environment}).</p>
 *
 * @param enabled      Whether the flag is active in this environment.
 * @param defaultValue Environment-specific default override ({@code null} means no override).
 * @param rules        Targeting rules to evaluate, in order.
 */
public record FlagEnvironment(boolean enabled, Object defaultValue, List<FlagRule> rules) {

    public FlagEnvironment {
        rules = rules != null ? List.copyOf(rules) : List.of();
    }
}
