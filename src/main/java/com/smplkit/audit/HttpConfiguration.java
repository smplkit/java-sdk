package com.smplkit.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * Forwarder destination HTTP request shape.
 */
public final class HttpConfiguration {
    /** HTTP verb used for delivery. Defaults to {@link HttpMethod#POST}. */
    public HttpMethod method = HttpMethod.POST;
    /** Destination URL the audit service POSTs each event to. */
    public String url = "";
    /**
     * Headers attached to every outbound request. Values often carry
     * credentials and are returned in plaintext on reads, so a
     * get-mutate-put round-trip preserves them without re-entering secrets.
     */
    public List<HttpHeader> headers = new ArrayList<>();
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
     * @param headers headers attached to every outbound request; values are
     *     returned in plaintext on reads, so a get-mutate-put round-trip
     *     preserves them without re-entering secrets
     */
    public HttpConfiguration(HttpMethod method, String url, List<HttpHeader> headers) {
        this.method = method;
        this.url = url;
        this.headers = new ArrayList<>(headers);
    }
}
