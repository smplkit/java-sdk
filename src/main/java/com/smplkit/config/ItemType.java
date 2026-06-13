package com.smplkit.config;

/** Type of a {@link ConfigItem} value. */
public enum ItemType {
    BOOLEAN("BOOLEAN"),
    JSON("JSON"),
    NUMBER("NUMBER"),
    STRING("STRING");

    private final String value;

    ItemType(String value) {
        this.value = value;
    }

    /**
     * Returns the wire string for this item type (e.g. {@code "STRING"}).
     *
     * @return the canonical upper-case wire string for this type
     */
    public String value() {
        return value;
    }

    /**
     * Returns the {@link ItemType} for a wire string, accepting either the
     * canonical upper-case value or any case-insensitive match.
     *
     * @param value the wire string to look up (case-insensitive)
     * @return the matching {@link ItemType}
     * @throws IllegalArgumentException if {@code value} matches no item type
     */
    public static ItemType fromValue(String value) {
        for (ItemType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown ItemType: " + value);
    }
}
