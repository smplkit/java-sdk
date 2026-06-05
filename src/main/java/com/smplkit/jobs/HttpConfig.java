package com.smplkit.jobs;

import java.util.ArrayList;
import java.util.List;

/**
 * The HTTP request a job performs when it fires (the {@code http}
 * configuration).
 *
 * <p>Extends the shared forwarder shape with the two fields a scheduled job
 * needs beyond a forwarder: a request {@link #body} and a per-run
 * {@link #timeout}.</p>
 */
public final class HttpConfig {
    /** HTTP verb used when the job fires. Defaults to {@link HttpMethod#POST}. */
    public HttpMethod method = HttpMethod.POST;
    /** Destination URL the job requests on each run. */
    public String url = "";
    /** Headers attached to every request. Values are redacted on reads. */
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

    public HttpConfig() {}

    public HttpConfig(String url) {
        this.url = url;
    }

    public HttpConfig(HttpMethod method, String url, List<HttpHeader> headers) {
        this.method = method;
        this.url = url;
        this.headers = new ArrayList<>(headers);
    }
}
