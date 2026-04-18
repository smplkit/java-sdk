package com.smplkit;

import com.smplkit.errors.SmplException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves SDK configuration using a 4-step algorithm:
 * <ol>
 *   <li>SDK hardcoded defaults</li>
 *   <li>Configuration file ({@code ~/.smplkit}): [common] + selected profile</li>
 *   <li>Environment variables ({@code SMPLKIT_*})</li>
 *   <li>Constructor/builder arguments</li>
 * </ol>
 */
final class ConfigResolver {

    /** Known config keys and their corresponding environment variables. */
    private static final Map<String, String> CONFIG_KEYS = Map.of(
            "api_key", "SMPLKIT_API_KEY",
            "base_domain", "SMPLKIT_BASE_DOMAIN",
            "scheme", "SMPLKIT_SCHEME",
            "environment", "SMPLKIT_ENVIRONMENT",
            "service", "SMPLKIT_SERVICE",
            "debug", "SMPLKIT_DEBUG",
            "disable_telemetry", "SMPLKIT_DISABLE_TELEMETRY"
    );

    private ConfigResolver() {
        // Utility class
    }

    /**
     * Fully resolved SDK configuration after the 4-step resolution.
     */
    static final class ResolvedConfig {
        final String apiKey;
        final String baseDomain;
        final String scheme;
        final String environment;
        final String service;
        final boolean debug;
        final boolean disableTelemetry;

        ResolvedConfig(String apiKey, String baseDomain, String scheme,
                       String environment, String service, boolean debug,
                       boolean disableTelemetry) {
            this.apiKey = apiKey;
            this.baseDomain = baseDomain;
            this.scheme = scheme;
            this.environment = environment;
            this.service = service;
            this.debug = debug;
            this.disableTelemetry = disableTelemetry;
        }
    }

    /**
     * Resolves configuration using real system environment and home directory.
     */
    static ResolvedConfig resolve(String profile, String apiKey, String baseDomain,
                                   String scheme, String environment, String service,
                                   Boolean debug, Boolean disableTelemetry) {
        return resolve(profile, apiKey, baseDomain, scheme, environment, service,
                debug, disableTelemetry,
                System.getenv("SMPLKIT_PROFILE"),
                key -> System.getenv(key),
                Paths.get(System.getProperty("user.home"), ".smplkit"));
    }

    /**
     * Injectable overload for testing — allows controlling env vars and file path.
     */
    static ResolvedConfig resolve(String profile, String apiKey, String baseDomain,
                                   String scheme, String environment, String service,
                                   Boolean debug, Boolean disableTelemetry,
                                   String envProfile, EnvLookup envLookup, Path configPath) {
        // Step 1: Hardcoded defaults
        String resolvedApiKey = null;
        String resolvedBaseDomain = "smplkit.com";
        String resolvedScheme = "https";
        String resolvedEnvironment = null;
        String resolvedService = null;
        boolean resolvedDebug = false;
        boolean resolvedDisableTelemetry = false;

        // Determine profile: constructor arg > SMPLKIT_PROFILE env > "default"
        String activeProfile = profile;
        if (activeProfile == null || activeProfile.isEmpty()) {
            activeProfile = envProfile;
        }
        if (activeProfile == null || activeProfile.isEmpty()) {
            activeProfile = "default";
        }

        // Step 2: Configuration file
        Map<String, String> fileValues = readConfigFile(activeProfile, configPath);
        if (fileValues.containsKey("api_key")) {
            resolvedApiKey = fileValues.get("api_key");
        }
        if (fileValues.containsKey("base_domain")) {
            resolvedBaseDomain = fileValues.get("base_domain");
        }
        if (fileValues.containsKey("scheme")) {
            resolvedScheme = fileValues.get("scheme");
        }
        if (fileValues.containsKey("environment")) {
            resolvedEnvironment = fileValues.get("environment");
        }
        if (fileValues.containsKey("service")) {
            resolvedService = fileValues.get("service");
        }
        if (fileValues.containsKey("debug")) {
            resolvedDebug = parseBool(fileValues.get("debug"), "debug");
        }
        if (fileValues.containsKey("disable_telemetry")) {
            resolvedDisableTelemetry = parseBool(fileValues.get("disable_telemetry"), "disable_telemetry");
        }

        // Step 3: Environment variables
        for (Map.Entry<String, String> entry : CONFIG_KEYS.entrySet()) {
            String key = entry.getKey();
            String envVar = entry.getValue();
            String envVal = envLookup.get(envVar);
            if (envVal != null && !envVal.isEmpty()) {
                switch (key) {
                    case "api_key" -> resolvedApiKey = envVal;
                    case "base_domain" -> resolvedBaseDomain = envVal;
                    case "scheme" -> resolvedScheme = envVal;
                    case "environment" -> resolvedEnvironment = envVal;
                    case "service" -> resolvedService = envVal;
                    case "debug" -> resolvedDebug = parseBool(envVal, envVar);
                    case "disable_telemetry" -> resolvedDisableTelemetry = parseBool(envVal, envVar);
                }
            }
        }

        // Step 4: Constructor arguments
        if (apiKey != null && !apiKey.isEmpty()) {
            resolvedApiKey = apiKey;
        }
        if (baseDomain != null && !baseDomain.isEmpty()) {
            resolvedBaseDomain = baseDomain;
        }
        if (scheme != null && !scheme.isEmpty()) {
            resolvedScheme = scheme;
        }
        if (environment != null && !environment.isEmpty()) {
            resolvedEnvironment = environment;
        }
        if (service != null && !service.isEmpty()) {
            resolvedService = service;
        }
        if (debug != null) {
            resolvedDebug = debug;
        }
        if (disableTelemetry != null) {
            resolvedDisableTelemetry = disableTelemetry;
        }

        // Validate required fields
        if (resolvedEnvironment == null || resolvedEnvironment.isEmpty()) {
            throw new SmplException(
                    "No environment provided. Set one of:\n" +
                    "  1. Call .environment() on the builder\n" +
                    "  2. Set the SMPLKIT_ENVIRONMENT environment variable\n" +
                    "  3. Add environment to the [" + activeProfile + "] section in ~/.smplkit",
                    0, null);
        }

        if (resolvedService == null || resolvedService.isEmpty()) {
            throw new SmplException(
                    "No service provided. Set one of:\n" +
                    "  1. Call .service() on the builder\n" +
                    "  2. Set the SMPLKIT_SERVICE environment variable\n" +
                    "  3. Add service to the [" + activeProfile + "] section in ~/.smplkit",
                    0, null);
        }

        if (resolvedApiKey == null || resolvedApiKey.isEmpty()) {
            throw new SmplException(
                    "No API key provided. Set one of:\n" +
                    "  1. Call .apiKey() on the builder or use SmplClient.create(apiKey)\n" +
                    "  2. Set the SMPLKIT_API_KEY environment variable\n" +
                    "  3. Add api_key to the [" + activeProfile + "] section in ~/.smplkit",
                    0, null);
        }

        return new ResolvedConfig(resolvedApiKey, resolvedBaseDomain, resolvedScheme,
                resolvedEnvironment, resolvedService, resolvedDebug, resolvedDisableTelemetry);
    }

