package com.smplkit.flags;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the per-environment mutation methods on {@link Flag} that mirror the
 * Python helpers: enable/disableRules, the env-scoped setDefault, clearDefault,
 * addValue, removeValue, clearValues.
 */
class FlagMutationsTest {

    private Flag<Boolean> newFlag() {
        try (FlagsClient client = FlagsClient.create("test-key")) {
            return client.newBooleanFlag("my-flag", false);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void enableRules_setsEnabledTrueForEnvironment() {
        Flag<Boolean> flag = newFlag();
        flag.enableRules("production");
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("production");
        assertEquals(true, envData.get("enabled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void disableRules_setsEnabledFalseForEnvironment() {
        Flag<Boolean> flag = newFlag();
        flag.disableRules("staging");
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("staging");
        assertEquals(false, envData.get("enabled"));
    }

    @Test
    void setDefault_envNull_changesFlagLevelDefault() {
        Flag<Boolean> flag = newFlag();
        flag.setDefault(true, null);
        assertTrue(flag.getDefault());
    }

    @Test
    @SuppressWarnings("unchecked")
    void setDefault_perEnv_writesEnvScopedDefault() {
        Flag<Boolean> flag = newFlag();
        flag.setDefault(true, "production");
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("production");
        assertEquals(true, envData.get("default"));
        // Flag-level default unchanged
        assertFalse(flag.getDefault());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearDefault_removesPerEnvDefault() {
        Flag<Boolean> flag = newFlag();
        flag.setDefault(true, "production");
        flag.clearDefault("production");
        Map<String, Object> envData = (Map<String, Object>) flag.getEnvironments().get("production");
        assertFalse(envData.containsKey("default"));
    }

    @Test
    void clearDefault_missingEnv_isNoOp() {
        Flag<Boolean> flag = newFlag();
        // Environment was never written to — should not blow up.
        flag.clearDefault("missing-env");
        assertFalse(flag.getEnvironments().containsKey("missing-env"));
    }

    @Test
    void addValue_appendsToValuesList() {
        Flag<Boolean> flag = newFlag();
        // A fresh BOOLEAN flag already has 2 values (True, False)
        int before = flag.getValues() != null ? flag.getValues().size() : 0;
        flag.addValue("Maybe", "maybe");
        assertEquals(before + 1, flag.getValues().size());
    }

    @Test
    void addValue_initialisesValuesIfNull() {
        Flag<String> flag;
        try (FlagsClient client = FlagsClient.create("test-key")) {
            // String flag with null values list (unconstrained)
            flag = client.newStringFlag("color", "red", null, null, null);
        }
        assertNull(flag.getValues());
        flag.addValue("Red", "red");
        assertNotNull(flag.getValues());
        assertEquals(1, flag.getValues().size());
    }

    @Test
    void addValue_returnsThisForChaining() {
        Flag<Boolean> flag = newFlag();
        assertSame(flag, flag.addValue("Maybe", "maybe"));
    }

    @Test
    void removeValue_dropsMatchingEntry() {
        Flag<Boolean> flag = newFlag();
        int before = flag.getValues().size();
        flag.removeValue(true);
        assertEquals(before - 1, flag.getValues().size());
        // True is gone, False remains
        assertFalse(flag.getValues().stream().anyMatch(v -> Boolean.TRUE.equals(v.get("value"))));
    }

    @Test
    void removeValue_dropsOnlyFirstOfDuplicates() {
        Flag<String> flag;
        try (FlagsClient client = FlagsClient.create("test-key")) {
            flag = client.newStringFlag("color", "red", null, null, null);
        }
        flag.addValue("Red", "red");
        flag.addValue("Crimson", "red");
        flag.addValue("Blue", "blue");
        flag.removeValue("red");
        // Only the first "red" entry is removed; the later duplicate survives.
        assertEquals(2, flag.getValues().size());
        assertEquals("Crimson", flag.getValues().get(0).get("name"));
        assertEquals("red", flag.getValues().get(0).get("value"));
        assertEquals("Blue", flag.getValues().get(1).get("name"));
    }

    @Test
    void removeValue_nullValuesList_isNoOpAndReturnsThis() {
        Flag<String> flag;
        try (FlagsClient client = FlagsClient.create("test-key")) {
            flag = client.newStringFlag("color", "red", null, null, null);
        }
        assertNull(flag.getValues());
        assertSame(flag, flag.removeValue("red"));
    }

    @Test
    void removeValue_returnsThis() {
        Flag<Boolean> flag = newFlag();
        assertSame(flag, flag.removeValue(false));
    }

    @Test
    void clearValues_setsValuesToNull() {
        Flag<Boolean> flag = newFlag();
        flag.clearValues();
        assertNull(flag.getValues());
    }
}
