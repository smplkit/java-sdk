package com.smplkit.config;

/**
 * Describes a single value change delivered by the config service.
 *
 * @param key      the config key that changed
 * @param oldValue the previous value (may be {@code null} if the key was added)
 * @param newValue the updated value (may be {@code null} if the key was removed)
 * @param source   how the change was delivered: {@code "websocket"}, {@code "manual"}
 */
public record ChangeEvent(String key, Object oldValue, Object newValue, String source) {

    @Override
    public String toString() {
        return "ChangeEvent[key=" + key
                + ", oldValue=" + oldValue
                + ", newValue=" + newValue
                + ", source=" + source + "]";
    }
}
