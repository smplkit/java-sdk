package com.smplkit;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable RGB(A) color value, mirroring the Python SDK's {@code Color}.
 *
 * <p>Construct from a hex string ({@code #RGB}, {@code #RRGGBB}, {@code #RRGGBBAA})
 * or from RGB components via {@link #rgb(int, int, int)}. Validation runs at
 * construction; invalid input raises {@link IllegalArgumentException} immediately
 * rather than failing later in a serializer or as a server 400.</p>
 *
 * <p>The wire format is the hex string; the SDK wraps and unwraps at the boundary.</p>
 */
public final class Color {

    private static final Pattern HEX_PATTERN =
            Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");

    private final String hex;

    /**
     * Constructs a {@code Color} from a hex string.
     *
     * <p>Accepts {@code #RGB}, {@code #RRGGBB}, or {@code #RRGGBBAA}. Anything
     * else raises {@link IllegalArgumentException}.</p>
     */
    public Color(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex must not be null");
        }
        if (!HEX_PATTERN.matcher(hex).matches()) {
            throw new IllegalArgumentException(
                    "Invalid hex color: " + hex
                            + " (expected #RGB, #RRGGBB, or #RRGGBBAA)");
        }
        this.hex = hex.toLowerCase();
    }

    /**
     * Constructs a {@code Color} from RGB components (each 0–255).
     *
     * <p>Out-of-range values raise {@link IllegalArgumentException}.</p>
     */
    public static Color rgb(int r, int g, int b) {
        validateComponent("r", r);
        validateComponent("g", g);
        validateComponent("b", b);
        return new Color(String.format("#%02x%02x%02x", r, g, b));
    }

    private static void validateComponent(String name, int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(
                    name + " must be 0-255, got " + value);
        }
    }

    /** Returns the hex string (lowercase). */
    public String hex() {
        return hex;
    }

    @Override
    public String toString() {
        return hex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Color other)) return false;
        return Objects.equals(hex, other.hex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hex);
    }
}
