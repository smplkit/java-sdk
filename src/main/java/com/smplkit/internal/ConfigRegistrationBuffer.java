package com.smplkit.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thread-safe batch buffer for config declarations. Mirrors Python's
 * {@code _ConfigRegistrationBuffer}: per-config metadata is retained across
 * flushes so post-drain deltas re-attribute correctly, and items are
 * dedup'd per {@code (configId, itemKey)} so an already-sent item is
 * never re-sent.
 */
public final class ConfigRegistrationBuffer {

    /** Item declaration record stored in {@link Entry#items}. */
    public static final class ItemEntry {
        public final Object defaultValue;
        public final String itemType;
        public final String description;

        public ItemEntry(Object defaultValue, String itemType, String description) {
            this.defaultValue = defaultValue;
            this.itemType = itemType;
            this.description = description;
        }
    }

    /** Pending payload row keyed by config id; drained on flush. */
    public static final class Entry {
        public final String id;
        public final String service;
        public final String environment;
        public final String parent;
        public final String name;
        public final String description;
        public final Map<String, ItemEntry> items = new LinkedHashMap<>();

        Entry(String id, Meta meta) {
            this.id = id;
            this.service = meta.service;
            this.environment = meta.environment;
            this.parent = meta.parent;
            this.name = meta.name;
            this.description = meta.description;
        }
    }

    private static final class Meta {
        final String service;
        final String environment;
        final String parent;
        final String name;
        final String description;

        Meta(String service, String environment, String parent, String name, String description) {
            this.service = service;
            this.environment = environment;
            this.parent = parent;
            this.name = name;
            this.description = description;
        }
    }

    private final Map<String, Entry> pending = new LinkedHashMap<>();
    private final Map<String, Meta> meta = new HashMap<>();
    private final Set<String> sentItems = new HashSet<>();
    private final Object lock = new Object();

    /** Idempotent — first writer's metadata wins. */
    public void declare(String configId, String service, String environment,
                        String parent, String name, String description) {
        synchronized (lock) {
            if (meta.containsKey(configId)) return;
            Meta m = new Meta(service, environment, parent, name, description);
            meta.put(configId, m);
            pending.put(configId, new Entry(configId, m));
        }
    }

    /**
     * Queue an item declaration for an already-declared config. Items
     * already sent in a previous {@link #drain()} are skipped.
     */
    public void addItem(String configId, String itemKey, String itemType,
                        Object defaultValue, String description) {
        synchronized (lock) {
            Meta m = meta.get(configId);
            if (m == null) return;
            String sentKey = configId + "::" + itemKey;
            if (sentItems.contains(sentKey)) return;
            Entry entry = pending.get(configId);
            if (entry == null) {
                entry = new Entry(configId, m);
                pending.put(configId, entry);
            }
            if (entry.items.containsKey(itemKey)) return;
            entry.items.put(itemKey, new ItemEntry(defaultValue, itemType, description));
        }
    }

    /** Returns and clears the pending batch; records sent items. */
    public List<Entry> drain() {
        synchronized (lock) {
            if (pending.isEmpty()) return new ArrayList<>();
            List<Entry> batch = new ArrayList<>(pending.values());
            for (Entry entry : batch) {
                for (String itemKey : entry.items.keySet()) {
                    sentItems.add(entry.id + "::" + itemKey);
                }
            }
            pending.clear();
            return batch;
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }
}
