package com.smplkit.account;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for {@link AccountClient}.
 *
 * <p>Resolution order matches {@link com.smplkit.SmplClientBuilder} but skips
 * environment/service/telemetry: the account plane has no runtime context. When
 * {@code baseUrl} is not supplied, the app-service base URL is resolved from the
 * resolved {@code scheme}/{@code baseDomain}.</p>
 */
public final class AccountClientBuilder {

    private String profile;
    private String apiKey;
    private String baseUrl;
    private String baseDomain;
    private String scheme;
    private Boolean debug;
    private final Map<String, String> extraHeaders = new HashMap<>();

    AccountClientBuilder() {}

    /**
     * Sets the named {@code ~/.smplkit} profile section to read configuration from.
     *
     * @param profile the profile name
     * @return this builder
     */
    public AccountClientBuilder profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        return this;
    }

    /**
     * Sets the API key. When omitted, the key is resolved from the environment
     * or {@code ~/.smplkit}.
     *
     * @param apiKey the API key
     * @return this builder
     */
    public AccountClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Sets the full app-service base URL. Usually resolved from
     * {@code baseDomain}/{@code scheme}; supply it directly to override.
     *
     * @param baseUrl the full app-service base URL
     * @return this builder
     */
    public AccountClientBuilder baseUrl(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        return this;
    }

    /**
     * Sets the base domain for API requests (default {@code "smplkit.com"}).
     *
     * @param baseDomain the base domain
     * @return this builder
     */
    public AccountClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    /**
     * Sets the URL scheme (default {@code "https"}).
     *
     * @param scheme the URL scheme
     * @return this builder
     */
    public AccountClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    /**
     * Enables or disables SDK debug logging.
     *
     * @param debug {@code true} to enable debug logging
     * @return this builder
     */
    public AccountClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /**
     * Adds extra headers attached to every request.
     *
     * @param extraHeaders the headers to attach; {@code null} is ignored
     * @return this builder
     */
    public AccountClientBuilder extraHeaders(Map<String, String> extraHeaders) {
        if (extraHeaders != null) this.extraHeaders.putAll(extraHeaders);
        return this;
    }

    /**
     * Builds an {@link AccountClient} from the configured options, resolving any
     * unset values from the environment and {@code ~/.smplkit}.
     *
     * @return a new {@link AccountClient}
     */
    public AccountClient build() {
        ResolvedClientConfig cfg = ConfigResolver.resolveClient(
                profile, apiKey, baseDomain, scheme, debug);
        String appUrl = baseUrl != null
                ? baseUrl
                : ConfigResolver.serviceUrl(cfg.scheme, "app", cfg.baseDomain);
        return new AccountClient(cfg.apiKey, appUrl, extraHeaders);
    }
}
