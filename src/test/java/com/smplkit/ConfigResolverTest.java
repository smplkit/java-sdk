package com.smplkit;

import com.smplkit.errors.SmplError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigResolverTest {

    private static final ConfigResolver.EnvLookup NO_ENV = key -> null;

    // -----------------------------------------------------------------------
    // Helper: resolve with injectable overload
    // -----------------------------------------------------------------------

    private ConfigResolver.ResolvedConfig resolve(
            String profile, String apiKey, String baseDomain, String scheme,
            String environment, String service, Boolean debug, Boolean disableTelemetry,
            String envProfile, Map<String, String> envVars, Path configPath) {
        ConfigResolver.EnvLookup envLookup = envVars::get;
        return ConfigResolver.resolve(profile, apiKey, baseDomain, scheme, environment, service,
                debug, disableTelemetry, envProfile, envLookup, configPath);
    }

    private ConfigResolver.ResolvedConfig resolveWithAllSources(
            String profile, String apiKey, String baseDomain, String scheme,
            String environment, String service, Boolean debug, Boolean disableTelemetry,
            Map<String, String> envVars, Path configPath) {
        return resolve(profile, apiKey, baseDomain, scheme, environment, service,
                debug, disableTelemetry, null, envVars, configPath);
    }

    // -----------------------------------------------------------------------
    // Step 1: Defaults
    // -----------------------------------------------------------------------

    @Test
    void defaultsApplied(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_test\nenvironment = prod\nservice = svc\n");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile);

        assertEquals("smplkit.com", config.baseDomain);
        assertEquals("https", config.scheme);
        assertFalse(config.debug);
        assertFalse(config.disableTelemetry);
    }

    // -----------------------------------------------------------------------
    // Step 2: File profiles
    // -----------------------------------------------------------------------

    @Test
    void fileProfileLoadsValues(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_file\n" +
                "environment = file-env\n" +
                "service = file-svc\n" +
                "base_domain = custom.io\n" +
                "scheme = http\n" +
                "debug = true\n" +
                "disable_telemetry = yes\n");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile);

        assertEquals("sk_file", config.apiKey);
        assertEquals("file-env", config.environment);
        assertEquals("file-svc", config.service);
        assertEquals("custom.io", config.baseDomain);
        assertEquals("http", config.scheme);
        assertTrue(config.debug);
        assertTrue(config.disableTelemetry);
    }

    @Test
    void commonSectionInherited(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[common]\n" +
                "api_key = sk_common\n" +
                "base_domain = common.io\n" +
                "\n" +
                "[staging]\n" +
                "environment = staging\n" +
                "service = my-svc\n");

        ConfigResolver.ResolvedConfig config = resolve(
                "staging", null, null, null, null, null, null, null,
                null, Map.of(), configFile);

        assertEquals("sk_common", config.apiKey);
        assertEquals("common.io", config.baseDomain);
        assertEquals("staging", config.environment);
        assertEquals("my-svc", config.service);
    }

    @Test
    void profileOverridesCommon(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[common]\n" +
                "api_key = sk_common\n" +
                "base_domain = common.io\n" +
                "\n" +
                "[staging]\n" +
                "api_key = sk_staging\n" +
                "environment = staging\n" +
                "service = my-svc\n");

        ConfigResolver.ResolvedConfig config = resolve(
                "staging", null, null, null, null, null, null, null,
                null, Map.of(), configFile);

        assertEquals("sk_staging", config.apiKey);
        assertEquals("common.io", config.baseDomain);
    }

    @Test
    void missingProfileThrowsWhenOtherProfilesExist(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[production]\n" +
                "api_key = sk_prod\n" +
                "environment = production\n" +
                "service = svc\n");

        SmplError ex = assertThrows(SmplError.class, () -> resolve(
                "staging", null, null, null, null, null, null, null,
                null, Map.of(), configFile));
        assertTrue(ex.getMessage().contains("Profile [staging] not found"));
        assertTrue(ex.getMessage().contains("production"));
    }

    @Test
    void defaultProfileSilentlyMissingWhenOtherProfilesExist(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[production]\n" +
                "api_key = sk_prod\n" +
                "environment = production\n" +
                "service = svc\n");

        // "default" profile silently proceeds even when other profiles exist
        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_ENVIRONMENT", "env-env",
                "SMPLKIT_SERVICE", "env-svc");
        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);

        assertEquals("sk_env", config.apiKey);
    }

    @Test
    void envProfileSelectsProfile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[staging]\n" +
                "api_key = sk_staging\n" +
                "environment = staging\n" +
                "service = svc\n");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                "staging", Map.of(), configFile);

        assertEquals("sk_staging", config.apiKey);
    }

    @Test
    void constructorProfileOverridesEnvProfile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[staging]\n" +
                "api_key = sk_staging\n" +
                "environment = staging\n" +
                "service = svc\n" +
                "\n" +
                "[production]\n" +
                "api_key = sk_prod\n" +
                "environment = production\n" +
                "service = svc\n");

        ConfigResolver.ResolvedConfig config = resolve(
                "production", null, null, null, null, null, null, null,
                "staging", Map.of(), configFile);

        assertEquals("sk_prod", config.apiKey);
        assertEquals("production", config.environment);
    }

    // -----------------------------------------------------------------------
    // Step 3: Environment variable overrides
    // -----------------------------------------------------------------------

    @Test
    void envVarsOverrideFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_file\n" +
                "environment = file-env\n" +
                "service = file-svc\n" +
                "base_domain = file.io\n");

        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_ENVIRONMENT", "env-env",
                "SMPLKIT_SERVICE", "env-svc",
                "SMPLKIT_BASE_DOMAIN", "env.io");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);

        assertEquals("sk_env", config.apiKey);
        assertEquals("env-env", config.environment);
        assertEquals("env-svc", config.service);
        assertEquals("env.io", config.baseDomain);
    }

    @Test
    void envVarsWithoutFile(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit"); // does not exist

        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_ENVIRONMENT", "env-env",
                "SMPLKIT_SERVICE", "env-svc");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);

        assertEquals("sk_env", config.apiKey);
        assertEquals("env-env", config.environment);
        assertEquals("env-svc", config.service);
    }

    @Test
    void emptyEnvVarsTreatedAsUnset(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_file\n" +
                "environment = file-env\n" +
                "service = file-svc\n");

        Map<String, String> envVars = new HashMap<>();
        envVars.put("SMPLKIT_API_KEY", "");
        envVars.put("SMPLKIT_ENVIRONMENT", "");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);

        assertEquals("sk_file", config.apiKey);
        assertEquals("file-env", config.environment);
    }

    // -----------------------------------------------------------------------
    // Step 4: Constructor argument overrides
    // -----------------------------------------------------------------------

    @Test
    void constructorOverridesAll(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_file\n" +
                "environment = file-env\n" +
                "service = file-svc\n" +
                "base_domain = file.io\n" +
                "scheme = http\n" +
                "debug = true\n" +
                "disable_telemetry = true\n");

        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_ENVIRONMENT", "env-env",
                "SMPLKIT_SERVICE", "env-svc");

        ConfigResolver.ResolvedConfig config = resolve(
                null, "sk_ctor", "ctor.io", "https", "ctor-env", "ctor-svc",
                false, false, null, envVars, configFile);

        assertEquals("sk_ctor", config.apiKey);
        assertEquals("ctor.io", config.baseDomain);
        assertEquals("https", config.scheme);
        assertEquals("ctor-env", config.environment);
        assertEquals("ctor-svc", config.service);
        assertFalse(config.debug);
        assertFalse(config.disableTelemetry);
    }

    @Test
    void constructorPartialOverride(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_file\n" +
                "environment = file-env\n" +
                "service = file-svc\n");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, "ctor-env", null, null, null,
                null, Map.of(), configFile);

        assertEquals("sk_file", config.apiKey);
        assertEquals("ctor-env", config.environment);
        assertEquals("file-svc", config.service);
    }

    // -----------------------------------------------------------------------
    // Validation errors
    // -----------------------------------------------------------------------

    @Test
    void throwsWhenNoEnvironment(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_file\n" +
                "service = svc\n");

        SmplError ex = assertThrows(SmplError.class, () -> resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile));
        assertTrue(ex.getMessage().contains("No environment provided"));
        assertTrue(ex.getMessage().contains("SMPLKIT_ENVIRONMENT"));
        assertTrue(ex.getMessage().contains("[default]"));
    }

    @Test
    void throwsWhenNoService(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_file\n" +
                "environment = env\n");

        SmplError ex = assertThrows(SmplError.class, () -> resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile));
        assertTrue(ex.getMessage().contains("No service provided"));
        assertTrue(ex.getMessage().contains("SMPLKIT_SERVICE"));
    }

    @Test
    void throwsWhenNoApiKey(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "environment = env\n" +
                "service = svc\n");

        SmplError ex = assertThrows(SmplError.class, () -> resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile));
        assertTrue(ex.getMessage().contains("No API key provided"));
        assertTrue(ex.getMessage().contains("SMPLKIT_API_KEY"));
    }

    @Test
    void errorMessageShowsActiveProfile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[staging]\n" +
                "api_key = sk_staging\n");

        SmplError ex = assertThrows(SmplError.class, () -> resolve(
                "staging", null, null, null, null, null, null, null,
                null, Map.of(), configFile));
        assertTrue(ex.getMessage().contains("[staging]"));
    }

    @Test
    void throwsWhenNoKeyAnywhere(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit"); // does not exist
        SmplError ex = assertThrows(SmplError.class, () -> resolve(
                null, null, null, null, "env", "svc", null, null,
                null, Map.of(), configFile));
        assertTrue(ex.getMessage().contains("No API key provided"));
    }

    // -----------------------------------------------------------------------
    // Boolean parsing
    // -----------------------------------------------------------------------

    @Test
    void parseBoolTrueValues() {
        assertTrue(ConfigResolver.parseBool("true", "test"));
        assertTrue(ConfigResolver.parseBool("True", "test"));
        assertTrue(ConfigResolver.parseBool("TRUE", "test"));
        assertTrue(ConfigResolver.parseBool("1", "test"));
        assertTrue(ConfigResolver.parseBool("yes", "test"));
        assertTrue(ConfigResolver.parseBool("Yes", "test"));
        assertTrue(ConfigResolver.parseBool("YES", "test"));
    }

    @Test
    void parseBoolFalseValues() {
        assertFalse(ConfigResolver.parseBool("false", "test"));
        assertFalse(ConfigResolver.parseBool("False", "test"));
        assertFalse(ConfigResolver.parseBool("FALSE", "test"));
        assertFalse(ConfigResolver.parseBool("0", "test"));
        assertFalse(ConfigResolver.parseBool("no", "test"));
        assertFalse(ConfigResolver.parseBool("No", "test"));
        assertFalse(ConfigResolver.parseBool("NO", "test"));
    }

    @Test
    void parseBoolInvalidThrows() {
        SmplError ex = assertThrows(SmplError.class,
                () -> ConfigResolver.parseBool("maybe", "debug"));
        assertTrue(ex.getMessage().contains("Invalid boolean value"));
        assertTrue(ex.getMessage().contains("maybe"));
    }

    @Test
    void parseBoolWithWhitespace() {
        assertTrue(ConfigResolver.parseBool("  true  ", "test"));
        assertFalse(ConfigResolver.parseBool("  false  ", "test"));
    }

    @Test
    void boolFromFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_test\n" +
                "environment = env\n" +
                "service = svc\n" +
                "debug = 1\n" +
                "disable_telemetry = yes\n");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile);
        assertTrue(config.debug);
        assertTrue(config.disableTelemetry);
    }

    @Test
    void boolFromEnv(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit"); // does not exist
        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_test",
                "SMPLKIT_ENVIRONMENT", "env",
                "SMPLKIT_SERVICE", "svc",
                "SMPLKIT_DEBUG", "yes",
                "SMPLKIT_DISABLE_TELEMETRY", "1");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);
        assertTrue(config.debug);
        assertTrue(config.disableTelemetry);
    }

    @Test
    void invalidBoolInFileThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key = sk_test\n" +
                "environment = env\n" +
                "service = svc\n" +
                "debug = maybe\n");

        assertThrows(SmplError.class, () -> resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile));
    }

    // -----------------------------------------------------------------------
    // serviceUrl helper
    // -----------------------------------------------------------------------

    @Test
    void serviceUrlConstructed() {
        assertEquals("https://config.smplkit.com",
                ConfigResolver.serviceUrl("https", "config", "smplkit.com"));
        assertEquals("http://flags.localhost",
                ConfigResolver.serviceUrl("http", "flags", "localhost"));
    }

    // -----------------------------------------------------------------------
    // File edge cases
    // -----------------------------------------------------------------------

    @Test
    void missingFileUsesOtherSources(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit"); // does not exist

        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_ENVIRONMENT", "env",
                "SMPLKIT_SERVICE", "svc");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);
        assertEquals("sk_env", config.apiKey);
    }

    @Test
    void directoryInsteadOfFileSkipped(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.createDirectory(configFile);

        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_ENVIRONMENT", "env",
                "SMPLKIT_SERVICE", "svc");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);
        assertEquals("sk_env", config.apiKey);
    }

    @Test
    void commentsAndEmptyLinesIgnored(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "# top comment\n" +
                "\n" +
                "[default]\n" +
                "# comment in section\n" +
                "; semicolon comment\n" +
                "api_key = sk_comment\n" +
                "\n" +
                "environment = env\n" +
                "service = svc\n");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile);
        assertEquals("sk_comment", config.apiKey);
    }

    @Test
    void sectionNamesCaseInsensitive(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[Default]\n" +
                "api_key = sk_case\n" +
                "environment = env\n" +
                "service = svc\n");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile);
        assertEquals("sk_case", config.apiKey);
    }

    @Test
    void emptyValuesInFileIgnored(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\n" +
                "api_key =\n" +
                "environment = env\n" +
                "service = svc\n");

        // api_key is empty in file, so should fail since no other source
        SmplError ex = assertThrows(SmplError.class, () -> resolve(
                null, null, null, null, null, null, null, null,
                null, Map.of(), configFile));
        assertTrue(ex.getMessage().contains("No API key provided"));
    }

    // -----------------------------------------------------------------------
    // Full resolution chain
    // -----------------------------------------------------------------------

    @Test
    void fullChainDefaultsFileEnvConstructor(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[common]\n" +
                "base_domain = common.io\n" +
                "debug = true\n" +
                "\n" +
                "[default]\n" +
                "api_key = sk_file\n" +
                "environment = file-env\n" +
                "service = file-svc\n" +
                "scheme = http\n");

        Map<String, String> envVars = Map.of(
                "SMPLKIT_ENVIRONMENT", "env-env");

        // Constructor overrides service
        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, "ctor-svc", null, null,
                null, envVars, configFile);

        // base_domain from common
        assertEquals("common.io", config.baseDomain);
        // scheme from file
        assertEquals("http", config.scheme);
        // api_key from file
        assertEquals("sk_file", config.apiKey);
        // environment from env (overrides file)
        assertEquals("env-env", config.environment);
        // service from constructor (overrides file)
        assertEquals("ctor-svc", config.service);
        // debug from common
        assertTrue(config.debug);
    }

    // -----------------------------------------------------------------------
    // parseIniFile
    // -----------------------------------------------------------------------

    @Test
    void parseIniFile_multipleSections() {
        String content =
                "[common]\n" +
                "api_key = sk_common\n" +
                "\n" +
                "[production]\n" +
                "environment = production\n" +
                "service = prod-svc\n" +
                "\n" +
                "[staging]\n" +
                "environment = staging\n" +
                "service = staging-svc\n";

        Map<String, String> values = ConfigResolver.parseIniFile(content, "staging");
        assertEquals("sk_common", values.get("api_key"));
        assertEquals("staging", values.get("environment"));
        assertEquals("staging-svc", values.get("service"));
    }

    @Test
    void parseIniFile_keysWithSpacesAroundEquals() {
        String content = "[default]\napi_key  =  sk_spaced  \nenvironment = env\nservice = svc\n";
        Map<String, String> values = ConfigResolver.parseIniFile(content, "default");
        assertEquals("sk_spaced", values.get("api_key"));
    }

    // -----------------------------------------------------------------------
    // Builder integration
    // -----------------------------------------------------------------------

    @Test
    void builderCreatesClient() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("sk_test")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
            assertEquals("test", client.environment());
            assertEquals("test-service", client.service());
        }
    }

    @Test
    void builderWithProfile() {
        // Use "default" profile which is always safe (silently proceeds if missing)
        try (SmplClient client = SmplClient.builder()
                .profile("default")
                .apiKey("sk_test")
                .environment("staging")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
            assertEquals("staging", client.environment());
        }
    }

    @Test
    void builderWithBaseDomainAndScheme() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("sk_test")
                .environment("test")
                .service("test-service")
                .baseDomain("custom.io")
                .scheme("http")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void builderWithDebug() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("sk_test")
                .environment("test")
                .service("test-service")
                .debug(true)
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void builderRejectsNullProfile() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().profile(null));
    }

    @Test
    void builderRejectsNullBaseDomain() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().baseDomain(null));
    }

    @Test
    void builderRejectsNullScheme() {
        assertThrows(NullPointerException.class, () ->
                SmplClient.builder().scheme(null));
    }

    // -----------------------------------------------------------------------
    // All env var branches
    // -----------------------------------------------------------------------

    @Test
    void allEnvVarsOverride(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit"); // does not exist

        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_ENVIRONMENT", "env-env",
                "SMPLKIT_SERVICE", "env-svc",
                "SMPLKIT_BASE_DOMAIN", "env.io",
                "SMPLKIT_SCHEME", "http",
                "SMPLKIT_DEBUG", "true",
                "SMPLKIT_DISABLE_TELEMETRY", "1");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);

        assertEquals("sk_env", config.apiKey);
        assertEquals("env-env", config.environment);
        assertEquals("env-svc", config.service);
        assertEquals("env.io", config.baseDomain);
        assertEquals("http", config.scheme);
        assertTrue(config.debug);
        assertTrue(config.disableTelemetry);
    }

    // -----------------------------------------------------------------------
    // readConfigFile IOException path
    // -----------------------------------------------------------------------

    @Test
    void unreadableFileSkipped(@TempDir Path tempDir) throws IOException {
        // Create a file then make it unreadable
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_test\n");
        configFile.toFile().setReadable(false);

        Map<String, String> envVars = Map.of(
                "SMPLKIT_API_KEY", "sk_env",
                "SMPLKIT_ENVIRONMENT", "env",
                "SMPLKIT_SERVICE", "svc");

        ConfigResolver.ResolvedConfig config = resolve(
                null, null, null, null, null, null, null, null,
                null, envVars, configFile);
        assertEquals("sk_env", config.apiKey);

        // Restore permissions for cleanup
        configFile.toFile().setReadable(true);
    }

    // -----------------------------------------------------------------------
    // Production resolve() overload
    // -----------------------------------------------------------------------

    @Test
    void productionResolveOverloadCovered() {
        // The production overload is called via builder.build(), which
        // reads from real System.getenv and home dir. Exercise it.
        try (SmplClient client = SmplClient.builder()
                .apiKey("sk_test")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build()) {
            assertNotNull(client);
        }
    }
}
