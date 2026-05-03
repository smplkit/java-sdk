package com.smplkit.management;

import com.smplkit.Color;
import com.smplkit.config.Config;
import com.smplkit.config.ConfigClient;
import com.smplkit.config.ConfigManagement;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Catches the small per-class coverage gaps across the management package:
 * the typed Color setter on Environment, AccountSettings.saveAsync, ContextType.saveAsync,
 * ConfigManagement.new_(parent: Config), and SmplManagementClientBuilder.profile().
 */
class ManagementOneOffsTest {

    // ---------------------------------------------------------- Environment.color()

    @Test
    void environment_typedColorAccessor_returnsColorOrNull() {
        try (SmplManagementClient mc = SmplManagementClient.create("test-key")) {
            Environment env = mc.environments.new_("e", "E", "#ff0000",
                    EnvironmentClassification.STANDARD);
            assertNotNull(env.color());
            assertEquals("#ff0000", env.color().hex());

            // Same constructor with no color → typed accessor returns null.
            Environment envNoColor = mc.environments.new_("n", "N", null,
                    EnvironmentClassification.STANDARD);
            assertNull(envNoColor.color());
        }
    }

    @Test
    void environment_setColor_typedColor_writesHex() {
        try (SmplManagementClient mc = SmplManagementClient.create("test-key")) {
            Environment env = mc.environments.new_("e", "E", "#000000",
                    EnvironmentClassification.STANDARD);
            env.setColor(new Color("#abcdef"));
            assertEquals("#abcdef", env.getColor());
            // Null typed setter clears the field.
            env.setColor((Color) null);
            assertNull(env.getColor());
        }
    }

    // ---------------------------------------------------------- AccountSettings.saveAsync

    @Test
    void accountSettings_saveAsync_propagatesUnboundClient() {
        AccountSettings unbound = new AccountSettings(null, null);
        CompletableFuture<Void> fut = unbound.saveAsync();
        Exception ex = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> fut.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void accountSettings_saveAsyncWithExecutor_usesExecutor() throws Exception {
        AccountSettings unbound = new AccountSettings(null, null);
        AtomicBoolean used = new AtomicBoolean(false);
        Executor inline = r -> {
            used.set(true);
            r.run();
        };
        try {
            unbound.saveAsync(inline).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // save() fails (unbound client) — only care that executor ran.
        }
        assertTrue(used.get());
    }

    // ---------------------------------------------------------- ContextType.saveAsync

    @Test
    void contextType_saveAsync_propagatesUnboundClient() {
        ContextType unbound = new ContextType(null, "id", "Name", null, null, null);
        CompletableFuture<Void> fut = unbound.saveAsync();
        Exception ex = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> fut.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void contextType_saveAsyncWithExecutor_usesExecutor() throws Exception {
        ContextType unbound = new ContextType(null, "id", "Name", null, null, null);
        AtomicBoolean used = new AtomicBoolean(false);
        Executor inline = r -> {
            used.set(true);
            r.run();
        };
        try {
            unbound.saveAsync(inline).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // save() fails (unbound client) — only care that executor ran.
        }
        assertTrue(used.get());
    }

    // ---------------------------------------------------------- ConfigManagement.new_(Config parent)

    @Test
    void configManagement_newWithConfigParent_usesParentId() {
        ConfigsApi api = mock(ConfigsApi.class);
        ConfigClient client = new ConfigClient(api, HttpClient.newHttpClient(), "test-key");
        ConfigManagement mgmt = client.management();

        Config parent = mgmt.new_("parent-id");
        Config child = mgmt.new_("child-id", "Child", "desc", parent);
        assertEquals("parent-id", child.getParent());
    }

    @Test
    void configManagement_newWithNullConfigParent_usesNull() {
        ConfigsApi api = mock(ConfigsApi.class);
        ConfigClient client = new ConfigClient(api, HttpClient.newHttpClient(), "test-key");
        ConfigManagement mgmt = client.management();

        Config child = mgmt.new_("orphan", "Orphan", "no parent", (Config) null);
        assertNull(child.getParent());
    }

    // ---------------------------------------------------------- SmplManagementClientBuilder.profile()

    @Test
    void smplManagementClientBuilder_profileSetterUsesProfile() {
        // Profile "default" silently proceeds when the requested profile section
        // doesn't exist, so we can build successfully even with no ~/.smplkit
        // file present (apiKey is supplied directly).
        try (SmplManagementClient mc = SmplManagementClient.builder()
                .profile("default")
                .apiKey("sk_test")
                .build()) {
            assertEquals("sk_test", mc.apiKey());
        }
    }

    @Test
    void smplManagementClientBuilder_profileRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> SmplManagementClient.builder().profile(null));
    }
}
