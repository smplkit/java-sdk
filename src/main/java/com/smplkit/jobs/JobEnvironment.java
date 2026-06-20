package com.smplkit.jobs;

import java.time.OffsetDateTime;

/**
 * Per-environment enablement, schedule, and optional configuration override for
 * a job.
 *
 * <p>A job runs in a given environment only when that environment has an entry
 * in {@link Job#environments} with {@code enabled=true} (scheduled there for a
 * recurring job, triggerable there for a manual one); an environment with no
 * entry (or {@code enabled=false}) is disabled there.</p>
 */
public final class JobEnvironment {

    /**
     * Whether the job is enabled in this environment. Defaults to
     * {@code false}.
     */
    public boolean enabled = false;

    /**
     * Optional per-environment cron schedule override. {@code null} (the
     * default) inherits the job's base {@link Job#schedule}. When set, it must
     * be a 5-field cron expression evaluated in UTC and is only meaningful on a
     * recurring job — it varies the cadence within this environment. It cannot
     * appear on a manual or one-off job, and cannot change a job's kind.
     */
    public String schedule = null;

    /**
     * Optional per-environment IANA timezone override for evaluating this
     * environment's cron {@link #schedule}. {@code null} (the default) inherits
     * the job's base {@link Job#timezone} (else UTC). When set, it must be a
     * valid IANA timezone key (e.g. {@code "America/New_York"}) and is only
     * meaningful on a recurring job; it may be set on an environment that
     * inherits the base schedule (it need not also override {@link #schedule}).
     */
    public String timezone = null;

    /**
     * Optional per-environment retry-policy override — the id of a
     * {@link RetryPolicy} (or {@code "Default"}). {@code null} (the default)
     * inherits the job's base {@link Job#retryPolicy}. Sent on writes only when
     * not {@code null}.
     */
    public String retryPolicy = null;

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
