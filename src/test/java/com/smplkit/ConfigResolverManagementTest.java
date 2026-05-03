package com.smplkit;

import com.smplkit.errors.SmplError;
import com.smplkit.management.AsyncSmplManagementClient;
import com.smplkit.management.SmplManagementClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the management-only resolution path
 * ({@link ConfigResolver#resolveManagement}) and the
 * {@link ConfigResolver.ResolvedConfig#toManagement} projection. Also exercises
 * the no-arg static factories on {@link SmplManagementClient} and
 * {@link AsyncSmplManagementClient} via env-var injection.
 */
class ConfigResolverManagementTest {

    private ConfigResolver.ResolvedManagementConfig resolveMgmt(
            String profile, String apiKey, String baseDomain, String scheme,
            Boolean debug, Map<String, String> envVars, Path configPath) {
        return ConfigResolver.resolveManagement(profile, apiKey, baseDomain, scheme, debug,
                null, envVars::get, configPath);
    }

    // ---------------------------------------------------------- env-var overrides

    @Test
    void resolveManagement_envVarsOverrideFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".smplkit");
        Files.writeString(file, "[default]\napi_key = sk_file\nbase_domain = file.io\nscheme = http\ndebug = false\n");

        Map<String, String> env = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_BASE_DOMAIN", "env.io",
                "SMPLKIT_SCHEME", "https",
                "SMPLKIT_DEBUG", "true");

        ConfigResolver.ResolvedManagementConfig cfg = resolveMgmt(
                null, null, null, null, null, env, file);

        assertEquals("sk_env", cfg.apiKey);
        assertEquals("env.io", cfg.baseDomain);
        assertEquals("https", cfg.scheme);
        assertTrue(cfg.debug);
    }

    @Test
    void resolveManagement_runtimeOnlyEnvVarsIgnored(@TempDir Path tempDir) throws IOException {
        // SMPLKIT_ENVIRONMENT, SMPLKIT_SERVICE, SMPLKIT_DISABLE_TELEMETRY are
        // runtime-only; resolveManagement() drops them via the default branch.
        Path file = tempDir.resolve(".smplkit");
        Files.writeString(file, "[default]\napi_key = sk_file\n");

        Map<String, String> env = Map.of(
                "SMPLKIT_ENVIRONMENT", "irrelevant",
                "SMPLKIT_SERVICE", "irrelevant",
                "SMPLKIT_DISABLE_TELEMETRY", "1");

        ConfigResolver.ResolvedManagementConfig cfg = resolveMgmt(
                null, null, null, null, null, env, file);

        assertEquals("sk_file", cfg.apiKey);
    }

    @Test
    void resolveManagement_throwsWhenNoApiKeyAnywhere(@TempDir Path tempDir) {
        Path file = tempDir.resolve(".smplkit"); // does not exist
        SmplError ex = assertThrows(SmplError.class, () -> resolveMgmt(
                null, null, null, null, null, Map.of(), file));
        assertTrue(ex.getMessage().contains("No API key provided"));
        assertTrue(ex.getMessage().contains("SmplManagementClient.create(apiKey)"));
    }

    // ---------------------------------------------------------- toManagement projection

    @Test
    void resolvedConfig_toManagement_dropsRuntimeFields() {
        ConfigResolver.ResolvedConfig full = ConfigResolver.resolve(
                null, "sk_test", "custom.io", "http", "test-env", "test-svc",
                true, true,
                null, key -> null, java.nio.file.Paths.get("/nonexistent"));

        ConfigResolver.ResolvedManagementConfig mgmt = full.toManagement();
        assertEquals("sk_test", mgmt.apiKey);
        assertEquals("custom.io", mgmt.baseDomain);
        assertEquals("http", mgmt.scheme);
        assertTrue(mgmt.debug);
    }

    // ---------------------------------------------------------- no-arg factories

    @Test
    void smplManagementClient_create_noArg_resolvesFromEnv() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_create_test");
        try {
            try (SmplManagementClient mc = SmplManagementClient.create()) {
                assertEquals("sk_create_test", mc.apiKey());
            }
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void asyncSmplManagementClient_create_noArg_resolvesFromEnv() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_create_async");
        try {
            try (AsyncSmplManagementClient mc = AsyncSmplManagementClient.create()) {
                assertEquals("sk_create_async", mc.sync().apiKey());
            }
        } finally {
            clearEnv("SMPLKIT_API_KEY");
        }
    }

    @Test
    void asyncSmplClient_create_noArg_resolvesFromEnv() throws Exception {
        setEnv("SMPLKIT_API_KEY", "sk_runtime_create");
        setEnv("SMPLKIT_ENVIRONMENT", "test");
        setEnv("SMPLKIT_SERVICE", "svc");
        try {
            try (AsyncSmplClient async = AsyncSmplClient.create()) {
                assertNotNull(async.sync());
                assertEquals("test", async.sync().environment());
            }
        } finally {
            clearEnv("SMPLKIT_API_KEY");
            clearEnv("SMPLKIT_ENVIRONMENT");
            clearEnv("SMPLKIT_SERVICE");
        }
    }

    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((Map<String, String>) field.get(env)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearEnv(String key) throws Exception {
        var env = System.getenv();
        var field = env.getClass().getDeclaredField("m");
        field.setAccessible(true);
        ((Map<String, String>) field.get(env)).remove(key);
    }
}
