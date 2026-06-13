package com.smplkit.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Covers {@link ConfigEnvironment}: defensive copies, flat-shape storage, and the legacy-wrapped tolerance. */
class ConfigEnvironmentTest {

    @Test
    void configEnvironment_storesDefensiveCopyOfValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("max_retries", 5);

        ConfigEnvironment env = new ConfigEnvironment(values);

        // Mutating the source map after construction must not affect the env.
        values.put("hacked", true);

        assertEquals(1, env.values().size());
        assertEquals(5, env.values().get("max_retries"));
        assertEquals(1, env.valuesRaw().size());
        assertEquals(5, env.valuesRaw().get("max_retries"));
    }

    @Test
    void configEnvironment_emptyCtor_isEmpty() {
        ConfigEnvironment env = new ConfigEnvironment();
        assertTrue(env.values().isEmpty());
        assertTrue(env.valuesRaw().isEmpty());
    }

    @Test
    void configEnvironment_nullValues_isEmpty() {
        ConfigEnvironment env = new ConfigEnvironment(null);
        assertTrue(env.values().isEmpty());
    }

    @Test
    void configEnvironment_returnedMapsAreCopies() {
        ConfigEnvironment env = new ConfigEnvironment(Map.of("k", "v"));
        // Returned maps are fresh copies — mutating them does not affect the env.
        env.values().put("x", "y");
        env.valuesRaw().put("z", "w");
        assertEquals(1, env.values().size());
        assertEquals(1, env.valuesRaw().size());
    }

    @Test
    void configEnvironment_unwrapsLegacyWrappedValue() {
        // Tolerates the legacy {key: {"value": v, "type": ..}} shape, storing flat.
        Map<String, Object> wrapped = new HashMap<>();
        wrapped.put("max_retries", Map.of("value", 5, "type", "NUMBER"));

        ConfigEnvironment env = new ConfigEnvironment(wrapped);

        assertEquals(5, env.values().get("max_retries"));
    }

    @Test
    void configEnvironment_keepsMapWithExtraKeysAsRawValue() {
        // A map that has "value" but ALSO keys outside {value,type,description}
        // is NOT a legacy wrapper — it stays stored as the raw value.
        Map<String, Object> notAWrapper = Map.of("value", 5, "extra", "x");
        Map<String, Object> values = new HashMap<>();
        values.put("k", notAWrapper);

        ConfigEnvironment env = new ConfigEnvironment(values);

        assertEquals(notAWrapper, env.values().get("k"));
    }

    @Test
    void configEnvironment_toString_includesValues() {
        ConfigEnvironment env = new ConfigEnvironment(Map.of("k", "v"));
        String str = env.toString();
        assertTrue(str.contains("ConfigEnvironment"));
        assertTrue(str.contains("k") && str.contains("v"));
    }

    @Test
    void config_environments_returnsTypedView() {
        // Per ADR-024 §2.4 each env entry IS the flat override map of
        // {key: rawValue}. ConfigEnvironment.values() returns the same map.
        Config cfg = newConfig("user-service");
        cfg.setNumber("max_retries", 5, "production");
        cfg.setString("database.host", "prod-db", "production");

        Map<String, ConfigEnvironment> typed = cfg.environments();
        assertEquals(1, typed.size());
        ConfigEnvironment prod = typed.get("production");
        assertEquals(5, prod.values().get("max_retries"));
        assertEquals("prod-db", prod.values().get("database.host"));
        assertEquals(5, prod.valuesRaw().get("max_retries"));
    }

    @Test
    void config_environments_returnedMapIsACopy() {
        Config cfg = newConfig("x");
        // The map returned by environments() is a fresh copy: mutating it does
        // not add an environment to the underlying config.
        cfg.environments().put("p", new ConfigEnvironment(Map.of()));
        assertTrue(cfg.environments().isEmpty());
    }

    /** Build a fresh unsaved Config without standing up a client. */
    private static Config newConfig(String id) {
        return new Config(null, id, id, null, null,
                new HashMap<>(), new HashMap<>(), null, null);
    }
}
