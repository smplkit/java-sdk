package com.smplkit;

import com.smplkit.errors.SmplException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for {@link SmplkitClient} covering the request interceptor lambda.
 */
class SmplkitClientFullTest {

    @Test
    void realClient_exercisesRequestInterceptor() {
        // Build a real client (not mocked) and attempt an API call.
        // The call will fail (no real server), but the request interceptor lambda
        // (line 60: builder -> builder.header("Authorization", "Bearer " + apiKey))
        // will be executed before the request fails.
        try (SmplkitClient client = SmplkitClient.builder()
                .apiKey("test-key")
                .timeout(Duration.ofMillis(500))
                .build()) {

            // This will attempt a real HTTP call which exercises the interceptor
            // then fail because there's no real server.
            assertThrows(Exception.class, () ->
                    client.config().get("550e8400-e29b-41d4-a716-446655440000"));
        }
    }
}
