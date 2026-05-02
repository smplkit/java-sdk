package com.smplkit.errors;

import java.util.List;

/** Raised when a requested resource does not exist (HTTP 404). */
public class NotFoundError extends SmplError {

    public NotFoundError(String message, String responseBody) {
        super(message, 404, responseBody);
    }

    public NotFoundError(String message, String responseBody, List<ApiErrorDetail> errors) {
        super(message, 404, responseBody, errors);
    }
}
