package com.smplkit.errors;

/**
 * Raised when an operation exceeds its timeout.
 */
public class SmplTimeoutException extends SmplException {

    /**
     * Creates a new SmplTimeoutException.
     *
     * @param message human-readable error description
     * @param cause   the underlying cause
     */
    public SmplTimeoutException(String message, Throwable cause) {
        super(message, 0, null, cause);
    }
}
