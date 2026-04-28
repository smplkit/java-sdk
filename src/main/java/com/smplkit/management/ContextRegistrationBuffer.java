package com.smplkit.management;

import com.smplkit.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe buffer for pending context registrations.
 *
 * <p>Shared between {@code FlagsClient} (auto-observation during evaluation)
 * and {@code ContextsClient} (explicit management-plane registration). Both
 * contributors drain into the same flush cycle.</p>
 */
public final class ContextRegistrationBuffer {

    private static final int MAX_SIZE = 10_000;

    private final Map<String, Map<String, Object>> seen = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > MAX_SIZE;
                }
            });
    private final ConcurrentLinkedQueue<Map<String, Object>> pending = new ConcurrentLinkedQueue<>();

    /** Buffer a single context. Duplicate composite keys (type:key) are deduped. */
    public void observe(Context ctx) {
        String compositeKey = ctx.type() + ":" + ctx.key();
        if (seen.containsKey(compositeKey)) return;
        Map<String, Object> entry = Map.of(
                "type", ctx.type(),
                "key", ctx.key(),
                "attributes", ctx.attributes() != null ? ctx.attributes() : Map.of()
        );
        seen.put(compositeKey, entry);
        pending.add(entry);
    }

    /** Buffer a list of contexts. */
    public void observeAll(List<Context> contexts) {
        for (Context ctx : contexts) {
            observe(ctx);
        }
    }

    /** Drain and return all pending entries, clearing the queue. */
    public List<Map<String, Object>> drain() {
        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> item;
        while ((item = pending.poll()) != null) {
            batch.add(item);
        }
        return batch;
    }

    /** Number of pending (not yet flushed) entries. */
    public int pendingCount() {
        return pending.size();
    }
}
