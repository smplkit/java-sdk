package com.smplkit.logging;

import com.smplkit.LogLevel;

/**
 * Fired once per managed logger whose effective level the SDK just applied.
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code id}: the affected logger's normalized id.</li>
 *   <li>{@code level}: the newly-applied effective smplkit level (e.g.
 *     {@code INFO}, {@code DEBUG}); same value the resolution algorithm
 *     returns and that {@link Levels#smplLevelToPython} converts.</li>
 *   <li>{@code source}: short string identifying the trigger — typically
 *     {@code "websocket"} or {@code "manual"} (a {@link LoggingClient#refresh}
 *     call).</li>
 * </ul>
 *
 * @param id     the affected logger's normalized id
 * @param level  the newly-applied effective smplkit level
 * @param source short string identifying the trigger
 */
public record LoggerChangeEvent(String id, LogLevel level, String source) {
}
