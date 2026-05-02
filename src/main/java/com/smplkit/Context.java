package com.smplkit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluation context for flag resolution and bulk context registration.
 *
 * <p>Mirrors the Python SDK's {@code Context}: an entity (user, account, device, etc.)
 * with typed attributes used for rule matching during flag evaluation.</p>
 *
 * <p>{@code type} and {@code key} together form the entity identity — accessible as
 * {@link #id()} (the composite {@code "type:key"}). Both are required and validated
 * at construction; {@code null} or empty input raises immediately rather than as a
 * vague server error at flush time.</p>
 *
 * <pre>{@code
 * Context ctx = new Context("user", "user-123",
 *     Map.of("plan", "enterprise", "firstName", "Alice"));
 * }</pre>
 *
 * <p>Or using the builder for additive attribute construction:</p>
 * <pre>{@code
 * Context ctx = Context.builder("user", "user-123")
 *     .name("Alice Smith")
 *     .attr("plan", "enterprise")
 *     .build();
 * }</pre>
 *
 * <p>Java's type system enforces that {@code type} / {@code key} are strings at
 * compile time — no equivalent of Python's runtime {@code TypeError} is needed.
 * This class adds the boundary check for null / empty input.</p>
 */
public final class Context {

    private final String type;
    private final String key;
    private final String name;
    private final Map<String, Object> attributes;

    /** Creates a context with the given type and key (no extra attributes). */
    public Context(String type, String key) {
        this(type, key, null);
    }

    /** Creates a context with the given type, key, and attribute bag. */
    public Context(String type, String key, Map<String, Object> attributes) {
        this.type = validateIdentity("type", type);
        this.key = validateIdentity("key", key);
        this.name = null;
        this.attributes = attributes != null
                ? Collections.unmodifiableMap(new HashMap<>(attributes))
                : Collections.emptyMap();
    }

    private Context(Builder builder) {
        this.type = validateIdentity("type", builder.type);
        this.key = validateIdentity("key", builder.key);
        this.name = builder.name;
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    }

    private static String validateIdentity(String field, String value) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }

    /** Returns the context type (e.g. "user", "account"). */
    public String type() { return type; }

    /** Returns the unique key within this context type. */
    public String key() { return key; }

    /** Returns the composite identity {@code "type:key"}. */
    public String id() { return type + ":" + key; }

    /** Returns the display name, or null. */
    public String name() { return name; }

    /** Returns an unmodifiable view of the context attributes. */
    public Map<String, Object> attributes() { return attributes; }

    /** Returns a map of the context attributes with the context key added as "key". */
    public Map<String, Object> toEvalDict() {
        Map<String, Object> dict = new HashMap<>(attributes);
        dict.put("key", key);
        return dict;
    }

    /** Returns a new builder for constructing a {@link Context}. */
    public static Builder builder(String type, String key) {
        return new Builder(type, key);
    }

    /** Builder for {@link Context}. */
    public static final class Builder {
        private final String type;
        private final String key;
        private String name;
        private final Map<String, Object> attributes = new HashMap<>();

        private Builder(String type, String key) {
            this.type = validateIdentity("type", type);
            this.key = validateIdentity("key", key);
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
