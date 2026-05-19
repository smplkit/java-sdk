package com.smplkit.audit;

/**
 * SIEM streaming destination type. Mirrors the audit OpenAPI
 * {@code ForwarderType} enum so the wrapper public surface keeps
 * customer code outside {@code com.smplkit.internal.*}.
 *
 * <p>The available types are real-time HTTP destinations sharing one
 * outbound plumbing path. Object-storage archival (S3, GCS, etc.) has
 * different operational shape (batching, IAM, lifecycle policies) and
 * will get its own type if customer demand warrants.</p>
 *
 * <p>Members are declared in alphabetical order.</p>
 */
public enum ForwarderType {
    /** Datadog Logs intake. */
    DATADOG("datadog"),
    /** Elastic / Elasticsearch HTTP ingest. */
    ELASTIC("elastic"),
    /** Honeycomb events HTTP ingest. */
    HONEYCOMB("honeycomb"),
    /** Generic HTTP POST destination. */
    HTTP("http"),
    /** New Relic logs HTTP ingest. */
    NEW_RELIC("new_relic"),
    /** Splunk HTTP Event Collector. */
    SPLUNK_HEC("splunk_hec"),
    /** Sumo Logic HTTP source. */
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
