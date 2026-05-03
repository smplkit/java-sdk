/*
 * Demonstrates the smplkit runtime SDK for Smpl Logging.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.LoggingRuntimeShowcase
 */
package com.smplkit.examples;

import com.smplkit.SmplClient;

public final class LoggingRuntimeShowcase {

    public static void main(String[] args) {
        // create the client (use SmplClient for synchronous use)
        try (SmplClient client = SmplClient.builder()
                .environment("production").service("showcase-service").build()) {
            client.logging().install();
            System.out.println("All loggers are now controlled by smplkit");
        }
    }
}
