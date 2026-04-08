package com.smplkit.logging;

import com.smplkit.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the LogGroup model class.
 */
class LogGroupTest {

    // -----------------------------------------------------------------------
    // Construction and getters
    // -----------------------------------------------------------------------

    @Test
    void constructor_setsAllFields() {
        Instant now = Instant.now();
        Map<String, Object> envs = Map.of("prod", Map.of("level", "WARN"));

        LogGroup grp = new LogGroup(null, "id-1", "my.group", "My Group",
                "DEBUG", "parent-1", envs, now, now);

        assertEquals("id-1", grp.getId());
        assertEquals("my.group", grp.getKey());
        assertEquals("My Group", grp.getName());
        assertEquals("DEBUG", grp.getLevel());
        assertEquals("parent-1", grp.getGroup());
        assertEquals("WARN", ((Map<?, ?>) grp.getEnvironments().get("prod")).get("level"));
        assertEquals(now, grp.getCreatedAt());
        assertEquals(now, grp.getUpdatedAt());
    }

    @Test
    void constructor_handlesNullEnvironments() {
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, null, null, null);
        assertNotNull(grp.getEnvironments());
        assertTrue(grp.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Setters
    // -----------------------------------------------------------------------

    @Test
    void publicSetters_work() {
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, null, null, null);

        grp.setName("New Name");
        assertEquals("New Name", grp.getName());

        grp.setGroup("parent-2");
        assertEquals("parent-2", grp.getGroup());

        Map<String, Object> envs = new HashMap<>();
        envs.put("staging", Map.of("level", "DEBUG"));
        grp.setEnvironments(envs);
        assertEquals(1, grp.getEnvironments().size());

        grp.setEnvironments(null);
        assertNotNull(grp.getEnvironments());
        assertTrue(grp.getEnvironments().isEmpty());
    }

    @Test
    void packagePrivateSetters_work() {
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, null, null, null);
        Instant now = Instant.now();

        grp.setId("new-id");
        assertEquals("new-id", grp.getId());

        grp.setKey("new-key");
        assertEquals("new-key", grp.getKey());

        grp.setCreatedAt(now);
        assertEquals(now, grp.getCreatedAt());

        grp.setUpdatedAt(now);
        assertEquals(now, grp.getUpdatedAt());

        grp.setLevelRaw("ERROR");
        assertEquals("ERROR", grp.getLevel());

        LoggingClient mockClient = mock(LoggingClient.class);
        grp.setClient(mockClient);
        assertEquals(mockClient, grp.getClient());
    }

    // -----------------------------------------------------------------------
    // Level convenience methods
    // -----------------------------------------------------------------------

    @Test
    void setLevel_setsStringFromEnum() {
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, null, null, null);
        grp.setLevel(LogLevel.WARN);
        assertEquals("WARN", grp.getLevel());
    }

    @Test
    void clearLevel_setsNull() {
        LogGroup grp = new LogGroup(null, null, "key", "name", "INFO", null, null, null, null);
        grp.clearLevel();
        assertNull(grp.getLevel());
    }

    @Test
    void setEnvironmentLevel_addsEntry() {
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, null, null, null);
        grp.setEnvironmentLevel("production", LogLevel.ERROR);
        Map<?, ?> envData = (Map<?, ?>) grp.getEnvironments().get("production");
        assertNotNull(envData);
        assertEquals("ERROR", envData.get("level"));
    }

    @Test
    void clearEnvironmentLevel_removesEntry() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("prod", Map.of("level", "WARN"));
        envs.put("staging", Map.of("level", "DEBUG"));
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, envs, null, null);

        grp.clearEnvironmentLevel("prod");
        assertNull(grp.getEnvironments().get("prod"));
        assertNotNull(grp.getEnvironments().get("staging"));
    }

    @Test
    void clearEnvironmentLevel_noopIfNotPresent() {
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, null, null, null);
        grp.clearEnvironmentLevel("nonexistent");
        assertTrue(grp.getEnvironments().isEmpty());
    }

    @Test
    void clearAllEnvironmentLevels_emptiesMap() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("prod", Map.of("level", "WARN"));
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, envs, null, null);

        grp.clearAllEnvironmentLevels();
        assertTrue(grp.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // save()
    // -----------------------------------------------------------------------

    @Test
    void save_throwsIfNoClient() {
        LogGroup grp = new LogGroup(null, null, "key", "name", null, null, null, null, null);
        assertThrows(IllegalStateException.class, grp::save);
    }

    @Test
    void save_callsCreateWhenIdIsNull() {
        LoggingClient mockClient = mock(LoggingClient.class);
        LogGroup grp = new LogGroup(mockClient, null, "key", "name", null, null, null, null, null);
        LogGroup created = new LogGroup(null, "new-id", "key", "name", null, null, null, Instant.now(), Instant.now());
        when(mockClient._createGroup(grp)).thenReturn(created);

        grp.save();

        verify(mockClient)._createGroup(grp);
        assertEquals("new-id", grp.getId());
    }

    @Test
    void save_callsUpdateWhenIdIsSet() {
        LoggingClient mockClient = mock(LoggingClient.class);
        LogGroup grp = new LogGroup(mockClient, "existing-id", "key", "name", null, null, null, null, null);
        LogGroup updated = new LogGroup(null, "existing-id", "key", "Updated", "INFO", null, null, Instant.now(), Instant.now());
        when(mockClient._updateGroup(grp)).thenReturn(updated);

        grp.save();

        verify(mockClient)._updateGroup(grp);
        assertEquals("Updated", grp.getName());
    }

    // -----------------------------------------------------------------------
    // _apply
    // -----------------------------------------------------------------------

    @Test
    void apply_copiesAllFields() {
        Instant now = Instant.now();
        LogGroup source = new LogGroup(null, "id-1", "key-1", "Name 1", "DEBUG", "parent",
                Map.of("p", Map.of("level", "WARN")), now, now);
        LogGroup target = new LogGroup(null, null, "old", "Old", null, null, null, null, null);

        target._apply(source);

        assertEquals("id-1", target.getId());
        assertEquals("key-1", target.getKey());
        assertEquals("Name 1", target.getName());
        assertEquals("DEBUG", target.getLevel());
        assertEquals("parent", target.getGroup());
        assertNotNull(target.getEnvironments().get("p"));
        assertEquals(now, target.getCreatedAt());
        assertEquals(now, target.getUpdatedAt());
    }

    @Test
    void apply_handlesNullEnvironments() {
        LogGroup source = new LogGroup(null, "id", "key", "Name", null, null, null, null, null);
        LogGroup target = new LogGroup(null, null, "old", "Old", null, null, Map.of("x", "y"), null, null);

        target._apply(source);
        assertNotNull(target.getEnvironments());
        assertTrue(target.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Test
    void toString_includesKeyFields() {
        LogGroup grp = new LogGroup(null, "id-1", "my.group", "My Group", null, null, null, null, null);
        String str = grp.toString();
        assertTrue(str.contains("my.group"));
        assertTrue(str.contains("My Group"));
        assertTrue(str.contains("id-1"));
    }
}
