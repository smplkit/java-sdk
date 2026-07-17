package com.smplkit.flags;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for a standalone {@link FlagsClient}.
 *
 * <p>Resolves credentials / base-domain from {@code ~/.smplkit} / env vars /
 * builder args, then builds and OWNS the
 * flags + app transports the resulting client uses. {@code environment} scopes
 * runtime flag-value resolution and discovery declarations; it is optional for a
 * standalone client (omit it and the client serves flag-level defaults).</p>
 *
 * <pre>{@code
 * try (FlagsClient flags = FlagsClient.builder()
 *         .apiKey("sk_api_...")
 *         .environment("production")
 *         .build()) {
 *     ...
 * }
 * }</pre>
 */
public final class FlagsClientBuilder {

    private String profile;
    private String apiKey;
    private String baseUrl;
    private String baseDomain;
    private String scheme;
    private String environment;
    private String service;
    private Duration timeout = Duration.ofSeconds(30);
    private Boolean debug;
    private boolean streaming = true;
    private final Map<String, String> extraHeaders = new LinkedHashMap<>();

    FlagsClientBuilder() {}

    /**
     * Named {@code ~/.smplkit} profile section to resolve credentials from.
     *
     * @param profile the profile name
     * @return this builder
     */
    public FlagsClientBuilder profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        return this;
    }

    /**
     * API key. When omitted, resolved from {@code SMPLKIT_API_KEY} or {@code ~/.smplkit}.
     *
     * @param apiKey the API key
     * @return this builder
     */
    public FlagsClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Full flags-service base URL. Usually resolved from {@code baseDomain}/{@code scheme}.
     *
     * @param baseUrl the fully-qualified flags-service base URL
     * @return this builder
     */
    public FlagsClientBuilder baseUrl(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        return this;
    }

    /**
     * Base domain for API requests (default {@code "smplkit.com"}).
     *
     * @param baseDomain the base domain
     * @return this builder
     */
    public FlagsClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    /**
     * URL scheme (default {@code "https"}).
     *
     * @param scheme the URL scheme
     * @return this builder
     */
    public FlagsClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    /**
     * Deployment environment used to resolve runtime flag values and scope
     * discovery. When omitted, it is resolved from {@code SMPLKIT_ENVIRONMENT}
     * or {@code ~/.smplkit}.
     *
     * @param environment the deployment environment name
     * @return this builder
     */
    public FlagsClientBuilder environment(String environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        return this;
    }

    /**
     * Name of the service this client runs in. Used to attribute discovered
     * flag declarations and auto-injected as the {@code service} evaluation
     * context. When omitted, it is resolved from {@code SMPLKIT_SERVICE} or
     * {@code ~/.smplkit}.
     *
     * @param service the service name
     * @return this builder
     */
    public FlagsClientBuilder service(String service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        return this;
    }

    /**
     * Per-request read timeout (default 30 seconds).
     *
     * @param timeout the request timeout
     * @return this builder
     */
    public FlagsClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    /**
     * Enable SDK debug logging.
     *
     * @param debug {@code true} to enable debug logging
     * @return this builder
     */
    public FlagsClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /**
     * Stream live flag updates (default {@code true}): the first live call
     * opens a WebSocket for real-time change events and starts periodic
     * background flushes of discovered declarations.
     *
     * <p>Pass {@code false} for the stateless live surface: the first live
     * call still fetches all flag definitions once (blocking), but no
     * WebSocket, timers, or background threads are created; discovery
     * flushes run inline and {@code refresh()} re-fetches on demand — the
     * right shape for serverless and edge runtimes, whose short-lived
     * instances cannot hold open connections.</p>
     *
     * @param streaming whether to stream live flag updates
     * @return this builder
     */
    public FlagsClientBuilder streaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    /**
     * Extra headers attached to every request (SDK-managed headers win on collision).
     *
     * @param extraHeaders the headers to attach to every request
     * @return this builder
     */
    public FlagsClientBuilder extraHeaders(Map<String, String> extraHeaders) {
        Objects.requireNonNull(extraHeaders, "extraHeaders must not be null");
        this.extraHeaders.putAll(extraHeaders);
        return this;
    }

    /**
     * Add a single extra header attached to every request.
     *
     * @param name  the header name
     * @param value the header value
     * @return this builder
     */
    public FlagsClientBuilder header(String name, String value) {
        this.extraHeaders.put(
                Objects.requireNonNull(name, "header name must not be null"),
                Objects.requireNonNull(value, "header value must not be null"));
        return this;
    }

    public FlagsClient build() {
        ResolvedClientConfig cfg = ConfigResolver.resolveClient(
                profile, apiKey, baseDomain, scheme, environment, service, debug);
        return FlagsClient.fromResolved(cfg, baseUrl, timeout,
                new LinkedHashMap<>(extraHeaders), streaming);
    }
}
