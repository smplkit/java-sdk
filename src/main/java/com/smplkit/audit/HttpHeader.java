package com.smplkit.audit;

/**
 * A single name/value HTTP header on a forwarder destination.
 */
public final class HttpHeader {
    /** Header name (e.g. {@code "Authorization"}, {@code "DD-API-KEY"}). */
    public final String name;
    /**
     * Header value. Returned in plaintext on reads, so a get-mutate-put
     * round-trip preserves it without re-entering secrets.
     */
    public final String value;

    /**
     * @param name header name (e.g. {@code "Authorization"}, {@code "DD-API-KEY"})
     * @param value header value; returned in plaintext on reads, so a
     *     get-mutate-put round-trip preserves it without re-entering secrets
     */
    public HttpHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }
}
