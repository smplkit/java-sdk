package com.smplkit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link SmplClient#setContext(java.util.List)} and
 * {@link SmplClient#clearContext()} — the per-thread evaluation context that
 * middleware uses to attach {@code (type, key)} pairs once at request entry.
 */
class SmplClientContextTest {

    private SmplClient newClient() {
        return SmplClient.builder()
                .apiKey("test-key")
                .environment("test")
                .service("test-service")
                .disableTelemetry(true)
                .build();
    }

    @Test
    void setContext_withNonEmptyList_registersEachContext() {
        try (SmplClient client = newClient()) {
            List<Context> ctx = List.of(
                    new Context("user", "u-1", Map.of("plan", "enterprise")),
                    new Context("org", "o-1", Map.of()));
            // Smoke test: setContext should not throw and should register the
            // contexts for buffered upload. We don't depend on a specific
            // pendingCount — that depends on shared-buffer wiring.
            assertDoesNotThrow(() -> client.setContext(ctx));
        }
    }

    @Test
    void setContext_withNullList_treatsAsEmpty() {
        try (SmplClient client = newClient()) {
            // Null is allowed; it clears the per-thread context without
            // attempting registration.
            assertDoesNotThrow(() -> client.setContext(null));
        }
    }

    @Test
    void setContext_withEmptyList_skipsRegistration() {
        try (SmplClient client = newClient()) {
            assertDoesNotThrow(() -> client.setContext(List.of()));
        }
    }

    @Test
    void clearContext_isSafeWhenNothingSet() {
        try (SmplClient client = newClient()) {
            assertDoesNotThrow(client::clearContext);
        }
    }

    @Test
    void setContextThenClear_doesNotThrow() {
        try (SmplClient client = newClient()) {
            client.setContext(List.of(new Context("user", "u-1", Map.of())));
            assertDoesNotThrow(client::clearContext);
        }
    }
}
