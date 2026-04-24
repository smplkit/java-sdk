package com.smplkit.flags;

/**
 * Event fired when a flag definition changes.
 *
 * @param id      the flag id that changed
 * @param source  the change source ({@code "websocket"} or {@code "manual"})
 * @param deleted {@code true} if the flag was deleted
 */
public record FlagChangeEvent(String id, String source, boolean deleted) {

    /** Convenience constructor for non-delete events. */
    public FlagChangeEvent(String id, String source) {
        this(id, source, false);
    }

    /** Returns {@code true} if the flag was deleted. */
    public boolean isDeleted() {
        return deleted;
    }
}
