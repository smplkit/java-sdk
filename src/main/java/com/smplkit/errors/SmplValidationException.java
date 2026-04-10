package com.smplkit.errors;

import java.util.List;

/**
 * Raised when the server rejects a request due to validation errors (HTTP 400 or 422).
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

    /**
     * Creates a new SmplValidationException with structured error details.
     *
     * @param message      human-readable error description
     * @param statusCode   HTTP status code (400 or 422)
     * @param responseBody raw response body
     * @param errors       parsed error details
     */
    public SmplValidationException(String message, int statusCode, String responseBody, List<ApiError> errors) {
        super(message, statusCode, responseBody, errors);
    }
}
