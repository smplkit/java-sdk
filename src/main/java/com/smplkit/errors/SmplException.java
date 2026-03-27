package com.smplkit.errors;

/**
 * Base unchecked exception for all smplkit SDK errors.
 *
 * <p>All SDK exceptions extend this class, making it easy to catch any
 * SDK error with a single {@code catch (SmplException e)} block.</p>
 */
public class SmplException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    /**
     * Creates a new SmplException.
     *
     * @param message      human-readable error description
     * @param statusCode   HTTP status code (0 if not applicable)
     * @param responseBody raw response body (may be null)
     */
    public SmplException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * Creates a new SmplException with a cause.
     *
     * @param message      human-readable error description
     * @param statusCode   HTTP status code (0 if not applicable)
     * @param responseBody raw response body (may be null)
     * @param cause        the underlying cause
     */
    public SmplException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /**
     * Returns the HTTP status code, or 0 if not applicable (e.g., connection errors).
     *
     * @return the HTTP status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the raw response body, or null if not available.
     *
     * @return the response body
     */
    public String responseBody() {
        return responseBody;
    }
}
