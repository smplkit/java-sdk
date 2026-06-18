package com.smplkit.jobs;

/**
 * Per-environment enablement and optional configuration override for a job.
 *
 * <p>A recurring job fires in a given environment only when that environment
 * has an entry in {@link Job#environments} with {@code enabled=true}; an
 * environment with no entry (or {@code enabled=false}) does not fire there.</p>
 */
public final class JobEnvironment {

    /**
     * Whether the job fires (schedules runs) in this environment. Defaults to
     * {@code false}.
     */
    public boolean enabled = false;

    /**
     * Optional per-environment request configuration that fully replaces the
     * job's base {@link Job#configuration} for this environment. {@code null}
     * (the default) inherits the base configuration. Header values are returned
     * in plaintext on reads, so a get-mutate-put round-trip preserves them.
     */
    public HttpConfig configuration = null;

    public JobEnvironment() {}

    /**
     * @param enabled whether the job fires in this environment
     */
    public JobEnvironment(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @param enabled whether the job fires in this environment
     * @param configuration per-environment request configuration that fully
     *     replaces the base {@link Job#configuration} for this environment;
     *     {@code null} inherits the base configuration
     */
    public JobEnvironment(boolean enabled, HttpConfig configuration) {
        this.enabled = enabled;
        this.configuration = configuration;
    }
}
