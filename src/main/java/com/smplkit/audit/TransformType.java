package com.smplkit.audit;

/**
 * Engine used to evaluate a forwarder's {@code transform}.
 *
 * <p>Today only {@link #JSONATA} is supported. A {@code TransformType}
 * member's {@link #getValue()} returns its raw string
 * ({@code TransformType.JSONATA.getValue().equals("JSONATA")}).</p>
 */
public enum TransformType {
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
