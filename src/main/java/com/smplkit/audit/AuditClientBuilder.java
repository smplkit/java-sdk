package com.smplkit.audit;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for a standalone {@link AuditClient}.
 *
 * <p>Resolution order matches {@link com.smplkit.SmplClientBuilder} but skips
 * service/telemetry: Audit resolves credentials/base-domain from
 * {@code ~/.smplkit} / env vars / builder args. An {@code environment} is
 * supported but optional — when present it is stamped as
 * {@code X-Smplkit-Environment} so event recording and reads scope to it
 * server-side; when absent the client still works (forwarder CRUD and
 * discovery are environment-agnostic, and reads accept an explicit
 * {@code environments} filter).</p>
 */
public final class AuditClientBuilder {

    private String profile;
    private String apiKey;
    private String environment;
    private String baseDomain;
    private String scheme;
    private Duration timeout = Duration.ofSeconds(30);
    private Boolean debug;
    private final Map<String, String> extraHeaders = new LinkedHashMap<>();

    AuditClientBuilder() {}

    /**
     * Named {@code ~/.smplkit} profile section to resolve credentials from.
     *
     * @param profile the profile section name
     * @return this builder
     */
    public AuditClientBuilder profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        return this;
    }

    /**
     * API key. When omitted, it is resolved from {@code SMPLKIT_API_KEY} or
     * {@code ~/.smplkit}.
     *
     * @param apiKey the API key
     * @return this builder
     */
    public AuditClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Deployment environment to scope recording and reads to, sent as
     * {@code X-Smplkit-Environment}. Optional — forwarder CRUD and discovery
     * are environment-agnostic, and reads accept an explicit
     * {@code environments} filter.
     *
     * @param environment the deployment environment key
     * @return this builder
     */
    public AuditClientBuilder environment(String environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        return this;
    }

    /**
     * Base domain for API requests (default {@code "smplkit.com"}).
     *
     * @param baseDomain the base domain
     * @return this builder
     */
    public AuditClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    /**
     * URL scheme (default {@code "https"}).
     *
     * @param scheme the URL scheme
     * @return this builder
     */
    public AuditClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    /**
     * Per-request read timeout (default 30 seconds).
     *
     * @param timeout the read timeout
     * @return this builder
     */
    public AuditClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    /**
     * Enable SDK debug logging.
     *
     * @param debug whether to enable debug logging
     * @return this builder
     */
    public AuditClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /** Extra headers attached to every request (SDK-managed headers win on collision). */
    public AuditClientBuilder extraHeaders(Map<String, String> extraHeaders) {
        Objects.requireNonNull(extraHeaders, "extraHeaders must not be null");
        this.extraHeaders.putAll(extraHeaders);
        return this;
    }

    /** Add a single extra header attached to every request. */
    public AuditClientBuilder header(String name, String value) {
        this.extraHeaders.put(
                Objects.requireNonNull(name, "header name must not be null"),
                Objects.requireNonNull(value, "header value must not be null"));
        return this;
    }

    public AuditClient build() {
        ResolvedClientConfig cfg = ConfigResolver.resolveClient(
                profile, apiKey, baseDomain, scheme, debug);
        return AuditClient.fromResolved(cfg, environment, timeout, new LinkedHashMap<>(extraHeaders));
    }
}
