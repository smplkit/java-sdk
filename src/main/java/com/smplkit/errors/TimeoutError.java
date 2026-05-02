package com.smplkit.errors;

/** Raised when an operation exceeds its timeout. */
public class TimeoutError extends SmplError {

    public TimeoutError(String message, Throwable cause) {
        super(message, 0, null, cause);
    }
}
