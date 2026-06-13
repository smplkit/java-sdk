package com.smplkit.flags.types;

/**
 * Operators supported by {@link com.smplkit.Rule#when}.
 *
 * <p>Customers should prefer {@code Op.EQ} etc. over raw strings so the IDE
 * can validate calls. Raw strings are still accepted for backward
 * compatibility.</p>
 */
public enum Op {

    CONTAINS("contains"),
    EQ("=="),
    GT(">"),
    GTE(">="),
    IN("in"),
    LT("<"),
    LTE("<="),
    NEQ("!=");

    private final String value;

    Op(String value) {
        this.value = value;
    }

    /** Returns the JSON Logic wire operator (e.g. {@code "=="}, {@code "contains"}). */
    public String value() {
        return value;
    }
}
