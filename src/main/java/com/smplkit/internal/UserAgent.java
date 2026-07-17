package com.smplkit.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the SDK's default {@code User-Agent} string once at class load.
 *
 * <p>The platform edge rejects requests that carry no User-Agent header, and
 * an SDK-identifying value lets access logs attribute traffic to a specific
 * SDK release. The version segment comes from {@code version.properties},
 * which Gradle expands at build time from the project version (CI passes the
 * release version via {@code -PprojectVersion}), so the published jar always
 * reports the real release number. When the resource is missing — e.g. an
 * exploded-classes run without processed resources — the version falls back
 * to {@value #FALLBACK_VERSION}.</p>
 */
public final class UserAgent {

    private UserAgent() {}

    /** Classpath location of the Gradle-expanded version properties resource. */
    static final String RESOURCE = "/com/smplkit/internal/version.properties";

    /** Version used when the resource is missing or unreadable. */
    static final String FALLBACK_VERSION = "dev";

    /** The SDK's default User-Agent, e.g. {@code smplkit-sdk-java/1.4.2}. */
    public static final String VALUE =
            "smplkit-sdk-java/" + loadVersion(UserAgent.class.getResourceAsStream(RESOURCE));

    /**
     * Reads the {@code version} property from the given stream, closing it
     * afterwards. Returns {@link #FALLBACK_VERSION} when the stream is
     * {@code null}, unreadable, or lacks the property.
     */
    static String loadVersion(InputStream stream) {
        if (stream == null) {
            return FALLBACK_VERSION;
        }
        try (InputStream in = stream) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", FALLBACK_VERSION);
        } catch (IOException e) {
            return FALLBACK_VERSION;
        }
    }
}
