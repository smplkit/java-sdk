package com.smplkit.errors;

/**
 * Raised when a logging operation is attempted before {@code install()}.
 *
 * <p>Smpl Logging hooks into the application's logging framework, so it stays
 * opt-in: its live surface requires an explicit
 * {@link com.smplkit.logging.LoggingClient#install()} first. Config and flags
 * connect lazily on first live use and never raise this.</p>
 */
public class NotInstalledError extends SmplError {

    /**
     * Creates a not-installed error.
     *
     * @param message the human-readable error message
     */
    public NotInstalledError(String message) {
        super(message, 0, null);
    }
}
