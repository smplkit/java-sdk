package com.smplkit.errors;

/** Raised when a network request fails (DNS resolution, connection refused, etc.). */
public class ConnectionError extends SmplError {

    /**
     * Creates a connection error wrapping the underlying network failure.
     *
     * @param message the human-readable error message
     * @param cause the underlying exception (e.g. an {@code IOException}) that
     *     triggered this error
     */
    public ConnectionError(String message, Throwable cause) {
        super(message, 0, null, cause);
    }
}
