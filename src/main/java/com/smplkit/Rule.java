package com.smplkit;

import com.smplkit.flags.types.Op;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for flag targeting rules.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * new Rule("Enable for enterprise users", "staging")
 *     .when("user.plan", Op.EQ, "enterprise")
 *     .serve(true);
 * }</pre>
 *
 * <p>Multiple {@code when()} calls are AND'd. {@code environment} is required so the
 * target environment is unambiguous when the rule is passed to
 * {@link com.smplkit.flags.Flag#addRule}. {@code serve()} finalizes the rule and
 * returns the built map ready to pass to {@code addRule}.</p>
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
     * Creates a new rule builder with the given description scoped to an environment.
     *
     * @param description human-readable description of what this rule does
     * @param environment the environment key (e.g. {@code "production"})
     */
    public Rule(String description, String environment) {
        this.description = description;
        this.environment = environment;
    }

    /**
     * Add a condition. Multiple calls are AND'd at the top level.
     *
     * <p>Convenience form for simple comparisons. {@code op} accepts an
     * {@link Op} enum value (preferred) or a raw string (e.g. {@code "=="},
     * {@code "contains"}).</p>
     *
     * @param variable the context variable path (e.g. {@code "user.plan"})
     * @param operator the comparison operator
     * @param operand  the value to compare against
     * @return this rule
     */
    public Rule when(String variable, Op operator, Object operand) {
        return when(variable, operator.value(), operand);
    }

    /**
     * Add a condition. Multiple calls are AND'd at the top level.
     *
     * <p>Convenience form for simple comparisons. {@code operator} accepts a
     * raw JSON Logic operator string (e.g. {@code "=="}, {@code "contains"}).</p>
     *
     * @param variable the context variable path (e.g. {@code "user.plan"})
     * @param operator the comparison operator (==, !=, &gt;, &lt;, &gt;=, &lt;=, in, contains)
     * @param operand  the value to compare against
     * @return this rule
     */
    public Rule when(String variable, String operator, Object operand) {
        Map<String, Object> condition;
        if ("contains".equals(operator)) {
            // JSON Logic "in" with reversed operands: value in var
            condition = Map.of("in", List.of(operand, Map.of("var", variable)));
        } else {
            condition = Map.of(operator, List.of(Map.of("var", variable), operand));
        }
        conditions.add(condition);
        return this;
    }

    /**
     * Add a condition. Multiple calls are AND'd at the top level.
     *
     * <p>Escape hatch accepting an arbitrary JSON Logic expression (use this for
     * OR, nested AND/OR, {@code if}, etc.). See https://jsonlogic.com/ for the
     * full expression grammar.</p>
     *
     * @param expr an arbitrary JSON Logic expression
     * @return this rule
     */
    public Rule when(Map<String, Object> expr) {
        conditions.add(expr);
        return this;
    }

    /**
     * Finalize the rule with {@code value} served on match.
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
     * @param environment the environment key (e.g. {@code "production"})
     * @return this rule
     */
    public Rule environment(String environment) {
        this.environment = environment;
        return this;
    }

    /**
     * Builds the rule as a map ready to pass to {@link com.smplkit.flags.Flag#addRule}.
     *
     * @return the built rule map
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
