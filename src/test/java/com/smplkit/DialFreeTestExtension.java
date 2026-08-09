package com.smplkit;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Suite-wide test-hygiene guard: before every unit test, neutralize the
 * {@link EventStream} dial so no test opens a real network connection (the
 * lazy-auto-connect clients would otherwise dial on first use).
 *
 * <p>Auto-registered via {@code META-INF/services} with JUnit extension
 * autodetection enabled. The dedicated EventStream tests reset
 * {@link EventStream#disableDialForTests} to {@code false} in their own
 * {@code @BeforeEach} (which runs after this callback) so they still exercise
 * the real connect/reconnect machinery against an injected fake connector or a
 * local test server.</p>
 */
public final class DialFreeTestExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        // The EventStream* tests drive the real connect/reconnect machinery
        // against an injected fake connector, a refused localhost port, or a
        // local SSE test server, so they opt out; every other test runs with
        // the dial neutralized.
        String simpleName = context.getRequiredTestClass().getSimpleName();
        boolean streamMachineryTest = simpleName.startsWith("EventStream");
        EventStream.disableDialForTests = !streamMachineryTest;
    }
}
