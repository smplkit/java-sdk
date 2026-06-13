package com.smplkit;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Suite-wide test-hygiene guard: before every unit test, neutralize the
 * {@link SharedWebSocket} dial so no test opens a real network/WebSocket
 * connection (the lazy-auto-connect clients would otherwise dial on first use).
 *
 * <p>Auto-registered via {@code META-INF/services} with JUnit extension
 * autodetection enabled. The dedicated WebSocket tests reset
 * {@link SharedWebSocket#disableDialForTests} to {@code false} in their own
 * {@code @BeforeEach} (which runs after this callback) so they still exercise
 * the real connect/reconnect machinery against an injected fake connector.</p>
 */
public final class WsFreeTestExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        // The SharedWebSocket* tests drive the real connect/reconnect machinery
        // against an injected fake connector (or a refused localhost port), so
        // they opt out; every other test runs with the dial neutralized.
        String simpleName = context.getRequiredTestClass().getSimpleName();
        boolean wsMachineryTest = simpleName.startsWith("SharedWebSocket");
        SharedWebSocket.disableDialForTests = !wsMachineryTest;
    }
}
