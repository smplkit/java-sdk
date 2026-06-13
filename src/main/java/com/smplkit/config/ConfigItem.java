package com.smplkit.config;

/** A single typed item in a {@link Config}. */
public final class ConfigItem {

    private final String name;
    private final Object value;
    private final ItemType type;
    private final String description;

    /**
     * Creates a config item with no description.
     *
     * @param name  the item key
     * @param value the item value
     * @param type  the item type
     */
    public ConfigItem(String name, Object value, ItemType type) {
        this(name, value, type, null);
    }

    /**
     * Creates a config item with no description, accepting the item type as
     * a wire string (e.g. {@code "STRING"}).
     *
     * @param name  the item key
     * @param value the item value
     * @param type  the item type as a wire string
     */
    public ConfigItem(String name, Object value, String type) {
        this(name, value, ItemType.fromValue(type), null);
    }

    /**
     * Creates a config item.
     *
     * @param name        the item key
     * @param value       the item value
     * @param type        the item type
     * @param description optional description
     */
    public ConfigItem(String name, Object value, ItemType type, String description) {
        this.name = name;
        this.value = value;
        this.type = type;
        this.description = description;
    }

    /**
     * Creates a config item, accepting the item type as a wire string.
     *
     * @param name        the item key
     * @param value       the item value
     * @param type        the item type as a wire string
     * @param description optional description
     */
    public ConfigItem(String name, Object value, String type, String description) {
        this(name, value, ItemType.fromValue(type), description);
    }

    /**
     * Returns the item key.
     *
     * @return the item key within its config
     */
    public String name() {
        return name;
    }

    /**
     * Returns the item value.
     *
     * @return the item's value
     */
    public Object value() {
        return value;
    }

    /**
     * Returns the item type.
     *
     * @return the item value type
     */
    public ItemType type() {
        return type;
    }

    /**
     * Returns the optional description (may be {@code null}).
     *
     * @return the human-readable description, or {@code null} if none was set
     */
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "ConfigItem(name=" + name + ", value=" + value + ", type=" + type.value() + ")";
    }
}
