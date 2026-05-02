package com.smplkit.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Mirrors Python rule 8 for config: typed ConfigEnvironment with values + valuesRaw. */
class ConfigEnvironmentTest {

    @Test
    void configEnvironment_isFrozenRecordWithDefensiveCopies() {
        Map<String, Object> values = new HashMap<>();
        values.put("max_retries", 5);
        Map<String, Object> raw = new HashMap<>();
        raw.put("max_retries", Map.of("value", 5, "type", "NUMBER"));

        ConfigEnvironment env = new ConfigEnvironment(values, raw);

        // Mutate original
        values.put("hacked", true);
        raw.put("hacked", Map.of("value", true));

        // Record state is unaffected
        assertEquals(1, env.values().size());
        assertEquals(5, env.values().get("max_retries"));
        assertEquals(1, env.valuesRaw().size());
    }

    @Test
    void configEnvironment_returnedMapsAreImmutable() {
        ConfigEnvironment env = new ConfigEnvironment(Map.of("k", "v"), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> env.values().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> env.valuesRaw().put("x", Map.of()));
    }

    @Test
    void config_environments_returnsTypedView() {
        Map<String, Object> production = new HashMap<>();
        Map<String, Object> values = new HashMap<>();
        values.put("max_retries", Map.of("value", 5, "type", "NUMBER"));
        values.put("database.host", Map.of("value", "prod-db", "type", "STRING"));
        production.put("values", values);

        Map<String, Object> envs = new HashMap<>();
        envs.put("production", production);

        Config cfg = new Config(null, "user-service", "User Service");
        cfg.setEnvironments(envs);

        Map<String, ConfigEnvironment> typed = cfg.environments();
        assertEquals(1, typed.size());
        ConfigEnvironment prod = typed.get("production");
        assertEquals(5, prod.values().get("max_retries"));
        assertEquals("prod-db", prod.values().get("database.host"));
        assertNotNull(prod.valuesRaw().get("max_retries"));
    }

    @Test
    void config_environments_returnedMapIsImmutable() {
        Config cfg = new Config(null, "x", "X");
        assertThrows(UnsupportedOperationException.class,
                () -> cfg.environments().put("p", new ConfigEnvironment(Map.of(), Map.of())));
    }
}
