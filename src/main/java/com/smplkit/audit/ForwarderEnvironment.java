package com.smplkit.audit;

/**
 * Per-environment enablement and optional configuration override for a
 * {@link Forwarder}.
 *
 * <p>A forwarder delivers events in a given environment only when that
 * environment has an entry in {@link Forwarder#environments} with
 * {@code enabled = true}. An environment with no entry (or
 * {@code enabled = false}) receives no deliveries.</p>
 */
public final class ForwarderEnvironment {

    /**
     * Whether the forwarder delivers events in this environment. Defaults
     * to {@code false}.
     */
    public boolean enabled = false;

    /**
     * Optional per-environment destination configuration that fully
     * replaces the forwarder's base {@link Forwarder#configuration} for
     * this environment. {@code null} (the default) inherits the base
     * configuration. As with the base configuration, header values are
     * plaintext on writes and returned redacted on reads — re-supply real
     * values before {@link Forwarder#save()}.
     */
    public HttpConfiguration configuration = null;

    public ForwarderEnvironment() {}

    public ForwarderEnvironment(boolean enabled) {
        this.enabled = enabled;
    }

    public ForwarderEnvironment(boolean enabled, HttpConfiguration configuration) {
        this.enabled = enabled;
        this.configuration = configuration;
    }
}
