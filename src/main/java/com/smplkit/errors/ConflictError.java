package com.smplkit.errors;

import java.util.List;

/** Raised when an operation conflicts with current state (HTTP 409). */
public class ConflictError extends SmplError {

    /**
     * Creates a conflict error.
     *
     * @param message the human-readable error message
     * @param responseBody the raw response body, or null if not available
     */
    public ConflictError(String message, String responseBody) {
        super(message, 409, responseBody);
    }

    /**
     * Creates a conflict error carrying structured server-side error details.
     *
     * @param message the human-readable error message
     * @param responseBody the raw response body, or null if not available
     * @param errors the structured error details from the server's JSON:API
     *     {@code errors} array
     */
    public ConflictError(String message, String responseBody, List<ApiErrorDetail> errors) {
        super(message, 409, responseBody, errors);
    }
}
