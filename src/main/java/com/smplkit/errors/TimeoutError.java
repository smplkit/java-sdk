package com.smplkit.errors;

/** Raised when an operation exceeds its timeout. */
public class TimeoutError extends SmplError {

    /**
     * Creates a timeout error.
     *
     * @param message the human-readable error message
     * @param cause the underlying exception that triggered this error, or null
     */
    public TimeoutError(String message, Throwable cause) {
        super(message, 0, null, cause);
    }
}
