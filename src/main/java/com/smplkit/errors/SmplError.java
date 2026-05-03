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

    public SmplError(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errors = Collections.emptyList();
    }

    public SmplError(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errors = Collections.emptyList();
    }

    public SmplError(String message, int statusCode, String responseBody, List<ApiErrorDetail> errors) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errors = errors != null ? List.copyOf(errors) : Collections.emptyList();
    }

    /** Returns the HTTP status code, or 0 if not applicable (e.g., connection errors). */
    public int statusCode() {
        return statusCode;
    }

    /** Returns the HTTP status code as a nullable Integer, or null if 0. */
    public Integer getStatusCode() {
        return statusCode == 0 ? null : statusCode;
    }

    /** Returns the raw response body, or null if not available. */
    public String responseBody() {
        return responseBody;
    }

    /** Returns the structured error details, or an empty list if none. */
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

    /** Derives a human-readable message from a list of error details. */
    static String deriveMessage(List<ApiErrorDetail> errors) {
        if (errors == null || errors.isEmpty()) {
            return "An API error occurred";
        }
        ApiErrorDetail first = errors.get(0);
        String msg;
        if (first.detail() != null && !first.detail().isEmpty()) {
            msg = first.detail();
        } else if (first.title() != null && !first.title().isEmpty()) {
            msg = first.title();
        } else if (first.status() != null && !first.status().isEmpty()) {
            msg = "HTTP " + first.status();
        } else {
            msg = "An API error occurred";
        }
        int extra = errors.size() - 1;
        if (extra == 1) {
            msg += " (and 1 more error)";
        } else if (extra > 1) {
            msg += " (and " + extra + " more errors)";
        }
        return msg;
    }
}
