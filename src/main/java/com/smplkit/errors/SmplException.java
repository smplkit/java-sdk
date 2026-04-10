package com.smplkit.errors;

import java.util.Collections;
import java.util.List;

/**
 * Base unchecked exception for all smplkit SDK errors.
 *
 * <p>All SDK exceptions extend this class, making it easy to catch any
 * SDK error with a single {@code catch (SmplException e)} block.</p>
 *
 * <p>When the server returns structured error details, they are available
 * via {@link #getErrors()} and included in {@link #toString()} output.</p>
 */
public class SmplException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final List<ApiError> errors;

    /**
     * A single error entry from a server error response.
     */
    public static final class ApiError {
        private final String status;
        private final String title;
        private final String detail;
        private final ApiErrorSource source;

        public ApiError(String status, String title, String detail, ApiErrorSource source) {
            this.status = status;
            this.title = title;
            this.detail = detail;
            this.source = source;
        }

        /** Returns the HTTP status code string (e.g. "400"), or null. */
        public String getStatus() { return status; }

        /** Returns the short human-readable summary, or null. */
        public String getTitle() { return title; }

        /** Returns the detailed human-readable explanation, or null. */
        public String getDetail() { return detail; }

        /** Returns the source location of the error, or null. */
        public ApiErrorSource getSource() { return source; }

        /** Returns a compact JSON representation. */
        public String toJson() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            if (status != null) {
                sb.append("\"status\": \"").append(escapeJson(status)).append("\"");
                first = false;
            }
            if (title != null) {
                if (!first) sb.append(", ");
                sb.append("\"title\": \"").append(escapeJson(title)).append("\"");
                first = false;
            }
            if (detail != null) {
                if (!first) sb.append(", ");
                sb.append("\"detail\": \"").append(escapeJson(detail)).append("\"");
                first = false;
            }
            if (source != null) {
                if (!first) sb.append(", ");
                sb.append("\"source\": ").append(source.toJson());
            }
            sb.append("}");
            return sb.toString();
        }
    }

    /**
     * Source location of an error (e.g. a JSON pointer to the offending field).
     */
    public static final class ApiErrorSource {
        private final String pointer;

        public ApiErrorSource(String pointer) {
            this.pointer = pointer;
        }

        /** Returns the JSON pointer to the field that caused the error, or null. */
        public String getPointer() { return pointer; }

        /** Returns a compact JSON representation. */
        public String toJson() {
            if (pointer != null) {
                return "{\"pointer\": \"" + escapeJson(pointer) + "\"}";
            }
            return "{}";
        }
    }

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
        this.errors = Collections.emptyList();
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
        this.errors = Collections.emptyList();
    }

    /**
     * Creates a new SmplException with structured error details.
     *
     * @param message      human-readable error description
     * @param statusCode   HTTP status code (0 if not applicable)
     * @param responseBody raw response body (may be null)
     * @param errors       parsed error details (must not be null)
     */
    public SmplException(String message, int statusCode, String responseBody, List<ApiError> errors) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errors = errors != null ? List.copyOf(errors) : Collections.emptyList();
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
     * Returns the HTTP status code as a nullable Integer, or null if not applicable
     * (status code is 0).
     *
     * @return the HTTP status code, or null
     */
    public Integer getStatusCode() {
        return statusCode == 0 ? null : statusCode;
    }

    /**
     * Returns the raw response body, or null if not available.
     *
     * @return the response body
     */
    public String responseBody() {
        return responseBody;
    }

    /**
     * Returns the structured error details, or an empty list if none are available.
     *
     * @return unmodifiable list of error details
     */
    public List<ApiError> getErrors() {
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

    /**
     * Derives a human-readable message from a list of error details.
     *
     * @param errors the parsed errors
     * @return a human-readable message
     */
    static String deriveMessage(List<ApiError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "An API error occurred";
        }
        ApiError first = errors.get(0);
        String msg;
        if (first.detail != null && !first.detail.isEmpty()) {
            msg = first.detail;
        } else if (first.title != null && !first.title.isEmpty()) {
            msg = first.title;
        } else if (first.status != null && !first.status.isEmpty()) {
            msg = "HTTP " + first.status;
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

    /** Minimal JSON string escaping (quotes and backslashes). */
    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
