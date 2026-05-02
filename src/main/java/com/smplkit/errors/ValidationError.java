package com.smplkit.errors;

import java.util.List;

/** Raised when the server rejects a request due to validation errors (HTTP 400/422). */
public class ValidationError extends SmplError {

    public ValidationError(String message, String responseBody) {
        super(message, 422, responseBody);
    }

    public ValidationError(String message, int statusCode, String responseBody, List<ApiErrorDetail> errors) {
        super(message, statusCode, responseBody, errors);
    }
}
