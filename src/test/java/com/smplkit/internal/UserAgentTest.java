package com.smplkit.internal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAgentTest {

    @Test
    void valueHasSdkPrefixAndNonEmptyVersion() {
        assertTrue(UserAgent.VALUE.startsWith("smplkit-sdk-java/"));
        String version = UserAgent.VALUE.substring("smplkit-sdk-java/".length());
        assertFalse(version.isBlank());
        assertFalse(version.contains(" "));
        // Would indicate the Gradle expand step didn't run on the resource.
        assertFalse(version.contains("${"));
    }

    @Test
    void valueUsesTheEmbeddedVersionResource() {
        // The build expands version.properties onto the classpath; VALUE must
        // match a fresh read of that same resource, and the resource must be
        // present (the "dev" fallback is only for resource-less classpaths).
        String version = UserAgent.loadVersion(UserAgent.class.getResourceAsStream(UserAgent.RESOURCE));
        assertEquals("smplkit-sdk-java/" + version, UserAgent.VALUE);
        assertNotEquals(UserAgent.FALLBACK_VERSION, version,
                "version.properties must be present and expanded on the test classpath");
    }

    @Test
    void loadVersion_readsVersionProperty() {
        InputStream in = new ByteArrayInputStream(
                "version=1.2.3\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("1.2.3", UserAgent.loadVersion(in));
    }

    @Test
    void loadVersion_nullStreamFallsBack() {
        assertEquals(UserAgent.FALLBACK_VERSION, UserAgent.loadVersion(null));
    }

    @Test
    void loadVersion_missingKeyFallsBack() {
        InputStream in = new ByteArrayInputStream(
                "other=value\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(UserAgent.FALLBACK_VERSION, UserAgent.loadVersion(in));
    }

    @Test
    void loadVersion_unreadableStreamFallsBack() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };
        assertEquals(UserAgent.FALLBACK_VERSION, UserAgent.loadVersion(broken));
    }
}
