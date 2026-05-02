package com.smplkit.errors;

import java.util.Objects;

/**
 * Structured server-side error detail (one entry from a JSON:API errors array).
 *
 * <p>Immutable value type, mirroring the Python SDK's {@code ApiErrorDetail}.</p>
 */
public final class ApiErrorDetail {

    private final String status;
    private final String title;
    private final String detail;
    private final Source source;

    public ApiErrorDetail(String status, String title, String detail, Source source) {
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.source = source;
    }

    /** HTTP status code as a string (e.g. "400"), or null. */
    public String status() { return status; }
    /** Short human-readable summary, or null. */
    public String title() { return title; }
    /** Detailed human-readable explanation, or null. */
    public String detail() { return detail; }
    /** Source location of the error (e.g. JSON pointer), or null. */
    public Source source() { return source; }

    // JavaBean-style aliases for code that prefers getX() over x().
    public String getStatus() { return status; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public Source getSource() { return source; }

    /** Compact JSON representation. */
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiErrorDetail other)) return false;
        return Objects.equals(status, other.status)
                && Objects.equals(title, other.title)
                && Objects.equals(detail, other.detail)
                && Objects.equals(source, other.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, title, detail, source);
    }

    /** Source location of an error (e.g. a JSON pointer to the offending field). */
    public static final class Source {
        private final String pointer;

        public Source(String pointer) {
            this.pointer = pointer;
        }

        public String pointer() { return pointer; }
        /** JavaBean-style alias for {@link #pointer()}. */
        public String getPointer() { return pointer; }

        public String toJson() {
            if (pointer != null) {
                return "{\"pointer\": \"" + escapeJson(pointer) + "\"}";
            }
            return "{}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Source other)) return false;
            return Objects.equals(pointer, other.pointer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pointer);
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
