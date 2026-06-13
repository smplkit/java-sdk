package com.smplkit.errors;

import java.util.List;

/** Raised when the server rejects a request due to validation errors (HTTP 400/422). */
public class ValidationError extends SmplError {

    /**
     * Creates a validation error with an HTTP 422 status.
     *
     * @param message the human-readable error message
     * @param responseBody the raw response body, or null if not available
     */
    public ValidationError(String message, String responseBody) {
        super(message, 422, responseBody);
    }

    /**
     * Creates a validation error with an explicit status (400 or 422) and
     * structured server-side error details.
     *
     * @param message the human-readable error message
     * @param statusCode the HTTP status code (typically 400 or 422)
     * @param responseBody the raw response body, or null if not available
     * @param errors the structured error details from the server's JSON:API
     *     {@code errors} array
     */
    public ValidationError(String message, int statusCode, String responseBody, List<ApiErrorDetail> errors) {
        super(message, statusCode, responseBody, errors);
    }
}
