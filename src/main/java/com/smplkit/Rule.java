package com.smplkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for constructing flag evaluation rules.
 *
 * <pre>{@code
 * Map<String, Object> rule = new Rule("Enable for enterprise users")
 *     .when("user.plan", "==", "enterprise")
 *     .serve(true)
 *     .build();
 *
 * Map<String, Object> rule2 = new Rule("Blue for premium")
 *     .environment("production")
 *     .when("user.plan", "==", "premium")
 *     .serve("blue")
 *     .build();
 * }</pre>
 */
public final class Rule {

    private final String description;
    private final List<Map<String, Object>> conditions = new ArrayList<>();
    private Object value;
    private String environment;

    /**
     * Creates a new rule builder with the given description.
     *
     * @param description human-readable description of what this rule does
     */
    public Rule(String description) {
        this.description = description;
    }

    /**
     * Adds a condition to this rule. Multiple conditions are AND'd together.
     *
     * @param variable the context variable path (e.g. "user.plan")
     * @param operator the comparison operator (==, !=, &gt;, &lt;, &gt;=, &lt;=, in, contains)
     * @param operand  the value to compare against
     * @return this rule
     */
    public Rule when(String variable, String operator, Object operand) {
        Map<String, Object> condition;
        if ("contains".equals(operator)) {
            condition = Map.of("in", List.of(operand, Map.of("var", variable)));
        } else {
            condition = Map.of(operator, List.of(Map.of("var", variable), operand));
        }
        conditions.add(condition);
        return this;
    }

    /**
     * Sets the value to serve when this rule matches.
     *
     * @param value the value to return on match
     * @return this rule
     */
    public Rule serve(Object value) {
        this.value = value;
        return this;
    }

    /**
     * Tags this rule with a specific environment.
     *
     * @param environment the environment key (e.g. "production")
     * @return this rule
     */
    public Rule environment(String environment) {
        this.environment = environment;
        return this;
    }

    /**
     * Builds the rule as a plain map suitable for the flags API.
     *
     * @return a map with "description", "logic", "value", and optionally "environment"
     */
    public Map<String, Object> build() {
        Map<String, Object> logic;
        if (conditions.isEmpty()) {
            logic = Map.of();
        } else if (conditions.size() == 1) {
            logic = conditions.get(0);
        } else {
            logic = Map.of("and", new ArrayList<>(conditions));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("description", description);
        result.put("logic", logic);
        result.put("value", value);
        if (environment != null) {
            result.put("environment", environment);
        }
        return result;
    }
}
