package com.smplkit.platform;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A context instance as returned by {@code client.platform.contexts.list()} and {@code .get()}.
 *
 * <p>The composite {@link #getId()} is {@code "type:key"}. Write-side registration
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

    /**
     * Returns the context type (for example {@code "user"}).
     *
     * @return the context type
     */
    public String getType() { return type; }

    /**
     * Returns the context key — the type-scoped identifier for this instance.
     *
     * @return the context key
     */
    public String getKey() { return key; }

    /**
     * Returns the display name, or {@code null} if unset.
     *
     * @return the display name, or {@code null} when not set
     */
    public String getName() { return name; }

    /**
     * Returns this context's attributes as an unmodifiable map.
     *
     * @return an unmodifiable view of the context attributes
     */
    public Map<String, Object> getAttributes() { return attributes; }

    /**
     * Returns when this context was created, as reported by the server.
     *
     * @return the creation timestamp, or {@code null} if unavailable
     */
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Returns when this context was last updated, as reported by the server.
     *
     * @return the last-updated timestamp, or {@code null} if unavailable
     */
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "ContextEntity{type='" + type + "', key='" + key + "'}";
    }
}
