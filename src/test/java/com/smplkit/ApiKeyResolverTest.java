package com.smplkit;

import com.smplkit.errors.SmplException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyResolverTest {

    @Test
    void explicitKeyReturned() {
        assertEquals("sk_api_explicit", ApiKeyResolver.resolve("sk_api_explicit", "production", null, Path.of("/nonexistent")));
    }

    @Test
    void envVarUsedWhenNoExplicit() {
        assertEquals("sk_api_env", ApiKeyResolver.resolve(null, "production", "sk_api_env", Path.of("/nonexistent")));
    }

    @Test
    void configFileUsedWhenNoExplicitNoEnv(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_api_file\n");
        assertEquals("sk_api_file", ApiKeyResolver.resolve(null, "production", null, configFile));
    }

    @Test
    void throwsWhenNoKeyAnywhere(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit");
        SmplException ex = assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, "production", null, configFile));
        assertTrue(ex.getMessage().contains("No API key provided"));
    }

    @Test
    void errorMessageListsAllMethods(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit");
        SmplException ex = assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, "staging", null, configFile));
        assertTrue(ex.getMessage().contains(".apiKey()"));
        assertTrue(ex.getMessage().contains("SMPLKIT_API_KEY"));
        assertTrue(ex.getMessage().contains("~/.smplkit"));
        // Error message should show the resolved environment name
        assertTrue(ex.getMessage().contains("[staging]"));
    }

    @Test
    void explicitTakesPrecedenceOverEnv() {
        assertEquals("sk_api_explicit", ApiKeyResolver.resolve("sk_api_explicit", "production", "sk_api_env", Path.of("/nonexistent")));
    }

    @Test
    void envTakesPrecedenceOverFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_api_file\n");
        assertEquals("sk_api_env", ApiKeyResolver.resolve(null, "production", "sk_api_env", configFile));
    }

    @Test
    void emptyEnvVarTreatedAsUnset(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_api_file\n");
        assertEquals("sk_api_file", ApiKeyResolver.resolve(null, "production", "", configFile));
    }

    @Test
    void malformedFileIsSkipped(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "not valid ini");
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, "production", null, configFile));
    }

    @Test
    void fileWithoutApiKeyThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\nother_key = value\n");
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, "production", null, configFile));
    }

    @Test
    void commentsAreIgnored(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "# This is a comment\n[default]\n# another comment\napi_key = sk_api_comment\n");
        assertEquals("sk_api_comment", ApiKeyResolver.resolve(null, "production", null, configFile));
    }

    @Test
    void missingDefaultSectionThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[staging]\napi_key = sk_api_staging\n");
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, "production", null, configFile));
    }

    @Test
    void environmentSectionTakesPrecedenceOverDefault(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile,
                "[default]\napi_key = sk_api_default\n\n[staging]\napi_key = sk_api_staging\n");
        assertEquals("sk_api_staging", ApiKeyResolver.resolve(null, "staging", null, configFile));
    }

    @Test
    void fallsBackToDefaultWhenEnvironmentSectionMissing(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_api_default\n");
        assertEquals("sk_api_default", ApiKeyResolver.resolve(null, "staging", null, configFile));
    }

    @Test
    void defaultSectionWithoutApiKeyThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\nsome_other = value\n");
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, "production", null, configFile));
    }

    @Test
    void emptyExplicitFallsThrough() {
        assertEquals("sk_api_env", ApiKeyResolver.resolve("", "production", "sk_api_env", Path.of("/nonexistent")));
    }

    @Test
    void createFactoryWithApiKey() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("sk_api_test")
                .environment("test")
                .service("test-service")
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void ioExceptionDuringFileRead(@TempDir Path tempDir) throws IOException {
        // Create a directory where we expect a file, to trigger an IOException
        // when trying to read it (reading a directory as a file throws IOException)
        Path configFile = tempDir.resolve(".smplkit");
        Files.createDirectory(configFile);
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, "production", null, configFile));
    }
}
