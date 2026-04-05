package com.smplkit.errors;

import java.util.List;

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

    /**
     * Creates a new SmplConflictException with parsed JSON:API errors.
     *
     * @param message      human-readable error description
     * @param responseBody raw response body
     * @param errors       parsed error details
     */
    public SmplConflictException(String message, String responseBody, List<ApiError> errors) {
        super(message, 409, responseBody, errors);
    }
}
