package com.smplkit.flags.types;

/**
 * Describes a flag declaration for buffered registration.
 *
 * <p>Used by {@code client.flags().register} to queue declarations for bulk
 * registration. {@code service} and {@code environment} default to
 * {@code null}; the runtime client fills them from the active
 * {@link com.smplkit.SmplClient} when it forwards declarations.</p>
 *
 * @param id          the flag id
 * @param type        the flag type ({@code "BOOLEAN"}, {@code "STRING"},
 *                    {@code "NUMERIC"}, {@code "JSON"})
 * @param defaultValue the flag's default served value
 * @param service     owning service name, or {@code null}
 * @param environment scoping environment, or {@code null}
 */
public record FlagDeclaration(String id, String type, Object defaultValue,
                              String service, String environment) {

    /** Construct a declaration with no service/environment scoping. */
    public FlagDeclaration(String id, String type, Object defaultValue) {
        this(id, type, defaultValue, null, null);
    }
}
