package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors Python rule 8: typed environment / value records, frozen, with
 * defensive copies on construction.
 */
class TypedAccessorsTest {

    @Test
    void flagValue_isFrozenRecord() {
        FlagValue v = new FlagValue("Enterprise", "enterprise");
        assertEquals("Enterprise", v.name());
        assertEquals("enterprise", v.value());
        // Records have built-in equals/hashCode/toString
        assertEquals(v, new FlagValue("Enterprise", "enterprise"));
    }

    @Test
    void flagRule_defensivelyCopiesLogicMap() {
        Map<String, Object> logic = new HashMap<>();
        logic.put("==", List.of("user.plan", "enterprise"));
        FlagRule rule = new FlagRule(logic, "blue", "Enterprise users get blue");
        // Mutate the original
        logic.put("hacked", true);
        // The rule's logic is unaffected
        assertFalse(rule.logic().containsKey("hacked"));
        assertEquals(List.of("user.plan", "enterprise"), rule.logic().get("=="));
    }

    @Test
    void flagRule_logicIsImmutable() {
        FlagRule rule = new FlagRule(Map.of("==", List.of("a", "b")), "v", "d");
        assertThrows(UnsupportedOperationException.class,
                () -> rule.logic().put("foo", "bar"));
    }

    @Test
    void flagEnvironment_defensivelyCopiesRulesList() {
        java.util.List<FlagRule> rules = new java.util.ArrayList<>();
        rules.add(new FlagRule(Map.of(), "v", "d"));
        FlagEnvironment env = new FlagEnvironment(true, false, rules);
        // Mutate the original
        rules.add(new FlagRule(Map.of(), "x", null));
        // env.rules() unchanged
        assertEquals(1, env.rules().size());
    }

    @Test
    void flagEnvironment_rulesListIsImmutable() {
        FlagEnvironment env = new FlagEnvironment(true, null, List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> env.rules().add(new FlagRule(Map.of(), null, null)));
    }

    @Test
    void flag_environments_returnsTypedView() {
        // Construct a Flag with the raw-Map shape that the wire/cache layer uses
        Map<String, Object> envs = new HashMap<>();
        Map<String, Object> production = new HashMap<>();
        production.put("enabled", true);
        production.put("default", "blue");
        production.put("rules", List.of(
                Map.of("logic", Map.of("==", List.of("user.plan", "enterprise")),
                        "value", "red", "description", "rule-desc")));
        envs.put("production", production);

        Flag<String> flag = new Flag<>(null, "banner-color", "Banner Color",
                "STRING", "red", null, null, envs, null, null, String.class);

        Map<String, FlagEnvironment> typed = flag.environments();
        assertEquals(1, typed.size());
        FlagEnvironment prod = typed.get("production");
        assertNotNull(prod);
        assertTrue(prod.enabled());
        assertEquals("blue", prod.defaultValue());
        assertEquals(1, prod.rules().size());
        FlagRule rule = prod.rules().get(0);
        assertEquals("red", rule.value());
        assertEquals("rule-desc", rule.description());
    }

    @Test
    void flag_environments_returnedMapIsImmutable() {
        Flag<Boolean> flag = new Flag<>(null, "f", "F", "BOOLEAN",
                false, null, null, new HashMap<>(), null, null, Boolean.class);
        assertThrows(UnsupportedOperationException.class,
                () -> flag.environments().put("staging",
                        new FlagEnvironment(true, true, List.of())));
    }

    @Test
    void flag_values_returnsTypedFlagValueList() {
        List<Map<String, Object>> rawValues = List.of(
                Map.of("name", "Red", "value", "red"),
                Map.of("name", "Blue", "value", "blue"));
        Flag<String> flag = new Flag<>(null, "f", "F", "STRING",
                "red", rawValues, null, null, null, null, String.class);
        List<FlagValue> typed = flag.values();
        assertEquals(2, typed.size());
        assertEquals("Red", typed.get(0).name());
        assertEquals("red", typed.get(0).value());
        assertEquals("Blue", typed.get(1).name());
        assertEquals("blue", typed.get(1).value());
    }

    @Test
    void flag_values_nullWhenUnconstrained() {
        Flag<Number> flag = new Flag<>(null, "max-retries", "Max Retries",
                "NUMERIC", 3, null, null, null, null, null, Number.class);
        assertNull(flag.values());
    }
}
