package com.smplkit.audit;

/**
 * Engine used to evaluate a forwarder's {@code transform}. Must be set
 * whenever {@code transform} is set. Today only {@link #JSONATA} is
 * supported; new engines will be added as the audit service grows.
 *
 * <p>Members are declared in alphabetical order.</p>
 */
public enum TransformType {
    /** JSONata expression — see https://jsonata.org. */
    JSONATA("JSONATA");

    private final String value;

    TransformType(String value) {
        this.value = value;
    }

    /** The wire-format slug — e.g. {@code "JSONATA"}. */
    public String getValue() {
        return value;
    }

    /** Parse a wire-format slug. Throws on unknown values. */
    public static TransformType fromValue(String value) {
        for (TransformType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown TransformType: " + value);
    }
}
