package com.smplkit.management;

/**
 * Whether an environment participates in the canonical ordering.
 *
 * <p>STANDARD environments are customer deploy targets (production, staging, development, etc.).
 * They appear in {@code account_settings.environment_order} and the standard Console columns.</p>
 *
 * <p>AD_HOC environments are transient targets (preview branches, dev sandboxes) that should
 * not appear in the standard ordering.</p>
 */
public enum EnvironmentClassification {
    STANDARD("STANDARD"),
    AD_HOC("AD_HOC");

    private final String value;

    EnvironmentClassification(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static EnvironmentClassification fromValue(String value) {
        if (value == null) return STANDARD;
        for (EnvironmentClassification c : values()) {
            if (c.value.equalsIgnoreCase(value)) return c;
        }
        return STANDARD;
    }
}
