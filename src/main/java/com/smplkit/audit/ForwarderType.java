package com.smplkit.audit;

/**
 * SIEM streaming destination type. Mirrors the audit OpenAPI
 * {@code ForwarderType} enum so the wrapper public surface keeps
 * customer code outside {@code com.smplkit.internal.*}.
 *
 * <p>ADR-047 §2.12. The audit service rejects any other value with
 * a 400.
 */
public enum ForwarderType {
    HTTP("http"),
    DATADOG("datadog"),
    SPLUNK_HEC("splunk_hec"),
    SUMO_LOGIC("sumo_logic"),
    NEW_RELIC("new_relic"),
    HONEYCOMB("honeycomb"),
    ELASTIC("elastic");

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