    /**
     * Builds a service URL: {scheme}://{subdomain}.{baseDomain}.
     */
    static String serviceUrl(String scheme, String subdomain, String baseDomain) {
        return scheme + "://" + subdomain + "." + baseDomain;
    }

    /**
     * Parses a boolean string value (true/1/yes or false/0/no, case-insensitive).
     *
     * @throws SmplException for invalid values
     */
    static boolean parseBool(String value, String key) {
        String lower = value.strip().toLowerCase();
        if ("true".equals(lower) || "1".equals(lower) || "yes".equals(lower)) {
            return true;
        }
        if ("false".equals(lower) || "0".equals(lower) || "no".equals(lower)) {
            return false;
        }
        throw new SmplException(
                "Invalid boolean value for " + key + ": \"" + value + "\". " +
                "Expected one of: true, false, 1, 0, yes, no",
                0, null);
    }

    /**
     * Reads ~/.smplkit and returns merged [common] + profile values.
     */
    static Map<String, String> readConfigFile(String profile, Path configPath) {
        if (!Files.exists(configPath) || Files.isDirectory(configPath)) {
            return Map.of();
        }

        String content;
        try {
            content = Files.readString(configPath);
        } catch (IOException e) {
            return Map.of(); // Unreadable file — skip silently
        }

        return parseIniFile(content, profile);
    }

    /**
     * Parses INI content and returns merged [common] + profile values.
     */
    static Map<String, String> parseIniFile(String content, String profile) {
        Map<String, Map<String, String>> sections = parseIniSections(content);
        Map<String, String> values = new LinkedHashMap<>();

        // Step 1: Load [common] if it exists
        Map<String, String> common = sections.get("common");
        if (common != null) {
            values.putAll(common);
        }

        // Step 2: Overlay the selected profile section
        Map<String, String> profileSection = sections.get(profile.toLowerCase());
        if (profileSection == null) {
            // If the file has sections but NOT the requested profile, error
            // unless only [common] or no sections, or profile is "default"
            java.util.List<String> nonCommonSections = sections.keySet().stream()
                    .filter(s -> !"common".equals(s))
                    .toList();
            if (!nonCommonSections.isEmpty() && !"default".equals(profile)) {
                throw new SmplException(
                        "Profile [" + profile + "] not found in ~/.smplkit. " +
                        "Available profiles: " + String.join(", ", nonCommonSections),
                        0, null);
            }
        } else {
            values.putAll(profileSection);
        }

        return values;
    }

    /**
     * Parses raw INI content into a map of section name → key-value pairs.
     */
    private static Map<String, Map<String, String>> parseIniSections(String content) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String currentSection = null;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase();
                sections.putIfAbsent(currentSection, new LinkedHashMap<>());
                continue;
            }
            if (currentSection != null) {
                int eqIndex = trimmed.indexOf('=');
                if (eqIndex != -1) {
                    String key = trimmed.substring(0, eqIndex).trim();
                    String value = trimmed.substring(eqIndex + 1).trim();
                    if (!value.isEmpty()) {
                        sections.get(currentSection).put(key, value);
                    }
                }
            }
        }
        return sections;
    }

    /**
     * Functional interface for environment variable lookup (for testing).
     */
    @FunctionalInterface
    interface EnvLookup {
        String get(String key);
    }
}
