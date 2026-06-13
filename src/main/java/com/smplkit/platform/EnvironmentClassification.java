package com.smplkit.platform;

/**
 * Whether an environment participates in the canonical ordering.
 *
 * <p>STANDARD environments are the customer's deploy targets — production,
 * staging, development, etc. They participate in
 * {@code account.settings.environment_order} and appear in the standard
 * Console environment columns.</p>
 *
 * <p>AD_HOC environments are transient targets (preview branches,
 * individual developer sandboxes) that should not appear in the
 * standard ordering.</p>
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
