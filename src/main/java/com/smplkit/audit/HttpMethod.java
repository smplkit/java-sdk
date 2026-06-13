package com.smplkit.audit;

/**
 * HTTP verb used by a forwarder's outbound delivery.
 *
 * <p>Mirrors the audit spec's {@code HttpConfigurationMethod} enum so
 * customers get autocomplete and a typed value back from
 * {@code forwarder.configuration.method}. A {@code HttpMethod} member's
 * {@link #getValue()} returns its raw string
 * ({@code HttpMethod.POST.getValue().equals("POST")}).</p>
 */
public enum HttpMethod {
    DELETE("DELETE"),
    GET("GET"),
    PATCH("PATCH"),
    POST("POST"),
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
