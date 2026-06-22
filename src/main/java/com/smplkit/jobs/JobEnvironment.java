package com.smplkit.jobs;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One environment's <strong>sparse override</strong> for a job (ADR-056).
 *
 * <p>A job's {@link Job#environments} map holds one of these per environment.
 * Only the leaves you set are sent on save; everything you leave unset is
 * inherited from the job's base definition, and the server resolves
 * base&nbsp;&oplus;&nbsp;overrides when the job fires. The base definition is
 * disabled everywhere, so a job runs in an environment only when that
 * environment's override sets {@code enabled=true}.</p>
 *
 * <p>Reach an override through {@link Job#environment(String)} and set its
 * fields directly, e.g.
 * {@code job.environment("production").url = "https://prod.example.com/warm"}.</p>
 *
 * <p><strong>Reading a leaf returns this environment's override, or
 * {@code null} when it does not override that leaf</strong> — the SDK does not
 * merge in the base value (jobs resolve server-side). To see a base value,
 * read the job's base definition ({@link Job#configuration}, {@link
 * Job#schedule}, …).</p>
 */
public final class JobEnvironment {

    // Overlay leaf wire names (ADR-056). Headers are addressed individually as
    // ``headers.<name>``; everything else is a single top-level overlay key.
    private static final String LEAF_ENABLED = "enabled";
    private static final String LEAF_SCHEDULE = "schedule";
    private static final String LEAF_TIMEZONE = "timezone";
    private static final String LEAF_RETRY_POLICY = "retry_policy";
    private static final String LEAF_URL = "url";
    private static final String LEAF_METHOD = "method";
    private static final String LEAF_TIMEOUT = "timeout";
    private static final String LEAF_BODY = "body";
    private static final String LEAF_SUCCESS_STATUS = "success_status";
    private static final String LEAF_TLS_VERIFY = "tls_verify";
    private static final String LEAF_CA_CERT = "ca_cert";
    private static final String HEADERS_PREFIX = "headers";
    private static final String LEAF_NEXT_RUN_AT = "next_run_at";

    /** Whether the job runs in this environment. Defaults to {@code false}. */
    public boolean enabled = false;

    /**
     * Per-environment cron schedule override (recurring jobs only). {@code null}
     * (the default) inherits the base {@link Job#schedule}.
     */
    public String schedule = null;

    /**
     * Per-environment IANA timezone override (recurring jobs only). {@code null}
     * (the default) inherits the base {@link Job#timezone} (else UTC).
     */
    public String timezone = null;

    /**
     * Per-environment retry-policy override — the id of a {@link RetryPolicy}
     * (or {@code "Default"}). {@code null} (the default) inherits the base
     * {@link Job#retryPolicy}. Assign the id directly, or use
     * {@link #setRetryPolicy(RetryPolicy)} to reference a policy instance.
     */
    public String retryPolicy = null;

    /**
     * Per-environment destination URL override. {@code null} (the default)
     * inherits the base {@link Job#configuration} URL.
     */
    public String url = null;

    /**
     * Per-environment HTTP method override. {@code null} (the default) inherits
     * the base {@link Job#configuration} method.
     */
    public HttpMethod method = null;

    /**
     * Per-environment timeout override, in seconds. {@code null} (the default)
     * inherits the base {@link Job#configuration} timeout.
     */
    public Integer timeout = null;

    /**
     * Per-environment request-body override. {@code null} (the default)
     * inherits the base {@link Job#configuration} body.
     */
    public String body = null;

    /**
     * Per-environment success-status override. {@code null} (the default)
     * inherits the base {@link Job#configuration} success status.
     */
    public String successStatus = null;

    /**
     * Per-environment TLS-verification override. {@code null} (the default)
     * inherits the base {@link Job#configuration} setting.
     */
    public Boolean tlsVerify = null;

    /**
     * Per-environment CA-certificate override (PEM). {@code null} (the default)
     * inherits the base {@link Job#configuration} certificate.
     */
    public String caCert = null;

    /**
     * Per-environment header overrides, as a name&rarr;value map. Each entry
     * overrides (or adds) that one header by name on top of the base headers,
     * leaving the rest inherited. Use {@link #setHeader(String, String)} /
     * {@link #getHeader(String)}.
     */
    public Map<String, String> headers = new LinkedHashMap<>();

    /**
     * Read-only: the next scheduled fire time in this environment (UTC).
     * {@code null} when the environment is not enabled, once a one-off run has
     * fired, or for an unsaved instance. Server-derived — never sent on save.
     */
    public OffsetDateTime nextRunAt = null;

    public JobEnvironment() {}

    /**
     * @param enabled whether the job runs in this environment
     */
    public JobEnvironment(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Override (or add) a single header by name in this environment.
     *
     * @param name  header name (e.g. {@code "Authorization"})
     * @param value header value
     */
    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    /**
     * This environment's override for header {@code name}, or {@code null} when
     * it does not override that header.
     *
     * @param name header name to look up
     * @return the override value, or {@code null} when unset
     */
    public String getHeader(String name) {
        return headers.get(name);
    }

    /**
     * Set this environment's retry-policy override from a {@link RetryPolicy}
     * instance (its {@link RetryPolicy#id} is used).
     *
     * @param policy the retry policy whose id to reference
     */
    public void setRetryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy.id;
    }

    /**
     * Emit the flat sparse leaf-path overlay (ADR-056) — {@code enabled} plus
     * only the leaves this environment overrides, with each header as a
     * {@code headers.<name>} leaf. The read-only {@code next_run_at} is never
     * written.
     */
    Map<String, Object> toOverlay() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(LEAF_ENABLED, enabled);
        if (schedule != null) payload.put(LEAF_SCHEDULE, schedule);
        if (timezone != null) payload.put(LEAF_TIMEZONE, timezone);
        if (retryPolicy != null) payload.put(LEAF_RETRY_POLICY, retryPolicy);
        if (url != null) payload.put(LEAF_URL, url);
        if (method != null) payload.put(LEAF_METHOD, method.getValue());
        if (timeout != null) payload.put(LEAF_TIMEOUT, timeout);
        if (body != null) payload.put(LEAF_BODY, body);
        if (successStatus != null) payload.put(LEAF_SUCCESS_STATUS, successStatus);
        if (tlsVerify != null) payload.put(LEAF_TLS_VERIFY, tlsVerify);
        if (caCert != null) payload.put(LEAF_CA_CERT, caCert);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            payload.put(HEADERS_PREFIX + "." + h.getKey(), h.getValue());
        }
        return payload;
    }

    /**
     * Parse the flat leaf-path overlay the server returns (ADR-056). Header
     * leaves arrive as {@code headers.<name>} (split on the first dot, so a
     * dotted header name like {@code X-Foo.Bar} is preserved); every other leaf
     * is a single top-level key. The read-only {@code next_run_at} is captured
     * separately; unknown leaves are ignored for forward compatibility.
     */
    static JobEnvironment fromOverlay(Map<String, Object> raw) {
        JobEnvironment env = new JobEnvironment();
        if (raw == null) {
            return env;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (LEAF_NEXT_RUN_AT.equals(key)) {
                env.nextRunAt = parseDateTime(value);
                continue;
            }
            int dot = key.indexOf('.');
            if (dot >= 0) {
                if (key.substring(0, dot).equals(HEADERS_PREFIX) && dot + 1 < key.length()) {
                    env.headers.put(key.substring(dot + 1), asString(value));
                }
                continue;
            }
            switch (key) {
                case LEAF_ENABLED -> env.enabled = Boolean.TRUE.equals(value);
                case LEAF_SCHEDULE -> env.schedule = asString(value);
                case LEAF_TIMEZONE -> env.timezone = asString(value);
                case LEAF_RETRY_POLICY -> env.retryPolicy = asString(value);
                case LEAF_URL -> env.url = asString(value);
                case LEAF_METHOD -> env.method = value == null ? null : HttpMethod.fromValue(asString(value));
                case LEAF_TIMEOUT -> env.timeout = value instanceof Number n ? n.intValue() : null;
                case LEAF_BODY -> env.body = asString(value);
                case LEAF_SUCCESS_STATUS -> env.successStatus = asString(value);
                case LEAF_TLS_VERIFY -> env.tlsVerify = value instanceof Boolean b ? b : null;
                case LEAF_CA_CERT -> env.caCert = asString(value);
                default -> { /* unknown leaf — ignore for forward compatibility */ }
            }
        }
        return env;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static OffsetDateTime parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return null;
        }
        if (text.endsWith("Z")) {
            text = text.substring(0, text.length() - 1) + "+00:00";
        }
        return OffsetDateTime.parse(text);
    }
}
