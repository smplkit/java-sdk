package com.smplkit.errors;

/**
 * Raised when an operation conflicts with the current state (HTTP 409).
 *
 * <p>For example, attempting to delete a config that has children.</p>
 */
public class SmplConflictException extends SmplException {

    /**
     * Creates a new SmplConflictException.
     *
     * @param message      human-readable error description
     * @param responseBody raw response body
     */
    public SmplConflictException(String message, String responseBody) {
        super(message, 409, responseBody);
    }
}
