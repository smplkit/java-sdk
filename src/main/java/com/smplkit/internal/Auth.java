package com.smplkit.internal;

import java.util.Objects;

/**
 * Simple authentication helper that holds the API key and produces
 * the {@code Authorization} header value.
 */
public final class Auth {

    private final String apiKey;

    /**
     * Creates a new Auth instance.
     *
     * @param apiKey the bearer token (API key)
     * @throws NullPointerException     if apiKey is null
     * @throws IllegalArgumentException if apiKey is blank
     */
    public Auth(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.apiKey = apiKey;
    }

    /**
     * Returns the full Authorization header value ({@code "Bearer <key>"}).
     *
     * @return the authorization header value
     */
    public String authorizationHeader() {
        return "Bearer " + apiKey;
    }

    /**
     * Returns the raw API key.
     *
     * @return the API key
     */
    public String apiKey() {
        return apiKey;
    }
}
