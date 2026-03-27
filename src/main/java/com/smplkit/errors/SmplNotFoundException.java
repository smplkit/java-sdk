package com.smplkit.errors;

/**
 * Raised when a requested resource does not exist (HTTP 404).
 */
public class SmplNotFoundException extends SmplException {

    /**
     * Creates a new SmplNotFoundException.
     *
     * @param message      human-readable error description
     * @param responseBody raw response body
     */
    public SmplNotFoundException(String message, String responseBody) {
        super(message, 404, responseBody);
    }
}
