package com.smplkit.audit;

/**
 * A single name/value HTTP header on a forwarder destination.
 *
 * <p>Header values carry credentials and are encrypted at rest server-side;
 * reads return them redacted as the literal string {@code "<redacted>"}.
 * Re-supply real values before saving a forwarder fetched from the server,
 * or those headers will be persisted as the literal redaction marker.</p>
 */
public final class HttpHeader {
    /** Header name (e.g. {@code "Authorization"}, {@code "DD-API-KEY"}). */
    public final String name;
    /**
     * Header value, plaintext on writes. The audit service encrypts values
     * at rest; reads return them as {@code "<redacted>"}.
     */
    public final String value;

    public HttpHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }
}
