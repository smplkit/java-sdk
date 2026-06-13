package com.smplkit.flags;

import com.smplkit.Rule;
import com.smplkit.flags.types.Op;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleTest {

    // --- Op enum (Rule operator type) ---

    @Test
    void op_valueReturnsWireOperator() {
        assertEquals("contains", Op.CONTAINS.value());
        assertEquals("==", Op.EQ.value());
        assertEquals(">", Op.GT.value());
        assertEquals(">=", Op.GTE.value());
        assertEquals("in", Op.IN.value());
        assertEquals("<", Op.LT.value());
        assertEquals("<=", Op.LTE.value());
        assertEquals("!=", Op.NEQ.value());
    }

    @Test
    void op_valueOfRoundTrips() {
        for (Op op : Op.values()) {
            assertSame(op, Op.valueOf(op.name()));
            assertNotNull(op.value());
        }
    }

    @Test
    void when_withOpEnum_buildsSameLogicAsRawString() {
        Map<String, Object> viaEnum = new Rule("Enterprise")
                .when("user.plan", Op.EQ, "enterprise")
                .serve(true)
                .build();
        Map<String, Object> viaString = new Rule("Enterprise")
                .when("user.plan", "==", "enterprise")
                .serve(true)
                .build();
        assertEquals(viaString.get("logic"), viaEnum.get("logic"));
    }

    @Test
    void when_withOpContains_usesInOperator() {
        Map<String, Object> rule = new Rule("Beta testers")
                .when("user.tags", Op.CONTAINS, "beta")
                .serve(true)
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> logic = (Map<String, Object>) rule.get("logic");
        assertTrue(logic.containsKey("in"));
    }

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

    @Test
    void twoArgConstructor_scopesToEnvironment() {
        Map<String, Object> rule = new Rule("Enterprise on staging", "staging")
                .when("user.plan", Op.EQ, "enterprise")
                .serve(true)
                .build();
        assertEquals("Enterprise on staging", rule.get("description"));
        assertEquals("staging", rule.get("environment"));
        assertEquals(true, rule.get("value"));
    }

    @Test
    void when_rawJsonLogicExpression_escapeHatch() {
        // The Map overload is the escape hatch for OR / nested expressions.
        Map<String, Object> orExpr = Map.of("or", List.of(
                Map.of("==", List.of(Map.of("var", "user.plan"), "pro")),
                Map.of("==", List.of(Map.of("var", "user.plan"), "enterprise"))));

        Map<String, Object> rule = new Rule("Paid plans")
                .when(orExpr)
                .serve(true)
                .build();

        assertEquals(orExpr, rule.get("logic"));
    }
}
