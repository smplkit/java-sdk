package com.smplkit;

/**
 * Shared utilities used across SDK modules.
 */
public final class Helpers {

    private Helpers() {}

    /**
     * Convert a slug-style key to a human-readable display name.
     *
     * <p>{@code "checkout-v2"} → {@code "Checkout V2"}<br>
     * {@code "user_service"} → {@code "User Service"}</p>
     */
    public static String keyToDisplayName(String key) {
        String[] words = key.replace("-", " ").replace("_", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            String w = words[i];
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1));
            }
        }
        return sb.toString();
    }
}
