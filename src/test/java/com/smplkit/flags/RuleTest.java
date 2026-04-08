package com.smplkit.flags;

import com.smplkit.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleTest {

    @Test
    void singleCondition() {
        Map<String, Object> rule = new Rule("Enterprise only")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();
        assertEquals("Enterprise only", rule.get("description"));
        assertEquals(true, rule.get("value"));
        assertNotNull(rule.get("logic"));
        assertFalse(rule.containsKey("environment"));
    }

    @Test
    void multipleConditionsAreAnded() {
        Map<String, Object> rule = new Rule("Enterprise US")
                .when("user.plan", "==", "enterprise")
                .when("account.region", "==", "us")
                .serve(true)
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> logic = (Map<String, Object>) rule.get("logic");
        assertTrue(logic.containsKey("and"));
        @SuppressWarnings("unchecked")
        List<Object> conditions = (List<Object>) logic.get("and");
        assertEquals(2, conditions.size());
    }

    @Test
    void containsOperator() {
        Map<String, Object> rule = new Rule("Contains test")
                .when("user.tags", "contains", "beta")
                .serve(true)
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> logic = (Map<String, Object>) rule.get("logic");
        assertTrue(logic.containsKey("in"));
    }

    @Test
    void withEnvironment() {
        Map<String, Object> rule = new Rule("Prod only")
                .environment("production")
                .when("user.plan", "==", "premium")
                .serve("blue")
                .build();
        assertEquals("production", rule.get("environment"));
        assertEquals("blue", rule.get("value"));
    }

    @Test
    void emptyConditions() {
        Map<String, Object> rule = new Rule("No conditions")
                .serve(true)
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> logic = (Map<String, Object>) rule.get("logic");
        assertTrue(logic.isEmpty());
    }

    @Test
    void numericServeValue() {
        Map<String, Object> rule = new Rule("Rate limit")
                .when("user.plan", "==", "free")
                .serve(100)
                .build();
        assertEquals(100, rule.get("value"));
    }
}
