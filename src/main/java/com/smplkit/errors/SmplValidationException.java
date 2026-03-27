package com.smplkit.errors;

/**
 * Raised when the server rejects a request due to validation errors (HTTP 422).
 */
public class SmplValidationException extends SmplException {

    /**
     * Creates a new SmplValidationException.
     *
     * @param message      human-readable error description
     * @param responseBody raw response body
     */
    public SmplValidationException(String message, String responseBody) {
        super(message, 422, responseBody);
    }
}
