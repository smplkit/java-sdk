package com.smplkit.errors;

import java.util.List;

/** Raised when the account's plan does not include the requested feature (HTTP 402). */
public class PaymentRequiredError extends SmplError {

    public PaymentRequiredError(String message, String responseBody) {
        super(message, 402, responseBody);
    }

    public PaymentRequiredError(String message, String responseBody, List<ApiErrorDetail> errors) {
        super(message, 402, responseBody, errors);
    }
}
