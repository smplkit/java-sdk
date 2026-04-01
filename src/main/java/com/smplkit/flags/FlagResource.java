package com.smplkit.flags;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A feature flag resource returned by the management API.
 *
 * <p>Provides {@link #update} and {@link #addRule} convenience methods
 * that delegate to the parent {@link FlagsClient}.</p>
 */
public final class FlagResource {

    private final String id;
    private final String key;
    private final String name;
    private final String description;
    private final String type;
    private final Object defaultValue;
    private final List<Map<String, Object>> values;
    private final Map<String, Object> environments;
    private final Instant createdAt;
    private final Instant updatedAt;
    private FlagsClient client;

    FlagResource(
            String id, String key, String name, String description, String type,
            Object defaultValue, List<Map<String, Object>> values,
            Map<String, Object> environments, Instant createdAt, Instant updatedAt
    ) {
        this.id = id;
        this.key = key;
        this.name = name;
        this.description = description;
        this.type = type;
        this.defaultValue = defaultValue;
        this.values = values != null ? values : Collections.emptyList();
        this.environments = environments != null ? environments : Collections.emptyMap();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void setClient(FlagsClient client) {
        this.client = client;
    }

    public String id() { return id; }
    public String key() { return key; }
    public String name() { return name; }
    public String description() { return description; }
    public String type() { return type; }
    public Object defaultValue() { return defaultValue; }
    public List<Map<String, Object>> values() { return values; }
    public Map<String, Object> environments() { return environments; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    /**
     * Updates this flag with the provided fields. Only non-null fields are changed.
     *
     * @param params update parameters
     * @return the updated flag resource
     */
    public FlagResource update(UpdateFlagParams params) {
        if (client == null) throw new IllegalStateException("FlagResource not bound to a client");
        return client.updateFlag(this, params);
    }

    /**
     * Adds a rule to this flag for the specified environment.
     *
     * @param builtRule a rule built via {@link Rule#build()}
     * @return the updated flag resource
     */
    public FlagResource addRule(Map<String, Object> builtRule) {
        if (client == null) throw new IllegalStateException("FlagResource not bound to a client");
        return client.addRuleToFlag(this, builtRule);
    }
}
