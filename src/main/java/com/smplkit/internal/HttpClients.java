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

    /** Lowercase User-Agent header name, for caller-override detection. */
    private static final String USER_AGENT_HEADER = "user-agent";

    /**
     * Builds a request interceptor that sets extra headers (excluding SDK-managed ones),
     * applies the SDK's default {@code User-Agent} unless the caller supplied one via
     * extraHeaders (any casing), then always sets the Authorization header. SDK headers
     * win on collision.
     *
     * <p>The default User-Agent exists because the platform edge rejects UA-less
     * requests, and an SDK-identifying value gives support/telemetry the SDK and
     * version in access logs — the JDK's {@code Java-http-client/<jdk>} default
     * identifies neither.</p>
     */
    public static Consumer<HttpRequest.Builder> compositeInterceptor(String apiKey,
                                                               Map<String, String> extraHeaders) {
        return builder -> {
            boolean callerSetUserAgent = false;
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    String lower = entry.getKey().toLowerCase(Locale.ROOT);
                    if (SDK_MANAGED_HEADERS.contains(lower)) {
                        continue;
                    }
                    builder.header(entry.getKey(), entry.getValue());
                    if (USER_AGENT_HEADER.equals(lower)) {
                        callerSetUserAgent = true;
                    }
                }
            }
            if (!callerSetUserAgent) {
                builder.setHeader("User-Agent", UserAgent.VALUE);
            }
            builder.header("Authorization", "Bearer " + apiKey);
        };
    }

    /** Builds the auth-only interceptor (no extra headers); also applies the default User-Agent. */
    public static Consumer<HttpRequest.Builder> authInterceptor(String apiKey) {
        return builder -> {
            builder.setHeader("User-Agent", UserAgent.VALUE);
            builder.header("Authorization", "Bearer " + apiKey);
        };
    }
}
