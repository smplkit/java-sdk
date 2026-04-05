package com.smplkit.errors;

import java.util.List;

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

    /**
     * Creates a new SmplNotFoundException with parsed JSON:API errors.
     *
     * @param message      human-readable error description
     * @param responseBody raw response body
     * @param errors       parsed error details
     */
    public SmplNotFoundException(String message, String responseBody, List<ApiError> errors) {
        super(message, 404, responseBody, errors);
    }
}
