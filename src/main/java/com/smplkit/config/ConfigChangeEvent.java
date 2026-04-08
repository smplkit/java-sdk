package com.smplkit.config;

/**
 * Describes a single config item value change.
 *
 * @param configKey the config key (e.g. "user_service")
 * @param itemKey   the item key within the config (e.g. "timeout")
 * @param oldValue  the previous value (may be {@code null} if the item was added)
 * @param newValue  the updated value (may be {@code null} if the item was removed)
 * @param source    how the change was delivered: {@code "websocket"}, {@code "manual"}
 */
public record ConfigChangeEvent(String configKey, String itemKey, Object oldValue, Object newValue, String source) {

    @Override
    public String toString() {
        return "ConfigChangeEvent[configKey=" + configKey
                + ", itemKey=" + itemKey
                + ", oldValue=" + oldValue
                + ", newValue=" + newValue
                + ", source=" + source + "]";
    }
}
