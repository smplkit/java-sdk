package com.smplkit.config;

import java.util.Map;

/**
 * Parameters for updating an existing configuration.
 *
 * <p>All fields are optional. If a field is not set, the current value from the existing
 * config is preserved when the update is sent to the server.</p>
 *
 * <pre>{@code
 * UpdateConfigParams params = UpdateConfigParams.builder()
 *     .description("New description")
 *     .values(Map.of("timeout", 30))
 *     .build();
 * Config updated = client.config().update(existing, params);
 * }</pre>
 */
public final class UpdateConfigParams {

    private final String name;
    private final String description;
    private final Map<String, Object> values;
    private final Map<String, Map<String, Object>> environments;

    private UpdateConfigParams(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.values = builder.values;
        this.environments = builder.environments;
    }

    /** Returns the new display name, or {@code null} if not set. */
    public String name() { return name; }

    /** Returns the new description, or {@code null} if not set. */
    public String description() { return description; }

    /** Returns the new base values map, or {@code null} if not set. */
    public Map<String, Object> values() { return values; }

    /** Returns the new environments map, or {@code null} if not set. */
    public Map<String, Map<String, Object>> environments() { return environments; }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link UpdateConfigParams}. */
    public static final class Builder {

        private String name;
        private String description;
        private Map<String, Object> values;
        private Map<String, Map<String, Object>> environments;

        private Builder() {}

        /** Sets the new display name. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the new description. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Sets the new base values map (replaces entirely). */
        public Builder values(Map<String, Object> values) {
            this.values = values;
            return this;
        }

        /** Sets the new environments map (replaces entirely). */
        public Builder environments(Map<String, Map<String, Object>> environments) {
            this.environments = environments;
            return this;
        }

        /** Builds the {@link UpdateConfigParams} instance. */
        public UpdateConfigParams build() {
            return new UpdateConfigParams(this);
        }
    }
}
