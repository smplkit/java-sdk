package com.smplkit.jobs;

/**
 * A single name/value HTTP header attached to the request a job performs.
 */
public final class HttpHeader {
    /** Header name (e.g. {@code "Authorization"}, {@code "Content-Type"}). */
    public final String name;
    /**
     * Header value. Returned in plaintext on reads, so a get-mutate-put
     * round-trip of a fetched job preserves it without re-entering the value.
     */
    public final String value;

    public HttpHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }
}
