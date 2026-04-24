package com.smplkit.config;

/**
 * Describes a single config item value change.
 *
 * @param configId the config id (e.g. "user_service")
 * @param itemKey  the item key within the config (e.g. "timeout")
 * @param oldValue the previous value (may be {@code null} if the item was added)
 * @param newValue the updated value (may be {@code null} if the item was removed)
 * @param source   how the change was detected (e.g. {@code "manual"} from {@code refresh()})
 * @param deleted  {@code true} if the config was deleted
 */
public record ConfigChangeEvent(String configId, String itemKey, Object oldValue, Object newValue,
                                String source, boolean deleted) {

    /** Convenience constructor for non-delete events. */
    public ConfigChangeEvent(String configId, String itemKey, Object oldValue, Object newValue, String source) {
        this(configId, itemKey, oldValue, newValue, source, false);
    }

    /** Returns {@code true} if the config was deleted. */
    public boolean isDeleted() {
        return deleted;
    }

    @Override
    public String toString() {
        return "ConfigChangeEvent[configId=" + configId
                + ", itemKey=" + itemKey
                + ", oldValue=" + oldValue
                + ", newValue=" + newValue
                + ", source=" + source
                + ", deleted=" + deleted + "]";
    }
}
