package com.smplkit.config;

/**
 * Describes a single config value change.
 *
 * <p>Frozen — fields are set at construction and cannot be mutated afterward.</p>
 *
 * @param configId The config id that changed.
 * @param itemKey  The item key within the config that changed.
 * @param oldValue The previous value.
 * @param newValue The updated value.
 * @param source   How the change was delivered ({@code "websocket"} or {@code "manual"}).
 */
public record ConfigChangeEvent(String configId, String itemKey, Object oldValue, Object newValue, String source) {

    @Override
    public String toString() {
        return "ConfigChangeEvent[configId=" + configId
                + ", itemKey=" + itemKey
                + ", oldValue=" + oldValue
                + ", newValue=" + newValue
                + ", source=" + source + "]";
    }
}
