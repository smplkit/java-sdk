package com.smplkit.jobs;

import java.util.ArrayList;
import java.util.List;

/**
 * The HTTP request a job performs when it fires (the {@code http}
 * configuration).
 */
public final class HttpConfig {
    /** HTTP verb used when the job fires. Defaults to {@link HttpMethod#POST}. */
    public HttpMethod method = HttpMethod.POST;
    /** Destination URL the job requests on each run. */
    public String url = "";
    /**
     * Headers attached to every request, as name/value pairs. Header values
     * are returned in plaintext on reads, so a get-mutate-put round-trip
     * preserves them.
     */
    public List<HttpHeader> headers = new ArrayList<>();
    /**
     * Request body sent on each run. {@code null} (the default) sends an empty
     * body, suitable for a connectivity ping. Sent verbatim — pair with a
     * matching {@code Content-Type} header.
     */
    public String body = null;
    /**
     * Status the destination must return for the run to count as success —
     * either an exact code ({@code "200"}, {@code "204"}) or a status class
     * ({@code "2xx"}, {@code "4xx"}). Defaults to {@code "2xx"}.
     */
    public String successStatus = "2xx";
    /**
     * Per-run timeout in seconds. A run that does not complete within this
     * many seconds fails with reason {@code TIMEOUT}. Defaults to 30; bounded
     * by your plan's maximum timeout.
     */
    public int timeout = 30;
    /**
     * Whether to verify the destination's TLS certificate chain. Defaults to
     * {@code true}; flip to {@code false} only for short-lived testing against
     * an untrusted certificate. Prefer pinning the CA via {@link #caCert}.
     */
    public boolean tlsVerify = true;
    /**
     * Optional PEM-encoded certificate (or bundle) trusted in addition to the
     * system CA store. Ignored when {@link #tlsVerify} is {@code false}.
     * {@code null} (the default) means "use system CAs only".
     */
    public String caCert = null;

    /** Create an empty configuration; set the public fields before use. */
    public HttpConfig() {}

    /**
     * Create a configuration targeting the given URL, leaving every other
     * field at its default.
     *
     * @param url destination URL the job sends its request to
     */
    public HttpConfig(String url) {
        this.url = url;
    }

    /**
     * Create a configuration with an explicit method, URL, and headers.
     *
     * @param method  HTTP verb used for the request
     * @param url     destination URL the job sends its request to
     * @param headers headers attached to the request, as name/value pairs
     */
    public HttpConfig(HttpMethod method, String url, List<HttpHeader> headers) {
        this.method = method;
        this.url = url;
        this.headers = new ArrayList<>(headers);
    }
}
