package com.smplkit.jobs;

/**
 * A single name/value HTTP header attached to the request a job performs.
 *
 * <p>Header values carry credentials and are encrypted at rest server-side;
 * reads return them redacted. Re-supply real values before saving a job
 * fetched from the server, or those headers will be persisted as the
 * redaction marker.</p>
 */
public final class HttpHeader {
    /** Header name (e.g. {@code "Authorization"}, {@code "Content-Type"}). */
    public final String name;
    /**
     * Header value, plaintext on writes. The jobs service encrypts values
     * at rest; reads return them redacted.
     */
    public final String value;

    public HttpHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }
}
