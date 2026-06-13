package com.smplkit.errors;

import java.util.Map;
import java.util.Objects;

/**
 * Structured server-side error detail (one entry from a JSON:API errors array).
 *
 * <p>Immutable value type, mirroring the Python SDK's {@code ApiErrorDetail}.</p>
 */
public final class ApiErrorDetail {

    private final String status;
    private final String code;
    private final String title;
    private final String detail;
    private final Source source;
    private final Map<String, Object> meta;

    /**
     * Full constructor (status, code, title, detail, source, meta).
     *
     * @param status the HTTP status code as a string (e.g. "400"), or null
     * @param code the application-specific machine-readable error code, or null
     * @param title the short human-readable summary, or null
     * @param detail the detailed human-readable explanation, or null
     * @param source the source location of the error, or null
     * @param meta additional structured context, or null
     */
    public ApiErrorDetail(String status, String code, String title, String detail,
                          Source source, Map<String, Object> meta) {
        this.status = status;
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.source = source;
        this.meta = meta;
    }

    /**
     * Backwards-compatible constructor without {@code code} / {@code meta} — kept
     * so callers built against the older shape (and existing tests) still compile.
     *
     * @param status the HTTP status code as a string (e.g. "400"), or null
     * @param title the short human-readable summary, or null
     * @param detail the detailed human-readable explanation, or null
     * @param source the source location of the error, or null
     */
    public ApiErrorDetail(String status, String title, String detail, Source source) {
        this(status, null, title, detail, source, null);
    }

    /**
     * Returns the HTTP status code as a string (e.g. "400"), or null.
     *
     * @return the HTTP status code as a string, or null
     */
    public String status() { return status; }
    /**
     * Application-specific machine-readable error code (e.g.
     * {@code environment_unmanaged}), or null. Per JSON:API §7, smplkit
     * sets this on every error so callers can branch without string-matching.
     *
     * @return the machine-readable error code, or null
     */
    public String code() { return code; }
    /**
     * Returns the short human-readable summary, or null.
     *
     * @return the title, or null
     */
    public String title() { return title; }
    /**
     * Returns the detailed human-readable explanation, or null.
     *
     * @return the detail, or null
     */
    public String detail() { return detail; }
    /**
     * Returns the source location of the error (e.g. JSON pointer), or null.
     *
     * @return the source location, or null
     */
    public Source source() { return source; }
    /**
     * Returns additional structured context (e.g. {@code {"environment": "staging"}}), or null.
     *
     * @return the meta map, or null
     */
    public Map<String, Object> meta() { return meta; }

    // JavaBean-style aliases for code that prefers getX() over x().
    /**
     * JavaBean-style alias for {@link #status()}.
     *
     * @return the HTTP status code as a string, or null
     */
    public String getStatus() { return status; }
    /**
     * JavaBean-style alias for {@link #code()}.
     *
     * @return the machine-readable error code, or null
     */
    public String getCode() { return code; }
    /**
     * JavaBean-style alias for {@link #title()}.
     *
     * @return the title, or null
     */
    public String getTitle() { return title; }
    /**
     * JavaBean-style alias for {@link #detail()}.
     *
     * @return the detail, or null
     */
    public String getDetail() { return detail; }
    /**
     * JavaBean-style alias for {@link #source()}.
     *
     * @return the source location, or null
     */
    public Source getSource() { return source; }
    /**
     * JavaBean-style alias for {@link #meta()}.
     *
     * @return the meta map, or null
     */
    public Map<String, Object> getMeta() { return meta; }

    /**
     * Returns a compact JSON representation of this error detail.
     *
     * @return a JSON object string containing the non-null fields
     */
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
                && Objects.equals(code, other.code)
                && Objects.equals(title, other.title)
                && Objects.equals(detail, other.detail)
                && Objects.equals(source, other.source)
                && Objects.equals(meta, other.meta);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, code, title, detail, source, meta);
    }

    /** Source location of an error (e.g. a JSON pointer to the offending field). */
    public static final class Source {
        private final String pointer;

        /**
         * Creates a source location.
         *
         * @param pointer a JSON pointer to the offending field
         *     (e.g. {@code "/data/attributes/name"}), or null
         */
        public Source(String pointer) {
            this.pointer = pointer;
        }

        /**
         * Returns the JSON pointer to the offending field, or null.
         *
         * @return the JSON pointer, or null
         */
        public String pointer() { return pointer; }
        /**
         * JavaBean-style alias for {@link #pointer()}.
         *
         * @return the JSON pointer, or null
         */
        public String getPointer() { return pointer; }

        /**
         * Returns a compact JSON representation of this source location.
         *
         * @return a JSON object string with the pointer, or {@code "{}"} if none
         */
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
