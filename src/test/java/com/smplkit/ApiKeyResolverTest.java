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
        assertEquals("sk_api_explicit", ApiKeyResolver.resolve("sk_api_explicit", null, Path.of("/nonexistent")));
    }

    @Test
    void envVarUsedWhenNoExplicit() {
        assertEquals("sk_api_env", ApiKeyResolver.resolve(null, "sk_api_env", Path.of("/nonexistent")));
    }

    @Test
    void configFileUsedWhenNoExplicitNoEnv(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_api_file\n");
        assertEquals("sk_api_file", ApiKeyResolver.resolve(null, null, configFile));
    }

    @Test
    void throwsWhenNoKeyAnywhere(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit");
        SmplException ex = assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, null, configFile));
        assertTrue(ex.getMessage().contains("No API key provided"));
    }

    @Test
    void errorMessageListsAllMethods(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve(".smplkit");
        SmplException ex = assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, null, configFile));
        assertTrue(ex.getMessage().contains(".apiKey()"));
        assertTrue(ex.getMessage().contains("SMPLKIT_API_KEY"));
        assertTrue(ex.getMessage().contains("~/.smplkit"));
    }

    @Test
    void explicitTakesPrecedenceOverEnv() {
        assertEquals("sk_api_explicit", ApiKeyResolver.resolve("sk_api_explicit", "sk_api_env", Path.of("/nonexistent")));
    }

    @Test
    void envTakesPrecedenceOverFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_api_file\n");
        assertEquals("sk_api_env", ApiKeyResolver.resolve(null, "sk_api_env", configFile));
    }

    @Test
    void emptyEnvVarTreatedAsUnset(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\napi_key = sk_api_file\n");
        assertEquals("sk_api_file", ApiKeyResolver.resolve(null, "", configFile));
    }

    @Test
    void malformedFileIsSkipped(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "not valid ini");
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, null, configFile));
    }

    @Test
    void fileWithoutApiKeyThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\nother_key = value\n");
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, null, configFile));
    }

    @Test
    void commentsAreIgnored(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "# This is a comment\n[default]\n# another comment\napi_key = sk_api_comment\n");
        assertEquals("sk_api_comment", ApiKeyResolver.resolve(null, null, configFile));
    }

    @Test
    void missingDefaultSectionThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[staging]\napi_key = sk_api_staging\n");
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, null, configFile));
    }

    @Test
    void defaultSectionWithoutApiKeyThrows(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve(".smplkit");
        Files.writeString(configFile, "[default]\nsome_other = value\n");
        assertThrows(SmplException.class,
                () -> ApiKeyResolver.resolve(null, null, configFile));
    }

    @Test
    void emptyExplicitFallsThrough() {
        assertEquals("sk_api_env", ApiKeyResolver.resolve("", "sk_api_env", Path.of("/nonexistent")));
    }

    @Test
    void createFactoryWithApiKey() {
        try (SmplClient client = SmplClient.builder()
                .apiKey("sk_api_test")
                .environment("test")
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
                () -> ApiKeyResolver.resolve(null, null, configFile));
    }
}
