package com.smplkit.logging.adapters;

/**
 * A logger discovered by a {@link LoggingAdapter} during runtime scanning.
 *
 * <p>{@code level} is the explicitly-configured level on this logger, or {@code null} if the
 * level is inherited from a parent.  {@code resolvedLevel} is the effective level after
 * framework inheritance and is always non-null.</p>
 */
public record DiscoveredLogger(String name, String level, String resolvedLevel) {

    /**
     * Convenience constructor for adapters that do not distinguish between explicit and
     * effective levels (e.g. JUL).  Sets both {@code level} and {@code resolvedLevel} to the
     * same value.
     */
    public DiscoveredLogger(String name, String level) {
        this(name, level, level);
    }
}
