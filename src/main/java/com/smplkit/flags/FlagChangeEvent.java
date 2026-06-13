package com.smplkit.flags;

/**
 * Describes a flag definition change. Frozen — fields are set at construction.
 *
 * @param id      the flag id that changed
 * @param source  the change source ({@code "websocket"} or {@code "manual"})
 * @param deleted {@code true} if the flag was deleted
 */
public record FlagChangeEvent(String id, String source, boolean deleted) {

    /** Convenience constructor for non-delete events ({@code deleted = false}). */
    public FlagChangeEvent(String id, String source) {
        this(id, source, false);
    }

    /** Returns {@code true} if the flag was deleted. */
    public boolean isDeleted() {
        return deleted;
    }
}
