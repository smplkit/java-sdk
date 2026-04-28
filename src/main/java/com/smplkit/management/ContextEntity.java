package com.smplkit.management;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A context instance as returned by {@code client.management.contexts.list()} and {@code .get()}.
 *
 * <p>The composite {@link #getId()} is {@code "{type}:{key}"}. Write-side registration
 * uses {@link ContextsClient#register}.</p>
 */
public final class ContextEntity {

    private final String type;
    private final String key;
    private final String name;
    private final Map<String, Object> attributes;
    private final Instant createdAt;
    private final Instant updatedAt;

    ContextEntity(String type, String key, String name,
                  Map<String, Object> attributes,
                  Instant createdAt, Instant updatedAt) {
        this.type = type;
        this.key = key;
        this.name = name;
        this.attributes = attributes != null ? Collections.unmodifiableMap(new HashMap<>(attributes)) : Collections.emptyMap();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Composite {@code "type:key"} identifier. */
    public String getId() { return type + ":" + key; }

    public String getType() { return type; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public Map<String, Object> getAttributes() { return attributes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "ContextEntity{type='" + type + "', key='" + key + "'}";
    }
}
