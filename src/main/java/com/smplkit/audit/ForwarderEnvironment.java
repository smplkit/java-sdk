package com.smplkit.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One environment's <strong>sparse override</strong> for a forwarder (ADR-056).
 *
 * <p>A forwarder's {@link Forwarder#environments} map holds one of these per
 * environment. Only the leaves you set are sent on save; everything you leave
 * unset is inherited from the forwarder's base definition, and the server
 * resolves base&nbsp;&oplus;&nbsp;overrides when an event is delivered. The base
 * definition delivers nowhere, so a forwarder delivers in an environment only
 * when that environment's override sets {@code enabled=true}.</p>
 *
 * <p>Reach an override through {@link Forwarder#environment(String)} and set its
 * fields directly, e.g.
 * {@code forwarder.environment("production").url = "https://prod.siem.example.com/in"}.</p>
 *
 * <p><strong>Reading a leaf returns this environment's override, or
 * {@code null} when it does not override that leaf</strong> — the SDK does not
 * merge in the base value (forwarders resolve server-side). To see a base
 * value, read the forwarder's base definition
 * ({@link Forwarder#configuration}).</p>
 */
public final class ForwarderEnvironment {

    // Overlay leaf wire names (ADR-056). Headers are addressed individually as
    // ``headers.<name>``; everything else is a single top-level overlay key.
    private static final String LEAF_ENABLED = "enabled";
    private static final String LEAF_URL = "url";
    private static final String LEAF_METHOD = "method";
    private static final String LEAF_SUCCESS_STATUS = "success_status";
    private static final String LEAF_TLS_VERIFY = "tls_verify";
    private static final String LEAF_CA_CERT = "ca_cert";
    private static final String HEADERS_PREFIX = "headers";

    /**
     * Whether the forwarder delivers events in this environment. Defaults to
     * {@code false}.
     */
    public boolean enabled = false;

    /**
     * Per-environment destination URL override. {@code null} (the default)
     * inherits the base {@link Forwarder#configuration} URL.
     */
    public String url = null;

    /**
     * Per-environment HTTP method override. {@code null} (the default) inherits
     * the base {@link Forwarder#configuration} method.
     */
    public HttpMethod method = null;

    /**
     * Per-environment success-status override. {@code null} (the default)
     * inherits the base {@link Forwarder#configuration} success status.
     */
    public String successStatus = null;

    /**
     * Per-environment TLS-verification override. {@code null} (the default)
     * inherits the base {@link Forwarder#configuration} setting.
     */
    public Boolean tlsVerify = null;

    /**
     * Per-environment CA-certificate override (PEM). {@code null} (the default)
     * inherits the base {@link Forwarder#configuration} certificate.
     */
    public String caCert = null;

    /**
     * Per-environment header overrides, as a name&rarr;value map. Each entry
     * overrides (or adds) that one header by name on top of the base headers,
     * leaving the rest inherited. Use {@link #setHeader(String, String)} /
     * {@link #getHeader(String)}.
     */
    public Map<String, String> headers = new LinkedHashMap<>();

    public ForwarderEnvironment() {}

    /**
     * @param enabled whether the forwarder delivers events in this environment
     */
    public ForwarderEnvironment(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Override (or add) a single header by name in this environment.
     *
     * @param name  header name (e.g. {@code "DD-API-KEY"})
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
     * Emit the flat sparse leaf-path overlay (ADR-056) — {@code enabled} plus
     * only the leaves this environment overrides, with each header as a
     * {@code headers.<name>} leaf.
     */
    Map<String, Object> toOverlay() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(LEAF_ENABLED, enabled);
        if (url != null) payload.put(LEAF_URL, url);
        if (method != null) payload.put(LEAF_METHOD, method.getValue());
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
     * is a single top-level key. Unknown leaves are ignored for forward
     * compatibility.
     */
    static ForwarderEnvironment fromOverlay(Map<String, Object> raw) {
        ForwarderEnvironment env = new ForwarderEnvironment();
        if (raw == null) {
            return env;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            int dot = key.indexOf('.');
            if (dot >= 0) {
                if (key.substring(0, dot).equals(HEADERS_PREFIX) && dot + 1 < key.length()) {
                    env.headers.put(key.substring(dot + 1), asString(value));
                }
                continue;
            }
            switch (key) {
                case LEAF_ENABLED -> env.enabled = Boolean.TRUE.equals(value);
                case LEAF_URL -> env.url = asString(value);
                case LEAF_METHOD -> env.method = value == null ? null : HttpMethod.fromValue(asString(value));
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
}
