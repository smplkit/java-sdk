package com.smplkit.audit;

/**
 * Supported SIEM forwarder destination types.
 *
 * <p>The audit service's OpenAPI spec declares {@code forwarder_type} as a
 * string-with-enum-constraint; this Java-side enum mirrors that constraint so
 * customers get autocomplete and type-checked values instead of stringly-typed
 * inputs. A {@code ForwarderType} member's {@link #getValue()} returns its
 * string literal ({@code ForwarderType.HTTP.getValue().equals("http")}).</p>
 *
 * <p>The available types are real-time HTTP destinations sharing one outbound
 * delivery path. Object-storage archival (S3, GCS, etc.) has a different
 * operational shape (batching, IAM, lifecycle policies) and may get its own
 * type if customer demand warrants.</p>
 */
public enum ForwarderType {
    DATADOG("datadog"),
    ELASTIC("elastic"),
    HONEYCOMB("honeycomb"),
    HTTP("http"),
    NEW_RELIC("new_relic"),
    SPLUNK_HEC("splunk_hec"),
    SUMO_LOGIC("sumo_logic");

    private final String value;

    ForwarderType(String value) {
        this.value = value;
    }

    /** The wire-format slug — e.g. {@code "splunk_hec"}. */
    public String getValue() {
        return value;
    }

    /** Parse a wire-format slug. Throws on unknown values. */
    public static ForwarderType fromValue(String value) {
        for (ForwarderType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown ForwarderType: " + value);
    }
}
