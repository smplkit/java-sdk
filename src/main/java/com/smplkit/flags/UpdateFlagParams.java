package com.smplkit.flags;

import java.util.List;
import java.util.Map;

/**
 * Parameters for updating an existing feature flag.
 *
 * <p>Only non-null fields are updated.</p>
 */
public final class UpdateFlagParams {

    private final String name;
    private final String description;
    private final Object defaultValue;
    private final List<Map<String, Object>> values;
    private final Map<String, Object> environments;

    private UpdateFlagParams(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.defaultValue = builder.defaultValue;
        this.values = builder.values;
        this.environments = builder.environments;
    }

    public String name() { return name; }
    public String description() { return description; }
    public Object defaultValue() { return defaultValue; }
    public List<Map<String, Object>> values() { return values; }
    public Map<String, Object> environments() { return environments; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String description;
        private Object defaultValue;
        private List<Map<String, Object>> values;
        private Map<String, Object> environments;

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder defaultValue(Object defaultValue) { this.defaultValue = defaultValue; return this; }
        public Builder values(List<Map<String, Object>> values) { this.values = values; return this; }
        public Builder environments(Map<String, Object> environments) { this.environments = environments; return this; }

        public UpdateFlagParams build() {
            return new UpdateFlagParams(this);
        }
    }
}
