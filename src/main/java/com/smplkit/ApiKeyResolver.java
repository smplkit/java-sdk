package com.smplkit;

import com.smplkit.errors.SmplException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an API key from an explicit value, environment variable, or config file.
 */
final class ApiKeyResolver {

    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "\\[default\\]\\s*[\\s\\S]*?api_key\\s*=\\s*\"([^\"]+)\"");

    private static final String NO_API_KEY_MESSAGE =
            "No API key provided. Set one of:\n" +
            "  1. Call .apiKey() on the builder or use SmplClient.create(apiKey)\n" +
            "  2. Set the SMPLKIT_API_KEY environment variable\n" +
            "  3. Add api_key to [default] in ~/.smplkit";

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
                String content = Files.readString(configPath);
                Matcher matcher = API_KEY_PATTERN.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            } catch (IOException e) {
                // Malformed file — skip
            }
        }

        throw new SmplException(NO_API_KEY_MESSAGE, 0, null);
    }
}
