package com.smplkit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An evaluation context for flag resolution.
 *
 * <p>A context represents an entity (user, account, device, etc.) with typed attributes
 * used for rule matching during flag evaluation.</p>
 *
 * <pre>{@code
 * Context ctx = new Context("user", "user-123", Map.of("plan", "enterprise", "firstName", "Alice"));
 * }</pre>
 *
 * <p>Or using the builder:</p>
 * <pre>{@code
 * Context ctx = Context.builder("user", "user-123")
 *     .name("Alice Smith")
 *     .attr("plan", "enterprise")
 *     .build();
 * }</pre>
 */
public final class Context {

    private final String type;
    private final String key;
    private final String name;
    private final Map<String, Object> attributes;

    /**
     * Creates a context with the given type, key, and attributes.
     *
     * @param type       the context type (e.g. "user", "account")
     * @param key        the unique identifier within the type
     * @param attributes additional attributes for rule evaluation
     */
    public Context(String type, String key, Map<String, Object> attributes) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.name = null;
        this.attributes = attributes != null
                ? Collections.unmodifiableMap(new HashMap<>(attributes))
                : Collections.emptyMap();
    }

    private Context(Builder builder) {
        this.type = builder.type;
        this.key = builder.key;
        this.name = builder.name;
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    }

    /** Returns the context type (e.g. "user", "account"). */
    public String type() { return type; }

    /** Returns the unique key within this context type. */
    public String key() { return key; }

    /** Returns the display name, or null. */
    public String name() { return name; }

    /** Returns an unmodifiable view of the context attributes. */
    public Map<String, Object> attributes() { return attributes; }

    /**
     * Converts this context to the evaluation dict format used by JSON Logic.
     * The result is a map with the context key injected as "key".
     */
    public Map<String, Object> toEvalDict() {
        Map<String, Object> dict = new HashMap<>(attributes);
        dict.put("key", key);
        return dict;
    }

    /**
     * Returns a new builder for constructing a {@link Context}.
     *
     * @param type the context type
     * @param key  the unique identifier
     * @return a new builder
     */
    public static Builder builder(String type, String key) {
        return new Builder(type, key);
    }

    /**
     * Builder for {@link Context}.
     */
    public static final class Builder {
        private final String type;
        private final String key;
        private String name;
        private final Map<String, Object> attributes = new HashMap<>();

        private Builder(String type, String key) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.key = Objects.requireNonNull(key, "key must not be null");
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder attr(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public Context build() {
            return new Context(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Context c)) return false;
        return type.equals(c.type) && key.equals(c.key) && attributes.equals(c.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, key, attributes);
    }

    @Override
    public String toString() {
        return "Context{type='" + type + "', key='" + key + "', attributes=" + attributes + "}";
    }
}
