package com.smplkit.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwarder destination HTTP request shape — the base configuration.
 */
public final class HttpConfiguration {
    /** HTTP verb used for delivery. Defaults to {@link HttpMethod#POST}. */
    public HttpMethod method = HttpMethod.POST;
    /** Destination URL the audit service POSTs each event to. */
    public String url = "";
    /**
     * Headers attached to every outbound request, as a name&rarr;value map
     * (e.g. {@code {"DD-API-KEY": "s3cr3t"}}). Values often carry credentials
     * and are returned in plaintext on reads, so a get-mutate-put round-trip
     * preserves them without re-entering secrets. Use
     * {@link #setHeader(String, String)} / {@link #getHeader(String)} to read
     * and write individual headers.
     */
    public Map<String, String> headers = new LinkedHashMap<>();
    /**
     * Status the destination must return for delivery to count as success —
     * either an exact code ({@code "200"}, {@code "204"}) or a class
     * ({@code "2xx"}, {@code "4xx"}). Defaults to {@code "2xx"}.
     */
    public String successStatus = "2xx";
    /**
     * Whether to verify the destination's TLS certificate chain. Defaults
     * to {@code true}; flip to {@code false} only for short-lived testing
     * against a destination that serves an untrusted certificate. Prefer
     * pinning the issuing CA via {@link #caCert} for long-lived self-signed
     * setups.
     */
    public boolean tlsVerify = true;
    /**
     * Optional PEM-encoded certificate (or bundle) trusted in addition to
     * the system CA store. Ignored when {@link #tlsVerify} is {@code false}.
     * {@code null} (the default) means "use system CAs only".
     */
    public String caCert = null;

    public HttpConfiguration() {}

    /**
     * @param url destination URL the audit service POSTs each event to
     */
    public HttpConfiguration(String url) {
        this.url = url;
    }

    /**
     * @param method HTTP verb used for delivery
     * @param url destination URL the audit service POSTs each event to
     * @param headers headers attached to every outbound request, as a
     *     name&rarr;value map; values are returned in plaintext on reads, so a
     *     get-mutate-put round-trip preserves them without re-entering secrets
     */
    public HttpConfiguration(HttpMethod method, String url, Map<String, String> headers) {
        this.method = method;
        this.url = url;
        this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
    }

    /**
     * Set (or replace) a single request header by name.
     *
     * @param name  header name (e.g. {@code "DD-API-KEY"})
     * @param value header value
     */
    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    /**
     * The value of header {@code name}, or {@code null} if it is not set.
     *
     * @param name header name to look up
     * @return the header value, or {@code null} when unset
     */
    public String getHeader(String name) {
        return headers.get(name);
    }
}
