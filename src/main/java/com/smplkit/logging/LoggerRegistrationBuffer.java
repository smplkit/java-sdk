package com.smplkit.logging;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovery / registration buffer shared between the fused {@link LoggingClient}
 * and its {@code loggers} sub-client.
 *
 * <p>The fused client owns the buffer; the {@code loggers} sub-client shares it
 * so discovery (driven by {@link LoggingClient#install()}) and explicit
 * {@code loggers.register(...)} drain through one queue.</p>
 *
 * <p>Each entry is de-duplicated by normalized id — registering the same id
 * twice before a flush keeps only the first.</p>
 */
final class LoggerRegistrationBuffer {

    /** A single queued logger source awaiting a bulk flush. */
    record Entry(String id, String level, String resolvedLevel, String service, String environment) {}

    private final Set<String> seen = new HashSet<>();
    private final List<Entry> pending = new ArrayList<>();
    private final Object lock = new Object();

    void add(String id, String level, String resolvedLevel, String service, String environment) {
        synchronized (lock) {
            if (seen.add(id)) {
                pending.add(new Entry(id, level, resolvedLevel, service, environment));
            }
        }
    }

    List<Entry> drain() {
        synchronized (lock) {
            if (pending.isEmpty()) {
                return List.of();
            }
            List<Entry> batch = new ArrayList<>(pending);
            pending.clear();
            return batch;
        }
    }

    int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }
}
