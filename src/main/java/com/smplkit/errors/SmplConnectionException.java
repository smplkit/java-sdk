package com.smplkit.errors;

/**
 * Raised when a network request fails (e.g., DNS resolution failure,
 * connection refused).
 */
public class SmplConnectionException extends SmplException {

    /**
     * Creates a new SmplConnectionException.
     *
     * @param message human-readable error description
     * @param cause   the underlying cause
     */
    public SmplConnectionException(String message, Throwable cause) {
        super(message, 0, null, cause);
    }
}
