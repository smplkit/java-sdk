package com.smplkit.management;

import com.smplkit.ConfigResolver;
import com.smplkit.ConfigResolver.ResolvedManagementConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * Builder for {@link SmplManagementClient}.
 *
 * <p>Resolution order matches {@link com.smplkit.SmplClientBuilder} but skips
 * environment/service/telemetry: the management plane has no runtime context.</p>
 */
public final class SmplManagementClientBuilder {

    private String profile;
    private String apiKey;
    private String baseDomain;
    private String scheme;
    private Duration timeout = Duration.ofSeconds(30);
    private Boolean debug;

    SmplManagementClientBuilder() {}

    public SmplManagementClientBuilder profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        return this;
    }

    public SmplManagementClientBuilder apiKey(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        return this;
    }

    public SmplManagementClientBuilder baseDomain(String baseDomain) {
        this.baseDomain = Objects.requireNonNull(baseDomain, "baseDomain must not be null");
        return this;
    }

    public SmplManagementClientBuilder scheme(String scheme) {
        this.scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        return this;
    }

    public SmplManagementClientBuilder timeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        return this;
    }

    public SmplManagementClientBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    public SmplManagementClient build() {
        ResolvedManagementConfig cfg = ConfigResolver.resolveManagement(
                profile, apiKey, baseDomain, scheme, debug);
        return SmplManagementClient.fromResolved(cfg, timeout);
    }
}
