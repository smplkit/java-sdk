package com.smplkit.flags;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parameters for creating a new feature flag.
 *
 * <pre>{@code
 * CreateFlagParams params = CreateFlagParams.builder("enable-feature", "Enable Feature", FlagType.BOOLEAN)
 *     .defaultValue(false)
 *     .description("Enables the new feature")
 *     .build();
 * }</pre>
 */
public final class CreateFlagParams {

    private final String key;
    private final String name;
    private final FlagType type;
    private final Object defaultValue;
    private final String description;
    private final List<Map<String, Object>> values;

    private CreateFlagParams(Builder builder) {
        this.key = builder.key;
        this.name = builder.name;
        this.type = builder.type;
        this.defaultValue = builder.defaultValue;
        this.description = builder.description;
        this.values = builder.values;
    }

    public String key() { return key; }
    public String name() { return name; }
    public FlagType type() { return type; }
    public Object defaultValue() { return defaultValue; }
    public String description() { return description; }
    public List<Map<String, Object>> values() { return values; }

    public static Builder builder(String key, String name, FlagType type) {
        return new Builder(key, name, type);
    }

    public static final class Builder {
        private final String key;
        private final String name;
        private final FlagType type;
        private Object defaultValue;
        private String description;
        private List<Map<String, Object>> values;

        private Builder(String key, String name, FlagType type) {
            this.key = Objects.requireNonNull(key);
            this.name = Objects.requireNonNull(name);
            this.type = Objects.requireNonNull(type);
        }

        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder values(List<Map<String, Object>> values) {
            this.values = values;
            return this;
        }

        public CreateFlagParams build() {
            return new CreateFlagParams(this);
        }
    }
}
