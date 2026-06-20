package com.smplkit.jobs;

/**
 * Where a {@code RETRY} run sits in its retry chain (read-only).
 *
 * <p>Present on a {@link Run} only when its {@link Run#trigger} is
 * {@code RETRY} — an automatic retry of a failed run, per the job's retry
 * policy.</p>
 */
public final class RunRetry {

    /**
     * Id of the chain's original run — the first attempt that failed and started
     * the chain.
     */
    public final String of;

    /**
     * Which retry this run is — {@code 1} for the first retry, {@code 2} for the
     * second, and so on.
     */
    public final int attempt;

    /**
     * @param of id of the chain's original (first-attempt) run
     * @param attempt which retry this run is ({@code 1}-based)
     */
    public RunRetry(String of, int attempt) {
        this.of = of;
        this.attempt = attempt;
    }

    @Override
    public String toString() {
        return "RunRetry(of=" + of + ", attempt=" + attempt + ")";
    }
}
