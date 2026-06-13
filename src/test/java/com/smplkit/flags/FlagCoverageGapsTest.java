package com.smplkit.flags;

import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Targeted coverage for {@link Flag} branch/method gaps not exercised by the
 * existing suites: the no-arg enable/disable/clearRules variants and their
 * "all environments" loops, the no-arg async save/delete overloads, and the
 * {@link Flag#environments()} typed-view defensive branches (non-Map env value,
 * missing {@code enabled}, non-Map rule entry, non-Map logic). Also covers the
 * frozen-record null branches of {@link FlagEnvironment} and {@link FlagRule}.
 */
class FlagCoverageGapsTest {

    private static final Executor INLINE = Runnable::run;

    private FlagsApi mockApi;
    private FlagsClient client;

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(FlagsApi.class);
        client = new FlagsClient(mockApi, Mockito.mock(ContextsApi.class),
                HttpClient.newHttpClient(), "test-key",
                "https://flags.smplkit.com", "https://app.smplkit.com",
                Duration.ofSeconds(5));
        client.setEnvironment("staging");
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    // ------------------------------------------------------------------
    // enableRules() / disableRules() / clearRules() — no-arg "all envs" loops
    // (Flag.java lines 312, 322-324, 334, 346-348, 387, 398-402)
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void enableRules_noArg_enablesEveryConfiguredEnvironment() {
        Flag<Boolean> flag = client.newBooleanFlag("multi", false);
        // Configure two environments first (so the all-envs loop has work to do)
        flag.disableRules("staging");
        flag.disableRules("production");

        flag.enableRules(); // no-arg → iterate all environments

        Map<String, Object> staging = (Map<String, Object>) flag.getEnvironments().get("staging");
        Map<String, Object> production = (Map<String, Object>) flag.getEnvironments().get("production");
        assertEquals(true, staging.get("enabled"));
        assertEquals(true, production.get("enabled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void disableRules_noArg_disablesEveryConfiguredEnvironment() {
        Flag<Boolean> flag = client.newBooleanFlag("multi", false);
        flag.enableRules("staging");
        flag.enableRules("production");

        flag.disableRules(); // no-arg → iterate all environments

        Map<String, Object> staging = (Map<String, Object>) flag.getEnvironments().get("staging");
        Map<String, Object> production = (Map<String, Object>) flag.getEnvironments().get("production");
        assertEquals(false, staging.get("enabled"));
        assertEquals(false, production.get("enabled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearRules_noArg_emptiesRulesInEveryConfiguredEnvironment() {
        Flag<Boolean> flag = client.newBooleanFlag("multi", false);
        flag.addRule(Map.of("environment", "staging", "logic", Map.of("==", List.of(1, 1)), "value", true));
        flag.addRule(Map.of("environment", "production", "logic", Map.of("==", List.of(1, 1)), "value", true));

        flag.clearRules(); // no-arg → iterate all environments

        Map<String, Object> staging = (Map<String, Object>) flag.getEnvironments().get("staging");
        Map<String, Object> production = (Map<String, Object>) flag.getEnvironments().get("production");
        assertTrue(((List<?>) staging.get("rules")).isEmpty());
        assertTrue(((List<?>) production.get("rules")).isEmpty());
    }

    @Test
    void enableRules_noArg_noEnvironments_isNoOp() {
        // Exercises the no-arg → null delegation with an empty environments map
        // (the loop body never runs but the loop header / delegation does).
        Flag<Boolean> flag = client.newBooleanFlag("empty", false);
        flag.enableRules();
        flag.disableRules();
        flag.clearRules();
        assertTrue(flag.getEnvironments().isEmpty());
    }

    // ------------------------------------------------------------------
    // saveAsync() / deleteAsync() no-arg overloads (lines 234, 245, 263, 274)
    // ------------------------------------------------------------------

    @Test
    void saveAsync_noArg_runsOnCommonPool_andPersists() throws Exception {
        // Stub create so save() succeeds; saveAsync() (no-arg) delegates to the
        // common-pool overload which runs save() on the ForkJoin common pool.
        com.smplkit.internal.generated.flags.model.FlagResponse resp =
                makeFlagResponse("async-flag", "BOOLEAN", false);
        when(mockApi.createFlag(any())).thenReturn(resp);

        Flag<Boolean> flag = client.newBooleanFlag("async-flag", false, "Async Flag", null);
        CompletableFuture<Void> fut = flag.saveAsync(); // no-arg → common pool
        fut.get(5, TimeUnit.SECONDS);

        verify(mockApi).createFlag(any());
    }

    @Test
    void saveAsync_withExecutor_runsSaveOnExecutor() throws Exception {
        com.smplkit.internal.generated.flags.model.FlagResponse resp =
                makeFlagResponse("async-flag", "BOOLEAN", false);
        when(mockApi.createFlag(any())).thenReturn(resp);

        Flag<Boolean> flag = client.newBooleanFlag("async-flag", false, "Async Flag", null);
        flag.saveAsync(INLINE).get(5, TimeUnit.SECONDS); // explicit executor overload

        verify(mockApi).createFlag(any());
    }

    @Test
    void deleteAsync_noArg_runsOnCommonPool_andDeletes() throws Exception {
        Flag<Boolean> flag = client.newBooleanFlag("del-flag", false);
        flag.setId("del-flag");

        flag.deleteAsync().get(5, TimeUnit.SECONDS); // no-arg → common pool

        verify(mockApi).deleteFlag("del-flag");
    }

    @Test
    void deleteAsync_withExecutor_runsDeleteOnExecutor() throws Exception {
        Flag<Boolean> flag = client.newBooleanFlag("del-flag", false);
        flag.setId("del-flag");

        flag.deleteAsync(INLINE).get(5, TimeUnit.SECONDS); // explicit executor overload

        verify(mockApi).deleteFlag("del-flag");
    }

    // ------------------------------------------------------------------
    // environments() typed-view defensive branches (lines 93, 95, 101, 105)
    // ------------------------------------------------------------------

    @Test
    void environments_skipsNonMapEnvironmentValue() {
        // Line 93: `if (!(e.getValue() instanceof Map)) continue;`
        Map<String, Object> envs = new HashMap<>();
        envs.put("bad", "not-a-map");        // skipped
        envs.put("good", Map.of("enabled", true));
        Flag<Boolean> flag = new Flag<>(null, "f", "F", "BOOLEAN", false,
                null, null, envs, null, null, Boolean.class);

        Map<String, FlagEnvironment> typed = flag.environments();
        assertFalse(typed.containsKey("bad"));
        assertTrue(typed.containsKey("good"));
    }

    @Test
    void environments_missingEnabled_defaultsToTrue() {
        // Line 95: ternary `: true` branch when "enabled" absent / not a Boolean
        Map<String, Object> envs = new HashMap<>();
        Map<String, Object> env = new HashMap<>();
        env.put("default", "blue"); // no "enabled" key
        envs.put("production", env);
        Flag<String> flag = new Flag<>(null, "f", "F", "STRING", "red",
                null, null, envs, null, null, String.class);

        FlagEnvironment prod = flag.environments().get("production");
        assertTrue(prod.enabled(), "missing 'enabled' should default to true");
    }

    @Test
    void environments_nonBooleanEnabled_defaultsToTrue() {
        // Line 95: `instanceof Boolean` is false → defaults to true
        Map<String, Object> envs = new HashMap<>();
        Map<String, Object> env = new HashMap<>();
        env.put("enabled", "yes"); // a String, not a Boolean
        envs.put("production", env);
        Flag<Boolean> flag = new Flag<>(null, "f", "F", "BOOLEAN", false,
                null, null, envs, null, null, Boolean.class);

        assertTrue(flag.environments().get("production").enabled());
    }

    @Test
    void environments_skipsNonMapRuleEntry() {
        // Line 101: `if (!(r instanceof Map)) continue;` — a rule that isn't a Map
        Map<String, Object> envs = new HashMap<>();
        Map<String, Object> env = new HashMap<>();
        env.put("enabled", true);
        List<Object> rules = new ArrayList<>();
        rules.add("not-a-map");                       // skipped
        rules.add(Map.of("logic", Map.of("==", List.of(1, 1)), "value", true, "description", "ok"));
        env.put("rules", rules);
        envs.put("production", env);
        Flag<Boolean> flag = new Flag<>(null, "f", "F", "BOOLEAN", false,
                null, null, envs, null, null, Boolean.class);

        FlagEnvironment prod = flag.environments().get("production");
        assertEquals(1, prod.rules().size(), "non-Map rule entry should be skipped");
    }

    @Test
    void environments_nonMapLogic_fallsBackToEmptyMap() {
        // Line 105: `logic instanceof Map ? ... : Map.of()` — logic is not a Map
        Map<String, Object> envs = new HashMap<>();
        Map<String, Object> env = new HashMap<>();
        env.put("enabled", true);
        Map<String, Object> rule = new HashMap<>();
        rule.put("logic", "not-a-map"); // non-Map logic → Map.of() fallback
        rule.put("value", true);
        rule.put("description", "weird");
        env.put("rules", List.of(rule));
        envs.put("production", env);
        Flag<Boolean> flag = new Flag<>(null, "f", "F", "BOOLEAN", false,
                null, null, envs, null, null, Boolean.class);

        FlagRule fr = flag.environments().get("production").rules().get(0);
        assertTrue(fr.logic().isEmpty(), "non-Map logic should yield an empty logic map");
        assertEquals(true, fr.value());
    }

    @Test
    void environments_missingLogicKey_fallsBackToEmptyMap() {
        // getOrDefault("logic", Map.of()) returns Map.of() (a Map) — exercises the
        // truthy side of line 105 with the default empty map.
        Map<String, Object> envs = new HashMap<>();
        Map<String, Object> env = new HashMap<>();
        env.put("enabled", true);
        Map<String, Object> rule = new HashMap<>();
        rule.put("value", "x"); // no "logic" key at all
        env.put("rules", List.of(rule));
        envs.put("production", env);
        Flag<String> flag = new Flag<>(null, "f", "F", "STRING", "red",
                null, null, envs, null, null, String.class);

        assertTrue(flag.environments().get("production").rules().get(0).logic().isEmpty());
    }

    // ------------------------------------------------------------------
    // FlagEnvironment / FlagRule frozen-record null branches (line 20 each)
    // ------------------------------------------------------------------

    @Test
    void flagEnvironment_nullRules_becomesEmptyList() {
        FlagEnvironment env = new FlagEnvironment(true, null, null);
        assertNotNull(env.rules());
        assertTrue(env.rules().isEmpty());
    }

    @Test
    void flagRule_nullLogic_becomesEmptyMap() {
        FlagRule rule = new FlagRule(null, "value", "desc");
        assertNotNull(rule.logic());
        assertTrue(rule.logic().isEmpty());
    }

    // ------------------------------------------------------------------
    // _apply with non-null environments (line 467 — env-copy true branch)
    // ------------------------------------------------------------------

    @Test
    void apply_copiesNonNullEnvironmentsDefensively() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("staging", new HashMap<>(Map.of("enabled", true)));
        Flag<Boolean> source = new Flag<>(client, "src", "Src", "BOOLEAN", true,
                List.of(Map.of("name", "On", "value", true)), "d", envs, null, null, Boolean.class);

        Flag<Boolean> target = client.newBooleanFlag("tgt", false);
        target._apply(source);

        assertTrue(target.getEnvironments().containsKey("staging"));
        // Defensive copy: mutating target must not change source
        target.enableRules("brand-new");
        assertFalse(source.getEnvironments().containsKey("brand-new"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static com.smplkit.internal.generated.flags.model.FlagResponse makeFlagResponse(
            String id, String type, Object defaultVal) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("name", id);
        attrs.put("type", type);
        attrs.put("default", defaultVal);
        attrs.put("values", List.of());
        attrs.put("environments", Map.of());
        attrs.put("created_at", "2024-06-01T12:00:00Z");
        attrs.put("updated_at", "2024-06-01T12:00:00Z");
        return FlagsClient.OBJECT_MAPPER.convertValue(
                Map.of("data", Map.of("id", id, "type", "flag", "attributes", attrs)),
                com.smplkit.internal.generated.flags.model.FlagResponse.class);
    }
}
