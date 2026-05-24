package com.smplkit.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP destination configuration for a {@link Forwarder} whose
 * {@code forwarderType} is one of the HTTP-family transports
 * (HTTP, DATADOG, SPLUNK_HEC, SUMO_LOGIC, NEW_RELIC, HONEYCOMB,
 * ELASTIC). Carried as {@code Forwarder.configuration}; non-HTTP
 * transports will land their own configuration shapes alongside
 * this one as the discriminated union grows.
 */
public final class HttpConfiguration {
    /** HTTP verb used for delivery. Defaults to {@link HttpMethod#POST}. */
    public HttpMethod method = HttpMethod.POST;
    /** Destination URL the audit service POSTs each event to. */
    public String url = "";
    /**
     * Headers attached to every outbound request. Values carry credentials
     * and are encrypted at rest server-side; reads return them redacted.
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

    public HttpConfiguration(String url) {
        this.url = url;
    }

    public HttpConfiguration(HttpMethod method, String url, List<HttpHeader> headers) {
        this.method = method;
        this.url = url;
        this.headers = new ArrayList<>(headers);
    }
}
