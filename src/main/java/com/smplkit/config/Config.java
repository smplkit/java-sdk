package com.smplkit.config;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable representation of a configuration resource from the Smpl Config service.
 *
 * @param id           unique identifier (UUID)
 * @param key          human-readable key (e.g., "user_service")
 * @param name         display name
 * @param description  optional description (may be null)
 * @param parent       parent config UUID, or null for root configs
 * @param values       base values map
 * @param environments map of environment names to their override maps
 * @param createdAt    creation timestamp (may be null)
 * @param updatedAt    last-modified timestamp (may be null)
 */
public record Config(
        String id,
        String key,
        String name,
        String description,
        String parent,
        Map<String, Object> values,
        Map<String, Map<String, Object>> environments,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Creates a Config with validation.
     */
    public Config {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(name, "name must not be null");
        values = values != null ? Map.copyOf(values) : Map.of();
        environments = environments != null ? Map.copyOf(environments) : Map.of();
    }

    @Override
    public String toString() {
        return "Config[id=" + id + ", key=" + key + ", name=" + name + "]";
    }
}
