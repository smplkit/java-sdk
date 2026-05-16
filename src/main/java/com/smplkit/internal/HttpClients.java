package com.smplkit.internal;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HttpClient builder helpers.
 *
 * <p>All SDK transports pin {@link HttpClient.Version#HTTP_1_1}. The JDK's
 * default behavior is to attempt HTTP/2 — over HTTPS that uses ALPN
 * (transparent), but over cleartext HTTP it sends {@code Connection: Upgrade}
 * + {@code Upgrade: h2c} + {@code HTTP2-Settings} headers. Some intermediaries
 * (notably Caddy fronting our local platform) mishandle h2c upgrades on
 * requests with bodies, dropping the body during forwarding so the upstream
 * service sees an empty payload and rejects it as a JSON:API validation
 * error. Pinning HTTP/1.1 sidesteps the issue and is fine for our REST
 * workload — connection keep-alive provides the throughput we need.</p>
 */
public final class HttpClients {

    private HttpClients() {}

    /** Builder pinned to HTTP/1.1 with no other configuration. */
    public static HttpClient.Builder builder() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
    }

    /** Convenience: build a HTTP/1.1 client with the given connect timeout. */
    public static HttpClient http11(Duration connectTimeout) {
        return builder().connectTimeout(connectTimeout).build();
    }
}
