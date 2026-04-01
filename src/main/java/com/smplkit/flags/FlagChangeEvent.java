package com.smplkit.flags;

/**
 * Event fired when a flag definition changes.
 *
 * @param key    the flag key that changed
 * @param source the change source ({@code "websocket"} or {@code "manual"})
 */
public record FlagChangeEvent(String key, String source) {
}
