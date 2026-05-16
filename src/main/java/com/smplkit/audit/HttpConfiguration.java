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
 *
 * <p>{@code successStatus} is a 3-character string: an exact code
 * (e.g. {@code "200"}) or a status class (e.g. {@code "2xx"}).</p>
 */
public final class HttpConfiguration {
    public String method = "POST";
    public String url = "";
    public List<HttpHeader> headers = new ArrayList<>();
    public String successStatus = "2xx";

    public HttpConfiguration() {}

    public HttpConfiguration(String url) {
        this.url = url;
    }
}
