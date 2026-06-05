package com.smplkit.jobs;

/**
 * HTTP verb a job uses when it fires.
 *
 * <p>Mirrors the jobs service's OpenAPI method enum so callers get
 * autocomplete and a typed value back from {@code job.configuration.method}.
 * Members are declared in alphabetical order.</p>
 */
public enum HttpMethod {
    /** {@code DELETE} */
    DELETE("DELETE"),
    /** {@code GET} */
    GET("GET"),
    /** {@code PATCH} */
    PATCH("PATCH"),
    /** {@code POST} */
    POST("POST"),
    /** {@code PUT} */
    PUT("PUT");

    private final String value;

    HttpMethod(String value) {
        this.value = value;
    }

    /** The wire-format slug — e.g. {@code "POST"}. */
    public String getValue() {
        return value;
    }

    /** Parse a wire-format slug. Throws on unknown values. */
    public static HttpMethod fromValue(String value) {
        for (HttpMethod m : values()) {
            if (m.value.equals(value)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown HttpMethod: " + value);
    }
}
