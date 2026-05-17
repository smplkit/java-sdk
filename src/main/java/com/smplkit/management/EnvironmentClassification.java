package com.smplkit.management;

/**
 * Whether an environment participates in the canonical ordering.
 *
 * <p>{@link #STANDARD} environments are customer deploy targets (production,
 * staging, development, etc.). They appear in
 * {@code account_settings.environment_order} and the standard Console
 * columns.</p>
 *
 * <p>{@link #AD_HOC} environments are transient targets (preview branches,
 * dev sandboxes) that should not appear in the standard ordering.</p>
 *
 * <p>Members are declared in alphabetical order.</p>
 */
public enum EnvironmentClassification {
    /** Transient target excluded from the canonical environment ordering. */
    AD_HOC("AD_HOC"),
    /** Customer deploy target; appears in the canonical ordering. */
    STANDARD("STANDARD");

    private final String value;

    EnvironmentClassification(String value) {
        this.value = value;
    }

    /** The wire-format slug — e.g. {@code "STANDARD"}. */
    public String getValue() {
        return value;
    }

    /** Parse a wire-format slug, defaulting to {@link #STANDARD} for {@code null} / unknown values. */
    public static EnvironmentClassification fromValue(String value) {
        if (value == null) return STANDARD;
        for (EnvironmentClassification c : values()) {
            if (c.value.equalsIgnoreCase(value)) return c;
        }
        return STANDARD;
    }
}
