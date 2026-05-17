package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Event fired when the SDK applies a new effective level to a logger.
 *
 * <p>A listener invocation always corresponds to exactly one
 * {@code adapter.applyLevel(...)} call: a key-scoped listener fires for the
 * one logger whose level changed; a global listener fires once per affected
 * logger (so a single trigger that re-resolves N loggers produces N global
 * invocations). Logger deletion is not a level change and does not produce
 * an event.</p>
 *
 * @param id      the logger id whose effective level changed
 * @param level   the newly-applied effective level
 * @param source  the trigger ({@code "websocket"}, {@code "manual"}, {@code "start"})
 */
public record LoggerChangeEvent(String id, LogLevel level, String source) {
}
