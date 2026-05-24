package com.smplkit.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the per-environment setters, removers, the typed environments() view
 * branches, and the async save() variants on {@link Config}.
 */
class ConfigMutationTest {

    private Config newConfig() {
        return new Config(null, "cfg", "Cfg");
    }

    // --- setString ---

    @Test
    void setString_baseLevel_writesItem() {
        Config c = newConfig();
        c.setString("greeting", "hello");
        assertEquals("hello", c.getResolvedItems().get("greeting"));
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
        assertEquals(42, c.getResolvedItems().get("limit"));
    }

    @Test
    void setNumber_perEnv_writesUnderEnvValues() {
        Config c = newConfig();
        c.setNumber("limit", 100, "production");
        assertEquals(100, c.environments().get("production").values().get("limit"));
    }

    @Test
    void setNumber_perEnv_extendsExistingEnv() {
        Config c = newConfig();
        c.setNumber("x", 1, "prod");
        c.setNumber("y", 2, "prod");
        ConfigEnvironment env = c.environments().get("prod");
        assertEquals(1, env.values().get("x"));
        assertEquals(2, env.values().get("y"));
    }

    // --- setBoolean ---

    @Test
    void setBoolean_baseLevel_writesItem() {
        Config c = newConfig();
        c.setBoolean("on", true);
        assertEquals(true, c.getResolvedItems().get("on"));
    }

    @Test
    void setBoolean_perEnv_writesUnderEnvValues() {
        Config c = newConfig();
        c.setBoolean("on", false, "production");
        assertEquals(false, c.environments().get("production").values().get("on"));
    }

    @Test
    void setBoolean_perEnv_extendsExistingEnv() {
        Config c = newConfig();
        c.setBoolean("a", true, "prod");
        c.setBoolean("b", false, "prod");
        ConfigEnvironment env = c.environments().get("prod");
        assertEquals(true, env.values().get("a"));
        assertEquals(false, env.values().get("b"));
    }

    // --- setJson ---

    @Test
    void setJson_baseLevel_writesItem() {
        Config c = newConfig();
        Map<String, Object> payload = Map.of("k", "v");
        c.setJson("payload", payload);
        assertEquals(payload, c.getResolvedItems().get("payload"));
    }

    @Test
    void setJson_perEnv_writesUnderEnvValues() {
        Config c = newConfig();
        Map<String, Object> payload = Map.of("k", "v");
        c.setJson("payload", payload, "production");
        assertEquals(payload, c.environments().get("production").values().get("payload"));
    }

    @Test
    void setJson_perEnv_extendsExistingEnv() {
        Config c = newConfig();
        c.setJson("a", Map.of("k", 1), "prod");
        c.setJson("b", Map.of("k", 2), "prod");
        ConfigEnvironment env = c.environments().get("prod");
        assertEquals(Map.of("k", 1), env.values().get("a"));
        assertEquals(Map.of("k", 2), env.values().get("b"));
    }

    // --- remove ---

    @Test
    void remove_baseItem_dropsItem() {
        Config c = newConfig();
        c.setString("k", "v");
        c.remove("k");
        assertFalse(c.getResolvedItems().containsKey("k"));
    }

    @Test
    void removeNoEnv_isAliasForBaseRemove() {
        Config c = newConfig();
        c.setNumber("n", 5);
        c.remove("n", null);
        assertFalse(c.getResolvedItems().containsKey("n"));
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
    void remove_perEnv_missingEnvIsNoOp() {
        Config c = newConfig();
        // Should not throw even though the environment doesn't exist
        c.remove("anything", "missing-env");
        assertTrue(c.environments().isEmpty());
    }

    @Test
    void remove_perEnv_keyAbsentIsNoOp() {
        Config c = newConfig();
        // Plant an environment entry that doesn't contain the key being removed.
        Map<String, Map<String, Object>> envs = new HashMap<>();
        Map<String, Object> existing = new HashMap<>();
        existing.put("other", "metadata");
        envs.put("prod", existing);
        c.setEnvironments(envs);

        // Should be a no-op rather than NPE
        c.remove("anything", "prod");
        // The other key is left alone.
        assertEquals("metadata", c.environments().get("prod").values().get("other"));
    }

    // --- environments() defensive branch coverage ---

    @Test
    void environments_nullEnvValue_yieldsEmptyEnvironment() {
        Config c = newConfig();
        // Per ADR-024 §2.4 each env entry IS the flat override map. A null
        // entry should degrade to an empty ConfigEnvironment rather than NPE.
        Map<String, Map<String, Object>> envs = new HashMap<>();
        envs.put("prod", null);
        c.setEnvironments(envs);

        ConfigEnvironment env = c.environments().get("prod");
        assertNotNull(env);
        assertTrue(env.values().isEmpty());
        assertTrue(env.valuesRaw().isEmpty());
    }

    @Test
    void environments_flatShape_isPassedThrough() {
        Config c = newConfig();
        // Per ADR-024 §2.4 each env entry is {key: rawValue}, no "values"
        // sub-map and no per-override envelope.
        Map<String, Map<String, Object>> envs = new HashMap<>();
        Map<String, Object> values = new HashMap<>();
        values.put("inline-string", "directly stored");
        envs.put("prod", values);
        c.setEnvironments(envs);

        ConfigEnvironment env = c.environments().get("prod");
        assertEquals("directly stored", env.values().get("inline-string"));
    }

    // --- saveAsync (no-op-ish: just confirm it dispatches via executor) ---

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
}
