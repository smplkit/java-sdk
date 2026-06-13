package com.smplkit.errors;

import java.util.Collections;
import java.util.List;

/**
 * Base unchecked exception for all smplkit SDK errors.
 *
 * <p>Mirrors the Python SDK's {@code Error} root. The {@code Smpl} prefix is
 * retained on the base class only to avoid collision with {@link java.lang.Error};
 * subclasses ({@link NotFoundError}, {@link ConflictError}, {@link ConnectionError},
 * {@link TimeoutError}, {@link ValidationError}) follow the unprefixed Python naming.</p>
 *
 * <p>When the server returns structured error details, they are available via
 * {@link #getErrors()} and included in {@link #toString()} output.</p>
 */
public class SmplError extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final List<ApiErrorDetail> errors;

    /**
     * Creates an error with no structured details and no underlying cause.
     *
     * @param message the human-readable error message
     * @param statusCode the HTTP status code, or 0 if not applicable
     * @param responseBody the raw response body, or null if not available
     */
    public SmplError(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errors = Collections.emptyList();
    }

    /**
     * Creates an error wrapping an underlying cause (e.g. a network failure).
     *
     * @param message the human-readable error message
     * @param statusCode the HTTP status code, or 0 if not applicable
     * @param responseBody the raw response body, or null if not available
     * @param cause the underlying exception that triggered this error
     */
    public SmplError(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errors = Collections.emptyList();
    }

    /**
     * Creates an error carrying structured server-side error details.
     *
     * @param message the human-readable error message
     * @param statusCode the HTTP status code, or 0 if not applicable
     * @param responseBody the raw response body, or null if not available
     * @param errors the structured error details from the server's JSON:API
     *     {@code errors} array; may be null, treated as empty
     */
    public SmplError(String message, int statusCode, String responseBody, List<ApiErrorDetail> errors) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errors = errors != null ? List.copyOf(errors) : Collections.emptyList();
    }

    /**
     * Returns the HTTP status code, or 0 if not applicable (e.g., connection errors).
     *
     * @return the HTTP status code, or 0 when none applies
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the HTTP status code as a nullable Integer, or null if 0.
     *
     * @return the HTTP status code, or null when none applies
     */
    public Integer getStatusCode() {
        return statusCode == 0 ? null : statusCode;
    }

    /**
     * Returns the raw response body, or null if not available.
     *
     * @return the raw response body, or null
     */
    public String responseBody() {
        return responseBody;
    }

    /**
     * Returns the structured error details, or an empty list if none.
     *
     * @return the structured error details from the server's JSON:API
     *     {@code errors} array; never null
     */
    public List<ApiErrorDetail> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        String className = getClass().getSimpleName();
        String msg = getMessage();
        if (errors.isEmpty()) {
            return className + ": " + msg;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(className).append(": ").append(msg).append("\n");
        if (errors.size() == 1) {
            sb.append("Error: ").append(errors.get(0).toJson());
        } else {
            sb.append("Errors:");
            for (int i = 0; i < errors.size(); i++) {
                sb.append("\n  [").append(i).append("] ").append(errors.get(i).toJson());
            }
        }
        return sb.toString();
    }

    /** Minimal JSON string escaping (test helper, mirrors {@link ApiErrorDetail#escapeJson}). */
    static String escapeJson(String s) {
        return ApiErrorDetail.escapeJson(s);
    }
}
