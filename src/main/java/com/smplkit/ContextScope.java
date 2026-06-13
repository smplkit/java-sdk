package com.smplkit;

import java.util.List;

/**
 * A restorable handle to the current thread's evaluation context, returned by
 * {@link SmplClient#setContext(java.util.List)}.
 *
 * <p>Optional to use. A bare {@code client.setContext([...])} is fire-and-forget
 * — the typical middleware pattern, where the context is set once at request
 * entry and never reverted. Holding the returned scope and closing it instead
 * restores whatever context was active before the {@code setContext} call, which
 * is useful for bounded overrides such as impersonation. Try-with-resources is
 * the natural shape:</p>
 *
 * <pre>{@code
 * try (ContextScope scope = client.setContext(List.of(
 *         new Context("user", "impersonated")))) {
 *     // evaluations on this thread see the impersonated user
 * }
 * // the previous context is restored here
 * }</pre>
 *
 * <p>{@link #close()} is idempotent — restoring more than once is a no-op — and
 * declares no checked exception, so it composes cleanly with try-with-resources.</p>
 */
public final class ContextScope implements AutoCloseable {

    private final ThreadLocal<List<Context>> holder;
    private final List<Context> previous;
    private boolean closed;

    /**
     * @param holder the thread-local the owning {@link SmplClient} stores the
     *     active context in
     * @param previous the context that was active before the {@code setContext}
     *     call that produced this scope, captured so {@link #close()} can put it back
     */
    ContextScope(ThreadLocal<List<Context>> holder, List<Context> previous) {
        this.holder = holder;
        this.previous = previous;
    }

    /**
     * Restores the evaluation context that was active before the
     * {@link SmplClient#setContext(java.util.List)} call that produced this
     * scope. Idempotent — calling it again has no effect.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        holder.set(previous);
    }
}
