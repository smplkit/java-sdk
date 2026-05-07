package com.smplkit.audit;

/** A single name/value HTTP header on a forwarder destination. */
public final class HttpHeader {
    public final String name;
    public final String value;

    public HttpHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }
}
