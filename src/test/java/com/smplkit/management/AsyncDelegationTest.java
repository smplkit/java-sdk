package com.smplkit.management;

import com.smplkit.Context;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each per-namespace async client (contexts, environments, contextTypes,
 * accountSettings) exposes thin delegation methods. The
 * {@link AsyncSmplManagementClientTest} only calls a couple of them via
 * {@code AsyncSmplManagementClient}; this test reaches into the direct
 * delegation methods to make sure the {@code CompletableFuture} construction is
 * exercised on every overload (including the (type, key) tuple forms).
 *
 * <p>We never actually wait for completion — these would attempt outbound HTTP
 * — only that {@code CompletableFuture} objects are produced.</p>
 */
class AsyncDelegationTest {

    @Test
    void asyncContextsClient_allOverloadsReturnFutures() {
        try (AsyncSmplManagementClient mc = AsyncSmplManagementClient.create("test-key")) {
            AsyncContextsClient async = mc.contexts;

            // Both register overloads (List + single)
            CompletableFuture<Void> a = async.register(List.of(
                    new Context("user", "u-1", Map.of())));
            CompletableFuture<Void> b = async.register(new Context("user", "u-2", Map.of()));
            assertNotNull(a);
            assertNotNull(b);

            // flush + list + the two get + two delete overloads
            assertNotNull(async.flush());
            assertNotNull(async.list("user"));
            assertNotNull(async.get("user:u-1"));
            assertNotNull(async.get("user", "u-1"));
            assertNotNull(async.delete("user:u-1"));
            assertNotNull(async.delete("user", "u-1"));
        }
    }

    @Test
    void asyncContextTypesClient_allOverloadsReturnFutures() {
        try (AsyncSmplManagementClient mc = AsyncSmplManagementClient.create("test-key")) {
            AsyncContextTypesClient async = mc.contextTypes;

            // Two new_ overloads (synchronous, no future)
            assertNotNull(async.new_("user"));
            assertNotNull(async.new_("user", "User", Map.of("plan", Map.of("type", "STRING"))));

            // get + delete return futures
            assertNotNull(async.get("user"));
            assertNotNull(async.delete("user"));
        }
    }

    @Test
    void asyncEnvironmentsClient_getAndDeleteReturnFutures() {
        try (AsyncSmplManagementClient mc = AsyncSmplManagementClient.create("test-key")) {
            AsyncEnvironmentsClient async = mc.environments;
            assertNotNull(async.get("e-1"));
            assertNotNull(async.delete("e-1"));
        }
    }

    @Test
    void asyncAccountSettingsClient_getReturnsFuture() {
        try (AsyncSmplManagementClient mc = AsyncSmplManagementClient.create("test-key")) {
            AsyncAccountSettingsClient async = mc.accountSettings;
            assertNotNull(async.get());
        }
    }
}
