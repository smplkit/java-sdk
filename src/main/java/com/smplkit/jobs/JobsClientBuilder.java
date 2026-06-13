package com.smplkit.jobs;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for a standalone {@link JobsClient}.
 *
 * <p>Resolution order matches {@link com.smplkit.SmplClientBuilder} but skips
 * environment/service/telemetry: jobs is account-global and never
 * environment-scoped, so it resolves credentials/base-domain from
 * {@code ~/.smplkit} / env vars / builder args.</p>
 */
public final class JobsClientBuilder {

    private String profile;
    private String apiKey;
    private String baseDomain;
    private String scheme;
    private Duration timeout = Duration.ofSeconds(30);
    private Boolean debug;
    private final Map<String, String> extraHeaders = new LinkedHashMap<>();

    JobsClientBuilder() {}

    /**
     * Use the given named {@code ~/.smplkit} profile section.
     *
     * @param profile named {@code ~/.smplkit} profile section
     * @return this builder
     */
    public JobsClientBuilder profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        return this;
    }

    /**
     * Set the API key explicitly. When omitted, the key is resolved from
     * {@code SMPLKIT_API_KEY} or {@code ~/.smplkit}.
     *
     * @param apiKey API key for the account
     * @return this builder
     */
    public JobsClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Set the base domain for API requests. Defaults to {@code "smplkit.com"}.
     *
     * @param baseDomain base domain for API requests
     * @return this builder
     */
    public JobsClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    /**
     * Set the URL scheme. Defaults to {@code "https"}.
     *
     * @param scheme URL scheme used for requests
     * @return this builder
     */
    public JobsClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    /**
     * Set the per-request read timeout. Defaults to 30 seconds.
     *
     * @param timeout per-request read timeout
     * @return this builder
     */
    public JobsClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    /**
     * Enable or disable SDK debug logging.
     *
     * @param debug {@code true} to enable SDK debug logging
     * @return this builder
     */
    public JobsClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /** Extra headers attached to every request (SDK-managed headers win on collision). */
    public JobsClientBuilder extraHeaders(Map<String, String> extraHeaders) {
        Objects.requireNonNull(extraHeaders, "extraHeaders must not be null");
        this.extraHeaders.putAll(extraHeaders);
        return this;
    }

    /** Add a single extra header attached to every request. */
    public JobsClientBuilder header(String name, String value) {
        this.extraHeaders.put(
                Objects.requireNonNull(name, "header name must not be null"),
                Objects.requireNonNull(value, "header value must not be null"));
        return this;
    }

    /**
     * Resolve credentials and build a standalone {@link JobsClient} that owns
     * its own transport.
     *
     * @return a standalone {@link JobsClient}
     */
    public JobsClient build() {
        ResolvedClientConfig cfg = ConfigResolver.resolveClient(
                profile, apiKey, baseDomain, scheme, debug);
        return JobsClient.fromResolved(cfg, timeout, new LinkedHashMap<>(extraHeaders));
    }
}
