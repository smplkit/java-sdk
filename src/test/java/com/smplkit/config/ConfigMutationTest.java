package com.smplkit.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the base / per-environment setters, removers, the {@code items()} /
 * {@code itemsRaw()} / {@code environments()} views, and the async save()
 * variants on {@link Config}.
 */
class ConfigMutationTest {

    private Config newConfig() {
        return new Config(null, "cfg", "Cfg", null, null,
                new HashMap<>(), new HashMap<>(), null, null);
    }

    // --- setString ---

    @Test
    void setString_baseLevel_writesItem() {
        Config c = newConfig();
        c.setString("greeting", "hello");
        assertEquals("hello", c.items().get("greeting"));
        // The raw typed shape carries value + type.
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) c.itemsRaw().get("greeting");
        assertEquals("hello", raw.get("value"));
        assertEquals("STRING", raw.get("type"));
    }

    @Test
    void setString_withDescription_carriesDescription() {
        Config c = newConfig();
        c.setString("greeting", "hello", "a friendly hello", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) c.itemsRaw().get("greeting");
        assertEquals("a friendly hello", raw.get("description"));
    }

    @Test
    void setString_perEnv_writesUnderEnvValues() {
        Config c = newConfig();
        c.setString("greeting", "hola", "production");
        assertEquals("hola", c.environments().get("production").values().get("greeting"));
    }

    @Test
    void setString_perEnv_extendsExistingEnv() {
        Config c = newConfig();
        c.setString("a", "1", "prod");
        c.setString("b", "2", "prod");
        ConfigEnvironment env = c.environments().get("prod");
        assertEquals("1", env.values().get("a"));
        assertEquals("2", env.values().get("b"));
    }

    // --- setNumber ---

    @Test
    void setNumber_baseLevel_writesItem() {
        Config c = newConfig();
        c.setNumber("limit", 42);
        assertEquals(42, c.items().get("limit"));
    }

    @Test
    void setNumber_perEnv_writesUnderEnvValues() {
        Config c = newConfig();
        c.setNumber("limit", 100, "production");
        assertEquals(100, c.environments().get("production").values().get("limit"));
    }

    @Test
    void setNumber_withDescription_baseLevel() {
        Config c = newConfig();
        c.setNumber("limit", 42, "the cap", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) c.itemsRaw().get("limit");
        assertEquals("the cap", raw.get("description"));
        assertEquals("NUMBER", raw.get("type"));
    }

    // --- setBoolean ---

    @Test
    void setBoolean_baseLevel_writesItem() {
        Config c = newConfig();
        c.setBoolean("on", true);
        assertEquals(true, c.items().get("on"));
    }

    @Test
    void setBoolean_perEnv_writesUnderEnvValues() {
        Config c = newConfig();
        c.setBoolean("on", false, "production");
        assertEquals(false, c.environments().get("production").values().get("on"));
    }

    @Test
    void setBoolean_withDescription_baseLevel() {
        Config c = newConfig();
        c.setBoolean("on", true, "the switch", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) c.itemsRaw().get("on");
        assertEquals("the switch", raw.get("description"));
        assertEquals("BOOLEAN", raw.get("type"));
    }

    // --- setJson ---

    @Test
    void setJson_baseLevel_writesItem() {
        Config c = newConfig();
        Map<String, Object> payload = Map.of("k", "v");
        c.setJson("payload", payload);
        assertEquals(payload, c.items().get("payload"));
    }

    @Test
    void setJson_perEnv_writesUnderEnvValues() {
        Config c = newConfig();
        Map<String, Object> payload = Map.of("k", "v");
        c.setJson("payload", payload, "production");
        assertEquals(payload, c.environments().get("production").values().get("payload"));
    }

    @Test
    void setJson_withDescription_baseLevel() {
        Config c = newConfig();
        c.setJson("payload", Map.of("k", "v"), "the blob", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) c.itemsRaw().get("payload");
        assertEquals("the blob", raw.get("description"));
        assertEquals("JSON", raw.get("type"));
    }

    // --- set(ConfigItem) without environment uses the no-description branch ---

    @Test
    void setItem_withoutDescription_omitsDescriptionKey() {
        Config c = newConfig();
        c.set(new ConfigItem("k", "v", ItemType.STRING));
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) c.itemsRaw().get("k");
        assertFalse(raw.containsKey("description"));
    }

    // --- remove ---

    @Test
    void remove_baseItem_dropsItem() {
        Config c = newConfig();
        c.setString("k", "v");
        c.remove("k");
        assertFalse(c.items().containsKey("k"));
    }

    @Test
    void removeNoEnv_isAliasForBaseRemove() {
        Config c = newConfig();
        c.setNumber("n", 5);
        c.remove("n", null);
        assertFalse(c.items().containsKey("n"));
    }

    @Test
    void remove_perEnv_dropsItemFromEnv() {
        Config c = newConfig();
        c.setString("greeting", "hola", "prod");
        c.setString("limit", "10", "prod");
        c.remove("greeting", "prod");
        ConfigEnvironment env = c.environments().get("prod");
        assertFalse(env.values().containsKey("greeting"));
        assertEquals("10", env.values().get("limit"));
    }

    @Test
    void remove_perEnv_missingEnv_createsEmptyEnvThenNoOp() {
        Config c = newConfig();
        // itemsTarget(env) creates the env if absent, then removes a missing key.
        c.remove("anything", "missing-env");
        assertTrue(c.environments().get("missing-env").values().isEmpty());
    }

    @Test
    void remove_perEnv_keyAbsentIsNoOp() {
        Config c = newConfig();
        c.setString("other", "metadata", "prod");
        c.remove("anything", "prod");
        assertEquals("metadata", c.environments().get("prod").values().get("other"));
    }

    // --- setters that mutate metadata ---

    @Test
    void setName_setDescription_setParent_mutateFields() {
        Config c = newConfig();
        c.setName("New Name");
        c.setDescription("new desc");
        c.setParent("the-parent");
        assertEquals("New Name", c.getName());
        assertEquals("new desc", c.getDescription());
        assertEquals("the-parent", c.getParent());
    }

    // --- saveAsync ---

    @Test
    void saveAsync_withNullClient_completesExceptionally() throws Exception {
        Config c = newConfig();
        // No client bound — save() throws IllegalStateException. The async
        // wrapper should propagate that as an exceptional completion.
        CompletableFuture<Void> fut = c.saveAsync();
        Exception ex = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> fut.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void saveAsyncWithExecutor_runsOnSuppliedExecutor() throws Exception {
        Config c = newConfig();
        AtomicBoolean usedExecutor = new AtomicBoolean(false);
        Executor inline = r -> {
            usedExecutor.set(true);
            r.run();
        };
        CompletableFuture<Void> fut = c.saveAsync(inline);
        try {
            fut.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // save() will fail (no client bound) — we only care that the
            // supplied executor was actually used.
        }
        assertTrue(usedExecutor.get());
    }

    @Test
    void deleteAsync_withNullClient_completesExceptionally() {
        Config c = newConfig();
        CompletableFuture<Void> fut = c.deleteAsync();
        Exception ex = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> fut.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void deleteAsyncWithExecutor_runsOnSuppliedExecutor() {
        Config c = newConfig();
        AtomicBoolean usedExecutor = new AtomicBoolean(false);
        Executor inline = r -> {
            usedExecutor.set(true);
            r.run();
        };
        CompletableFuture<Void> fut = c.deleteAsync(inline);
        try {
            fut.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // delete() fails (no client) — we only assert the executor ran.
        }
        assertTrue(usedExecutor.get());
    }

    @Test
    void config_toString() {
        Config c = newConfig();
        assertEquals("Config(id=cfg, name=Cfg)", c.toString());
    }

    // --- items() / itemsRaw() unwrap branches ---

    @Test
    void items_unwrapsTypedMap_andPassesPlainValueThrough() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("typed", Map.of("value", 30, "type", "NUMBER"));
        raw.put("plain", "raw-string"); // not a typed map
        raw.put("mapNoValue", Map.of("k", "v")); // a map without a "value" key
        Config c = new Config(null, "cfg", "Cfg", null, null,
                raw, new HashMap<>(), null, null);

        Map<String, Object> items = c.items();
        assertEquals(30, items.get("typed"));
        assertEquals("raw-string", items.get("plain"));
        assertEquals(Map.of("k", "v"), items.get("mapNoValue"));

        Map<String, Object> rawCopy = c.itemsRaw();
        // The typed entry is a defensive copy of the map.
        assertEquals(Map.of("value", 30, "type", "NUMBER"), rawCopy.get("typed"));
        assertEquals("raw-string", rawCopy.get("plain"));
    }

    // --- buildChain ---

    @Test
    void buildChain_rootConfig_singleEntry() {
        Config c = new Config(null, "root", "Root", null, null,
                new HashMap<>(), new HashMap<>(), null, null);
        var chain = c.buildChain(null);
        assertEquals(1, chain.size());
        assertEquals("root", chain.get(0).id);
    }

    @Test
    void buildChain_parentResolvedFromSuppliedList() {
        Config parent = new Config(null, "parent", "Parent", null, null,
                new HashMap<>(), new HashMap<>(), null, null);
        Config child = new Config(null, "child", "Child", null, "parent",
                new HashMap<>(), new HashMap<>(), null, null);
        var chain = child.buildChain(java.util.List.of(child, parent));
        assertEquals(2, chain.size());
        assertEquals("child", chain.get(0).id);
        assertEquals("parent", chain.get(1).id);
    }

    @Test
    void buildChain_parentMissingAndNoClient_throws() {
        // A parent that isn't in the supplied list and no client to fetch it
        // with cannot be resolved.
        Config child = new Config(null, "child", "Child", null, "ghost-parent",
                new HashMap<>(), new HashMap<>(), null, null);
        assertThrows(IllegalStateException.class, () -> child.buildChain(java.util.List.of(child)));
    }
}
