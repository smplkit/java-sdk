package com.smplkit.platform;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;
import com.smplkit.internal.Debug;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.app.ApiClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for a standalone {@link PlatformClient}.
 *
 * <p>Resolution order matches {@link com.smplkit.SmplClientBuilder} but skips
 * environment/service/telemetry: the platform plane has no runtime context.</p>
 */
public final class PlatformClientBuilder {

    private String profile;
    private String apiKey;
    private String baseDomain;
    private String scheme;
    private Boolean debug;
    private Map<String, String> extraHeaders = Map.of();
    private Duration timeout = Duration.ofSeconds(30);

    PlatformClientBuilder() {}

    /**
     * Sets the named {@code ~/.smplkit} profile section to read configuration from.
     *
     * @param profile the profile name
     * @return this builder
     */
    public PlatformClientBuilder profile(String profile) {
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
    public PlatformClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    /**
     * Sets the base domain for API requests (default {@code "smplkit.com"}).
     *
     * @param baseDomain the base domain
     * @return this builder
     */
    public PlatformClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    /**
     * Sets the URL scheme (default {@code "https"}).
     *
     * @param scheme the URL scheme
     * @return this builder
     */
    public PlatformClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    /**
     * Enables or disables SDK debug logging.
     *
     * @param debug {@code true} to enable debug logging
     * @return this builder
     */
    public PlatformClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /**
     * Sets extra headers attached to every request.
     *
     * @param extraHeaders the headers to attach; {@code null} clears any previously set
     * @return this builder
     */
    public PlatformClientBuilder extraHeaders(Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders != null ? Map.copyOf(extraHeaders) : Map.of();
        return this;
    }

    /**
     * Sets the per-request read timeout (default 30 seconds).
     *
     * @param timeout the read timeout
     * @return this builder
     */
    public PlatformClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    /**
     * Builds a standalone {@link PlatformClient} from the configured options,
     * resolving any unset values from the environment and {@code ~/.smplkit}.
     *
     * @return a new {@link PlatformClient} that owns its app transport
     */
    public PlatformClient build() {
        ResolvedClientConfig cfg = ConfigResolver.resolveClient(
                profile, apiKey, baseDomain, scheme, debug);
        if (cfg.debug) {
            Debug.enable();
        }
        String appBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "app", cfg.baseDomain);

        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(appBaseUrl);
        apiClient.setRequestInterceptor(
                HttpClients.compositeInterceptor(cfg.apiKey, extraHeaders));
        apiClient.setReadTimeout(timeout);

        return PlatformClient.standalone(apiClient);
    }
}
