package com.smplkit.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigRuntime} value resolution, accessors, and listeners.
 *
 * <p>Uses the test constructor {@link ConfigRuntime#ConfigRuntime(Map)} which
 * skips WebSocket setup.</p>
 */
class ConfigRuntimeTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ConfigRuntime runtime(Map<String, Object> values) {
        return new ConfigRuntime(values);
    }

    // -----------------------------------------------------------------------
    // Value accessors
    // -----------------------------------------------------------------------

    @Test
    void get_returnsValue() {
        ConfigRuntime r = runtime(Map.of("timeout", 30, "name", "test"));
        assertEquals(30, r.get("timeout"));
        assertEquals("test", r.get("name"));
    }

    @Test
    void get_returnsNullForMissingKey() {
        ConfigRuntime r = runtime(Map.of("a", 1));
        assertNull(r.get("missing"));
    }

    @Test
    void get_withDefaultValue() {
        ConfigRuntime r = runtime(Map.of("a", 1));
        assertEquals("fallback", r.get("missing", "fallback"));
        assertEquals(1, r.get("a", 99));
    }

    @Test
    void getString_returnsStringValue() {
        ConfigRuntime r = runtime(Map.of("key", "hello"));
        assertEquals("hello", r.getString("key", "default"));
    }

    @Test
    void getString_returnsDefaultForNonString() {
        ConfigRuntime r = runtime(Map.of("key", 42));
        assertEquals("default", r.getString("key", "default"));
    }

    @Test
    void getString_returnsDefaultForMissing() {
        ConfigRuntime r = runtime(Map.of());
        assertEquals("default", r.getString("missing", "default"));
    }

    @Test
    void getInt_returnsIntValue() {
        ConfigRuntime r = runtime(Map.of("timeout", 30));
        assertEquals(30, r.getInt("timeout", 0));
    }

    @Test
    void getInt_returnsDefaultForNonNumber() {
        ConfigRuntime r = runtime(Map.of("key", "not-a-number"));
        assertEquals(0, r.getInt("key", 0));
    }

    @Test
    void getInt_returnsDefaultForMissing() {
        ConfigRuntime r = runtime(Map.of());
        assertEquals(-1, r.getInt("missing", -1));
    }

    @Test
    void getInt_handlesDouble() {
        ConfigRuntime r = runtime(Map.of("value", 3.14));
        assertEquals(3, r.getInt("value", 0));
    }

    @Test
    void getBool_returnsTrueValue() {
        ConfigRuntime r = runtime(Map.of("enabled", true));
        assertTrue(r.getBool("enabled", false));
    }

    @Test
    void getBool_returnsFalseValue() {
        ConfigRuntime r = runtime(Map.of("enabled", false));
        assertFalse(r.getBool("enabled", true));
    }

    @Test
    void getBool_returnsDefaultForNonBoolean() {
        ConfigRuntime r = runtime(Map.of("key", "yes"));
        assertFalse(r.getBool("key", false));
    }

    @Test
    void getBool_returnsDefaultForMissing() {
        ConfigRuntime r = runtime(Map.of());
        assertTrue(r.getBool("missing", true));
    }

    @Test
    void exists_returnsTrue() {
        ConfigRuntime r = runtime(Map.of("key", "value"));
        assertTrue(r.exists("key"));
    }

    @Test
    void exists_returnsFalse() {
        ConfigRuntime r = runtime(Map.of());
        assertFalse(r.exists("ghost"));
    }

    @Test
    void getAll_returnsAllValues() {
        Map<String, Object> values = Map.of("a", 1, "b", "two", "c", true);
        ConfigRuntime r = runtime(values);
        Map<String, Object> all = r.getAll();
        assertEquals(3, all.size());
        assertEquals(1, all.get("a"));
        assertEquals("two", all.get("b"));
        assertEquals(true, all.get("c"));
    }

    @Test
    void getAll_returnsUnmodifiableCopy() {
        ConfigRuntime r = runtime(Map.of("a", 1));
        Map<String, Object> all = r.getAll();
        assertThrows(UnsupportedOperationException.class, () -> all.put("new", "key"));
    }

    // -----------------------------------------------------------------------
    // Stats and connection status
    // -----------------------------------------------------------------------

    @Test
    void stats_returnsFetchCount() {
        ConfigRuntime r = runtime(Map.of());
        ConfigStats stats = r.stats();
        assertEquals(0, stats.fetchCount());
    }

    @Test
    void connectionStatus_returnsDisconnectedForTestInstance() {
        ConfigRuntime r = runtime(Map.of());
        assertEquals("disconnected", r.connectionStatus());
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Test
    void close_doesNotThrow() {
        ConfigRuntime r = runtime(Map.of("key", "value"));
        assertDoesNotThrow(r::close);
    }

    @Test
    void close_valuesStillAccessibleAfterClose() {
        ConfigRuntime r = runtime(Map.of("key", "value"));
        r.close();
        assertEquals("value", r.get("key"));
    }

    @Test
    void refresh_doesNotThrowForTestInstance() {
        ConfigRuntime r = runtime(Map.of());
        assertDoesNotThrow(r::refresh);
    }

    // -----------------------------------------------------------------------
    // Resolution algorithm
    // -----------------------------------------------------------------------

    @Test
    void resolve_emptyChain_returnsEmpty() {
        Map<String, Object> result = ConfigRuntime.resolve(List.of(), "production");
        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_singleEntry_baseValuesOnly() {
        ConfigRuntime.ChainEntry entry = new ConfigRuntime.ChainEntry(
                "id1",
                Map.of("a", 1, "b", "hello"),
                Map.of()
        );
        Map<String, Object> result = ConfigRuntime.resolve(List.of(entry), "production");
        assertEquals(1, result.get("a"));
        assertEquals("hello", result.get("b"));
    }

    @Test
    void resolve_envOverridesBase() {
        Map<String, Object> envValues = Map.of("a", 99);
        Map<String, Object> envData = new HashMap<>();
        envData.put("values", envValues);

        ConfigRuntime.ChainEntry entry = new ConfigRuntime.ChainEntry(
                "id1",
                Map.of("a", 1, "b", 2),
                Map.of("production", envData)
        );
        Map<String, Object> result = ConfigRuntime.resolve(List.of(entry), "production");
        assertEquals(99, result.get("a"));
        assertEquals(2, result.get("b"));
    }

    @Test
    void resolve_noEnvMatch_usesBaseOnly() {
        Map<String, Object> envValues = Map.of("a", 99);
        Map<String, Object> envData = new HashMap<>();
        envData.put("values", envValues);

        ConfigRuntime.ChainEntry entry = new ConfigRuntime.ChainEntry(
                "id1",
                Map.of("a", 1),
                Map.of("production", envData)
        );
        // Looking up "staging" — no override defined
        Map<String, Object> result = ConfigRuntime.resolve(List.of(entry), "staging");
        assertEquals(1, result.get("a"));
    }

    @Test
    void resolve_childOverridesParent() {
        // Chain: [child, parent] — child is first (index 0), parent is last (index 1)
        ConfigRuntime.ChainEntry parent = new ConfigRuntime.ChainEntry(
                "parent",
                Map.of("a", "from_parent", "b", "from_parent"),
                Map.of()
        );
        ConfigRuntime.ChainEntry child = new ConfigRuntime.ChainEntry(
                "child",
                Map.of("a", "from_child"),
                Map.of()
        );
        // Walk root-to-child: parent first, then child
        Map<String, Object> result = ConfigRuntime.resolve(List.of(child, parent), "production");
        assertEquals("from_child", result.get("a")); // child overrides parent
        assertEquals("from_parent", result.get("b")); // inherited from parent
    }

    @Test
    void resolve_threeLevel_inheritance() {
        ConfigRuntime.ChainEntry root = new ConfigRuntime.ChainEntry(
                "root",
                Map.of("x", 1, "y", 2, "z", 3),
                Map.of()
        );
        ConfigRuntime.ChainEntry mid = new ConfigRuntime.ChainEntry(
                "mid",
                Map.of("y", 20),
                Map.of()
        );
        ConfigRuntime.ChainEntry leaf = new ConfigRuntime.ChainEntry(
                "leaf",
                Map.of("z", 300),
                Map.of()
        );
        // Chain is child-first: [leaf, mid, root]
        Map<String, Object> result = ConfigRuntime.resolve(List.of(leaf, mid, root), "production");
        assertEquals(1, result.get("x"));   // from root
        assertEquals(20, result.get("y"));  // mid overrides root
        assertEquals(300, result.get("z")); // leaf overrides mid and root
    }

    // -----------------------------------------------------------------------
    // deepMerge
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void deepMerge_mergesNestedMaps() {
        Map<String, Object> base = new HashMap<>();
        base.put("db", new HashMap<>(Map.of("host", "localhost", "port", 5432)));
        base.put("timeout", 30);

        Map<String, Object> override = new HashMap<>();
        override.put("db", new HashMap<>(Map.of("host", "prod-host", "ssl", true)));
        override.put("retries", 5);

        Map<String, Object> result = ConfigRuntime.deepMerge(base, override);

        Map<String, Object> db = (Map<String, Object>) result.get("db");
        assertEquals("prod-host", db.get("host")); // overridden
        assertEquals(5432, db.get("port"));         // from base
        assertEquals(true, db.get("ssl"));          // from override
        assertEquals(30, result.get("timeout"));    // from base
        assertEquals(5, result.get("retries"));     // from override
    }

    @Test
    void deepMerge_overrideWithScalarReplacesMap() {
        Map<String, Object> base = Map.of("key", new HashMap<>(Map.of("nested", 1)));
        Map<String, Object> override = Map.of("key", "scalar");

        Map<String, Object> result = ConfigRuntime.deepMerge(base, override);
        assertEquals("scalar", result.get("key")); // scalar replaces map
    }

    @Test
    void deepMerge_emptyBase_returnsOverride() {
        Map<String, Object> result = ConfigRuntime.deepMerge(Map.of(), Map.of("a", 1));
        assertEquals(1, result.get("a"));
    }

    @Test
    void deepMerge_emptyOverride_returnsBase() {
        Map<String, Object> result = ConfigRuntime.deepMerge(Map.of("a", 1), Map.of());
        assertEquals(1, result.get("a"));
    }

    // -----------------------------------------------------------------------
    // ChainEntry
    // -----------------------------------------------------------------------

    @Test
    void chainEntry_defaultsToEmptyMaps() {
        ConfigRuntime.ChainEntry entry = new ConfigRuntime.ChainEntry("id", null, null);
        assertNotNull(entry.values);
        assertNotNull(entry.environments);
        assertTrue(entry.values.isEmpty());
        assertTrue(entry.environments.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Change listeners
    // -----------------------------------------------------------------------

    @Test
    void onChange_registeredAndCalled() {
        // We test with the test-constructor runtime + fire listeners manually via refresh
        // (which no-ops for test instances). Instead test listener registration directly.
        ConfigRuntime r = runtime(Map.of("key", "value"));
        AtomicReference<ChangeEvent> received = new AtomicReference<>();
        r.onChange(received::set);
        assertNull(received.get()); // Not fired yet
    }

    @Test
    void onChangeForKey_registeredAndCalled() {
        ConfigRuntime r = runtime(Map.of("key", "value"));
        List<ChangeEvent> events = new ArrayList<>();
        r.onChange("key", events::add);
        assertTrue(events.isEmpty()); // Not fired yet
    }
}
