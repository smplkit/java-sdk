package com.smplkit.audit;

/**
 * Per-environment enablement and optional configuration override for a forwarder.
 *
 * <p>A forwarder delivers events in a given environment only when that
 * environment has an entry in {@link Forwarder#environments} with
 * {@code enabled=true}. An environment with no entry (or {@code enabled=false})
 * receives no deliveries.</p>
 */
public final class ForwarderEnvironment {

    /**
     * Whether the forwarder delivers events in this environment. Defaults to
     * {@code false}.
     */
    public boolean enabled = false;

    /**
     * Optional per-environment destination configuration that fully replaces
     * the forwarder's base {@link Forwarder#configuration} for this
     * environment. {@code null} (the default) inherits the base configuration.
     * As with the base configuration, header values are returned in plaintext
     * on reads, so a get-mutate-put round-trip preserves them without
     * re-entering secrets.
     */
    public HttpConfiguration configuration = null;

    public ForwarderEnvironment() {}

    /**
     * @param enabled whether the forwarder delivers events in this environment
     */
    public ForwarderEnvironment(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @param enabled whether the forwarder delivers events in this environment
     * @param configuration per-environment destination configuration that
     *     fully replaces the base {@link Forwarder#configuration} for this
     *     environment; {@code null} inherits the base configuration
     */
    public ForwarderEnvironment(boolean enabled, HttpConfiguration configuration) {
        this.enabled = enabled;
        this.configuration = configuration;
    }
}
