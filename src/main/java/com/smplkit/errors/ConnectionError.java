package com.smplkit.errors;

/** Raised when a network request fails (DNS resolution, connection refused, etc.). */
public class ConnectionError extends SmplError {

    public ConnectionError(String message, Throwable cause) {
        super(message, 0, null, cause);
    }
}
