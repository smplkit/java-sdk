package com.smplkit.jobs;

import java.time.OffsetDateTime;

/**
 * Per-environment enablement, schedule, and optional configuration override for
 * a job.
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
     * Optional per-environment cron schedule override. {@code null} (the
     * default) inherits the job's base {@link Job#schedule}. When set, it must
     * be a 5-field cron expression evaluated in UTC and is only meaningful on a
     * recurring job — it varies the cadence within this environment, it cannot
     * turn a one-off job recurring or vice-versa.
     */
    public String schedule = null;

    /**
     * Optional per-environment request configuration that fully replaces the
     * job's base {@link Job#configuration} for this environment. {@code null}
     * (the default) inherits the base configuration. Header values are returned
     * in plaintext on reads, so a get-mutate-put round-trip preserves them.
     */
    public HttpConfig configuration = null;

    /**
     * Read-only: the next scheduled fire time in this environment. {@code null}
     * when the environment is not enabled, once a one-off run has fired, or for
     * an unsaved instance. Server-derived — mutating it has no effect.
     */
    public OffsetDateTime nextRunAt = null;

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
