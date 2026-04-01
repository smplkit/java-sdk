package com.smplkit.flags;

import java.util.List;
import java.util.function.Consumer;

/**
 * A typed handle to a feature flag, obtained via
 * {@link FlagsClient#boolFlag}, {@link FlagsClient#stringFlag},
 * {@link FlagsClient#numberFlag}, or {@link FlagsClient#jsonFlag}.
 *
 * <p>Evaluation is always synchronous and local — no network calls on {@code get()}.</p>
 *
 * @param <T> the flag value type
 */
public final class FlagHandle<T> {

    private final String key;
    private final T defaultValue;
    private final Class<T> type;
    private FlagsClient namespace;

    FlagHandle(String key, T defaultValue, Class<T> type) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.type = type;
    }

    void setNamespace(FlagsClient namespace) {
        this.namespace = namespace;
    }

    /** Returns the flag key. */
    public String key() { return key; }

    /** Returns the code-level default value. */
    public T defaultValue() { return defaultValue; }

    /**
     * Evaluates the flag using the context provider.
     *
     * @return the resolved value, or the default if not connected or no match
     */
    public T get() {
        return get(null);
    }

    /**
     * Evaluates the flag with an explicit context override.
     *
     * @param contexts the contexts to evaluate against (bypasses context provider)
     * @return the resolved value, or the default if not connected or no match
     */
    @SuppressWarnings("unchecked")
    public T get(List<Context> contexts) {
        if (namespace == null) {
            return defaultValue;
        }
        Object raw = namespace.evaluateHandle(key, defaultValue, contexts);
        if (raw == null) {
            return defaultValue;
        }
        // Type coercion
        if (type == Boolean.class) {
            if (raw instanceof Boolean) return (T) raw;
            return defaultValue;
        }
        if (type == String.class) {
            if (raw instanceof String) return (T) raw;
            return defaultValue;
        }
        if (type == Number.class) {
            // Reject boolean (in some JSON parsers boolean could be numeric)
            if (raw instanceof Boolean) return defaultValue;
            if (raw instanceof Number) return (T) raw;
            return defaultValue;
        }
        // JSON type — expect Map
        if (type == Object.class) {
            return (T) raw;
        }
        return defaultValue;
    }

    /**
     * Registers a listener that fires when this specific flag changes.
     *
     * @param listener called with a {@link FlagChangeEvent} on each change
     */
    public void onChange(Consumer<FlagChangeEvent> listener) {
        if (namespace != null) {
            namespace.onFlagChange(key, listener);
        }
    }
}
