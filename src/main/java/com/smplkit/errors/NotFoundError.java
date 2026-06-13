package com.smplkit.errors;

import java.util.List;

/** Raised when a requested resource does not exist (HTTP 404). */
public class NotFoundError extends SmplError {

    /**
     * Creates a not-found error.
     *
     * @param message the human-readable error message
     * @param responseBody the raw response body, or null if not available
     */
    public NotFoundError(String message, String responseBody) {
        super(message, 404, responseBody);
    }

    /**
     * Creates a not-found error carrying structured server-side error details.
     *
     * @param message the human-readable error message
     * @param responseBody the raw response body, or null if not available
     * @param errors the structured error details from the server's JSON:API
     *     {@code errors} array
     */
    public NotFoundError(String message, String responseBody, List<ApiErrorDetail> errors) {
        super(message, 404, responseBody, errors);
    }
}
