package com.smplkit.logging;

import com.smplkit.internal.Debug;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Computes a logger's effective level from its own level, its environment
 * overrides, its group chain, and dot-notation ancestry.
 */
final class Resolution {

    private static final String FALLBACK_LEVEL = "INFO";

    private Resolution() {}

    /**
     * Resolve the effective log level for a logger in an environment.
     *
     * <p>Resolution chain (first non-null wins):</p>
     * <ol>
     *   <li>Logger's own {@code environments[env].level}</li>
     *   <li>Logger's own {@code level}</li>
     *   <li>Group chain (recursive: group's env level -&gt; group's level -&gt; parent group...)</li>
     *   <li>Dot-notation ancestry (walk {@code com.acme.payments} -&gt; {@code com.acme} -&gt; {@code com},
     *       applying steps 1-3 at each)</li>
     *   <li>System fallback: {@code "INFO"}</li>
     * </ol>
     *
     * @param loggerId    The normalized logger id (slug).
     * @param environment The current environment name.
     * @param loggers     Map of all loggers keyed by {@code id}.
     * @param groups      Map of all log groups keyed by {@code id}.
     * @return The resolved smplkit level string.
     */
    static String resolveLevel(String loggerId, String environment,
                               Map<String, Map<String, Object>> loggers,
                               Map<String, Map<String, Object>> groups) {
        String result = resolveForEntry(loggerId, environment, loggers, groups);
        if (result != null) {
            Debug.log("resolution", loggerId + " -> " + result
                    + " (source: " + findResolutionSource(loggerId, environment, loggers, groups) + ")");
            return result;
        }

        String[] parts = loggerId.split("\\.");
        for (int i = parts.length - 1; i > 0; i--) {
            String ancestorId = joinParts(parts, i);
            result = resolveForEntry(ancestorId, environment, loggers, groups);
            if (result != null) {
                Debug.log("resolution", loggerId + " -> " + result
                        + " (source: ancestor \"" + ancestorId + "\")");
                return result;
            }
        }

        Debug.log("resolution", loggerId + " -> " + FALLBACK_LEVEL + " (source: system default)");
        return FALLBACK_LEVEL;
    }

    /**
     * Return a human-readable string describing which resolution step produced the level.
     *
     * <p>Only called when debug is enabled — re-runs the same checks as
     * {@code resolveForEntry} to identify the winning step.</p>
     */
    static String findResolutionSource(String loggerId, String environment,
                                       Map<String, Map<String, Object>> loggers,
                                       Map<String, Map<String, Object>> groups) {
        Map<String, Object> entry = loggers.get(loggerId);
        if (entry == null) return "not found";

        String envLevel = extractEnvLevel(entry, environment);
        if (envLevel != null) return "env override \"" + environment + "\"";

        Object base = entry.get("level");
        if (base != null) return "base level";

        String groupId = (String) entry.get("group");
        String result = resolveGroupChain(groupId, environment, groups);
        if (result != null) return "group \"" + groupId + "\"";

        return "unknown";
    }

    private static String resolveForEntry(String key, String env,
                                          Map<String, Map<String, Object>> loggers,
                                          Map<String, Map<String, Object>> groups) {
        Map<String, Object> entry = loggers.get(key);
        if (entry == null) return null;

        String envLevel = extractEnvLevel(entry, env);
        if (envLevel != null) return envLevel;

        Object base = entry.get("level");
        if (base instanceof String s) return s;

        String groupId = (String) entry.get("group");
        return resolveGroupChain(groupId, env, groups);
    }

    private static String resolveGroupChain(String groupId, String env,
                                            Map<String, Map<String, Object>> groups) {
        Set<String> visited = new HashSet<>();
        String currentId = groupId;
        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            Map<String, Object> group = groups.get(currentId);
            if (group == null) break;

            String envLevel = extractEnvLevel(group, env);
            if (envLevel != null) return envLevel;

            Object base = group.get("level");
            if (base instanceof String s) return s;

            currentId = (String) group.get("group");
        }
        return null;
    }

    /**
     * Extract the environment-specific level from an entry, if present.
     *
     * <p>With no environment (server derives it from the API key) there is no
     * env-scoped override to apply, so resolution falls through to the entry's
     * base level / group chain / system default.</p>
     */
    @SuppressWarnings("unchecked")
    private static String extractEnvLevel(Map<String, Object> entry, String env) {
        if (env == null) {
            return null;
        }
        Object envs = entry.get("environments");
        if (envs instanceof Map<?, ?> envMap) {
            Object envData = ((Map<String, Object>) envMap).get(env);
            if (envData instanceof Map<?, ?> dataMap) {
                Object level = ((Map<String, Object>) dataMap).get("level");
                if (level instanceof String s) return s;
            }
        }
        return null;
    }

    private static String joinParts(String[] parts, int upTo) {
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int j = 1; j < upTo; j++) {
            sb.append('.').append(parts[j]);
        }
        return sb.toString();
    }
}
