package com.smplkit.flags;

/**
 * Event fired when a flag definition changes.
 *
 * @param id     the flag id that changed
 * @param source the change source ({@code "websocket"} or {@code "manual"})
 */
public record FlagChangeEvent(String id, String source) {
}
