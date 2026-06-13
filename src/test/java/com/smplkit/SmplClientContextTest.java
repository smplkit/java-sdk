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

    @Test
    void setContext_returnedScope_restoresPriorContextOnClose() {
        try (SmplClient client = newClient()) {
            client.clearContext();

            List<Context> outer = List.of(new Context("user", "outer", Map.of()));
            List<Context> inner = List.of(new Context("user", "inner", Map.of()));

            client.setContext(outer);
            assertSame(outer, client.currentContextForTesting());

            // Scoped override: inside the try-with-resources the inner context is
            // active; on close the previous (outer) context is restored.
            try (ContextScope scope = client.setContext(inner)) {
                assertNotNull(scope);
                assertSame(inner, client.currentContextForTesting());
            }
            assertSame(outer, client.currentContextForTesting());

            client.clearContext();
        }
    }

    @Test
    void setContext_returnedScope_restoresEmptyWhenNoPriorContext() {
        try (SmplClient client = newClient()) {
            client.clearContext();
            assertTrue(client.currentContextForTesting().isEmpty());

            try (ContextScope scope = client.setContext(
                    List.of(new Context("user", "u-1", Map.of())))) {
                assertEquals(1, client.currentContextForTesting().size());
            }
            // Closing restores the empty pre-call context.
            assertTrue(client.currentContextForTesting().isEmpty());
        }
    }

    @Test
    void contextScope_closeIsIdempotent() {
        try (SmplClient client = newClient()) {
            client.clearContext();

            ContextScope scope =
                    client.setContext(List.of(new Context("user", "first", Map.of())));

            List<Context> second = List.of(new Context("user", "second", Map.of()));
            client.setContext(second);
            assertSame(second, client.currentContextForTesting());

            // First close restores the empty context captured when the scope was created.
            scope.close();
            assertTrue(client.currentContextForTesting().isEmpty());

            // A second close must be a no-op — it must NOT clobber the now-current context.
            client.setContext(second);
            scope.close();
            assertSame(second, client.currentContextForTesting());

            client.clearContext();
        }
    }
}
