package com.smplkit.errors;

import java.util.List;

/** Raised when the account's plan does not include the requested feature (HTTP 402). */
public class PaymentRequiredError extends SmplError {

    /**
     * Creates a payment-required error.
     *
     * @param message the human-readable error message
     * @param responseBody the raw response body, or null if not available
     */
    public PaymentRequiredError(String message, String responseBody) {
        super(message, 402, responseBody);
    }

    /**
     * Creates a payment-required error carrying structured server-side error details.
     *
     * @param message the human-readable error message
     * @param responseBody the raw response body, or null if not available
     * @param errors the structured error details from the server's JSON:API
     *     {@code errors} array
     */
    public PaymentRequiredError(String message, String responseBody, List<ApiErrorDetail> errors) {
        super(message, 402, responseBody, errors);
    }
}
