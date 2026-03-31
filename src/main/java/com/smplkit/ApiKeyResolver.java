package com.smplkit;

import com.smplkit.errors.SmplException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves an API key from an explicit value, environment variable, or config file.
 */
final class ApiKeyResolver {

    private static final String NO_API_KEY_MESSAGE =
            "No API key provided. Set one of:\n" +
            "  1. Call .apiKey() on the builder or use SmplClient.create(apiKey)\n" +
            "  2. Set the SMPLKIT_API_KEY environment variable\n" +
            "  3. Create a ~/.smplkit file with:\n" +
            "     [default]\n" +
            "     api_key = your_key_here";

    private ApiKeyResolver() {
        // Utility class
    }

    /**
     * Resolves an API key using the fallback chain: explicit → env var → config file.
     *
     * @param explicit the explicitly provided API key, or null
     * @return the resolved API key
     * @throws SmplException if no API key can be resolved
     */
    static String resolve(String explicit) {
        return resolve(explicit,
                System.getenv("SMPLKIT_API_KEY"),
                Paths.get(System.getProperty("user.home"), ".smplkit"));
    }

    /**
     * Package-private overload for testing.
     */
    static String resolve(String explicit, String envVal, Path configPath) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }

        if (envVal != null && !envVal.isEmpty()) {
            return envVal;
        }

        if (Files.exists(configPath)) {
            try {
                String apiKey = parseIniApiKey(Files.readString(configPath));
                if (apiKey != null) {
                    return apiKey;
                }
            } catch (IOException e) {
                // Unreadable file — skip
            }
        }

        throw new SmplException(NO_API_KEY_MESSAGE, 0, null);
    }

    /**
     * Parses an INI-format config file and returns the api_key from the [default] section.
     */
    private static String parseIniApiKey(String content) {
        boolean inDefault = false;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("[")) {
                inDefault = trimmed.equalsIgnoreCase("[default]");
                continue;
            }
            if (inDefault && trimmed.startsWith("api_key")) {
                int eqIndex = trimmed.indexOf('=');
                if (eqIndex != -1) {
                    String value = trimmed.substring(eqIndex + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }
}
