package com.smplkit.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * Forwarder destination HTTP request shape.
 *
 * <p>{@code successStatus} is a 3-character string: an exact code
 * (e.g. {@code "200"}) or a class (e.g. {@code "2xx"}).</p>
 */
public final class ForwarderHttp {
    public String method = "POST";
    public String url = "";
    public List<HttpHeader> headers = new ArrayList<>();
    public String body; // nullable
    public String successStatus = "2xx";

    public ForwarderHttp() {}

    public ForwarderHttp(String url) {
        this.url = url;
    }
}
