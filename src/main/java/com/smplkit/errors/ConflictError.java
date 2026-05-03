package com.smplkit.errors;

import java.util.List;

/** Raised when an operation conflicts with current state (HTTP 409). */
public class ConflictError extends SmplError {

    public ConflictError(String message, String responseBody) {
        super(message, 409, responseBody);
    }

    public ConflictError(String message, String responseBody, List<ApiErrorDetail> errors) {
        super(message, 409, responseBody, errors);
    }
}
