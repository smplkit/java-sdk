package com.smplkit.internal;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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

    /** Headers the SDK always sets; users cannot override these via extraHeaders. */
    private static final Set<String> SDK_MANAGED_HEADERS =
            Set.of("authorization", "accept", "content-type");

    /**
     * Builds a request interceptor that sets extra headers (excluding SDK-managed ones),
     * then always sets the Authorization header. SDK headers win on collision.
     */
    public static Consumer<HttpRequest.Builder> compositeInterceptor(String apiKey,
                                                               Map<String, String> extraHeaders) {
        return builder -> {
            if (extraHeaders != null) {
                extraHeaders.forEach((k, v) -> {
                    if (!SDK_MANAGED_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                        builder.header(k, v);
                    }
                });
            }
            builder.header("Authorization", "Bearer " + apiKey);
        };
    }

    /** Builds the auth-only interceptor (no extra headers). */
    public static Consumer<HttpRequest.Builder> authInterceptor(String apiKey) {
        return builder -> builder.header("Authorization", "Bearer " + apiKey);
    }
}
