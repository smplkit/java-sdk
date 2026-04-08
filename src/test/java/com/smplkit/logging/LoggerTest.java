package com.smplkit.logging;

import com.smplkit.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Logger model class.
 */
class LoggerTest {

    // -----------------------------------------------------------------------
    // Construction and getters
    // -----------------------------------------------------------------------

    @Test
    void constructor_setsAllFields() {
        Instant now = Instant.now();
        List<Map<String, Object>> sources = List.of(Map.of("type", "jvm"));
        Map<String, Object> envs = Map.of("prod", Map.of("level", "WARN"));

        Logger lg = new Logger(null, "id-1", "my.logger", "My Logger",
                "DEBUG", "group-1", true, sources, envs, now, now);

        assertEquals("id-1", lg.getId());
        assertEquals("my.logger", lg.getKey());
        assertEquals("My Logger", lg.getName());
        assertEquals("DEBUG", lg.getLevel());
        assertEquals("group-1", lg.getGroup());
        assertTrue(lg.isManaged());
        assertEquals(1, lg.getSources().size());
        assertEquals("WARN", ((Map<?, ?>) lg.getEnvironments().get("prod")).get("level"));
        assertEquals(now, lg.getCreatedAt());
        assertEquals(now, lg.getUpdatedAt());
    }

    @Test
    void constructor_handlesNullSourcesAndEnvironments() {
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, null, null, null);
        assertNotNull(lg.getSources());
        assertTrue(lg.getSources().isEmpty());
        assertNotNull(lg.getEnvironments());
        assertTrue(lg.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Setters
    // -----------------------------------------------------------------------

    @Test
    void publicSetters_work() {
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, null, null, null);

        lg.setName("New Name");
        assertEquals("New Name", lg.getName());

        lg.setGroup("grp-1");
        assertEquals("grp-1", lg.getGroup());

        lg.setManaged(true);
        assertTrue(lg.isManaged());

        Map<String, Object> envs = new HashMap<>();
        envs.put("staging", Map.of("level", "DEBUG"));
        lg.setEnvironments(envs);
        assertEquals(1, lg.getEnvironments().size());

        // Null environments becomes empty map
        lg.setEnvironments(null);
        assertNotNull(lg.getEnvironments());
        assertTrue(lg.getEnvironments().isEmpty());
    }

    @Test
    void packagePrivateSetters_work() {
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, null, null, null);
        Instant now = Instant.now();

        lg.setId("new-id");
        assertEquals("new-id", lg.getId());

        lg.setKey("new-key");
        assertEquals("new-key", lg.getKey());

        lg.setSources(List.of(Map.of("a", "b")));
        assertEquals(1, lg.getSources().size());

        lg.setSources(null);
        assertNotNull(lg.getSources());
        assertTrue(lg.getSources().isEmpty());

        lg.setCreatedAt(now);
        assertEquals(now, lg.getCreatedAt());

        lg.setUpdatedAt(now);
        assertEquals(now, lg.getUpdatedAt());

        lg.setLevelRaw("ERROR");
        assertEquals("ERROR", lg.getLevel());

        LoggingClient mockClient = mock(LoggingClient.class);
        lg.setClient(mockClient);
        assertEquals(mockClient, lg.getClient());
    }

    // -----------------------------------------------------------------------
    // Level convenience methods
    // -----------------------------------------------------------------------

    @Test
    void setLevel_setsStringFromEnum() {
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, null, null, null);

        lg.setLevel(LogLevel.DEBUG);
        assertEquals("DEBUG", lg.getLevel());

        lg.setLevel(LogLevel.WARN);
        assertEquals("WARN", lg.getLevel());
    }

    @Test
    void clearLevel_setsNull() {
        Logger lg = new Logger(null, null, "key", "name", "INFO", null, false, null, null, null, null);
        assertNotNull(lg.getLevel());

        lg.clearLevel();
        assertNull(lg.getLevel());
    }

    @Test
    void setEnvironmentLevel_addsEntry() {
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, null, null, null);

        lg.setEnvironmentLevel("production", LogLevel.ERROR);
        Map<?, ?> envData = (Map<?, ?>) lg.getEnvironments().get("production");
        assertNotNull(envData);
        assertEquals("ERROR", envData.get("level"));
    }

    @Test
    void clearEnvironmentLevel_removesEntry() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("prod", Map.of("level", "WARN"));
        envs.put("staging", Map.of("level", "DEBUG"));
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, envs, null, null);

        lg.clearEnvironmentLevel("prod");
        assertNull(lg.getEnvironments().get("prod"));
        assertNotNull(lg.getEnvironments().get("staging"));
    }

    @Test
    void clearEnvironmentLevel_noopIfNotPresent() {
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, null, null, null);
        lg.clearEnvironmentLevel("nonexistent"); // should not throw
        assertTrue(lg.getEnvironments().isEmpty());
    }

    @Test
    void clearAllEnvironmentLevels_emptiesMap() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("prod", Map.of("level", "WARN"));
        envs.put("staging", Map.of("level", "DEBUG"));
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, envs, null, null);

        lg.clearAllEnvironmentLevels();
        assertTrue(lg.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // save()
    // -----------------------------------------------------------------------

    @Test
    void save_throwsIfNoClient() {
        Logger lg = new Logger(null, null, "key", "name", null, null, false, null, null, null, null);
        assertThrows(IllegalStateException.class, lg::save);
    }

    @Test
    void save_callsCreateWhenIdIsNull() {
        LoggingClient mockClient = mock(LoggingClient.class);
        Logger lg = new Logger(mockClient, null, "key", "name", null, null, false, null, null, null, null);
        Logger created = new Logger(null, "new-id", "key", "name", null, null, false, null, null, Instant.now(), Instant.now());
        when(mockClient._createLogger(lg)).thenReturn(created);

        lg.save();

        verify(mockClient)._createLogger(lg);
        assertEquals("new-id", lg.getId());
    }

    @Test
    void save_callsUpdateWhenIdIsSet() {
        LoggingClient mockClient = mock(LoggingClient.class);
        Logger lg = new Logger(mockClient, "existing-id", "key", "name", null, null, false, null, null, null, null);
        Logger updated = new Logger(null, "existing-id", "key", "Updated Name", "INFO", null, false, null, null, Instant.now(), Instant.now());
        when(mockClient._updateLogger(lg)).thenReturn(updated);

        lg.save();

        verify(mockClient)._updateLogger(lg);
        assertEquals("Updated Name", lg.getName());
    }

    // -----------------------------------------------------------------------
    // _apply
    // -----------------------------------------------------------------------

    @Test
    void apply_copiesAllFields() {
        Instant now = Instant.now();
        Logger source = new Logger(null, "id-1", "key-1", "Name 1", "DEBUG", "grp",
                true, List.of(Map.of("x", "y")), Map.of("p", Map.of("level", "WARN")), now, now);
        Logger target = new Logger(null, null, "old", "Old", null, null, false, null, null, null, null);

        target._apply(source);

        assertEquals("id-1", target.getId());
        assertEquals("key-1", target.getKey());
        assertEquals("Name 1", target.getName());
        assertEquals("DEBUG", target.getLevel());
        assertEquals("grp", target.getGroup());
        assertTrue(target.isManaged());
        assertEquals(1, target.getSources().size());
        assertNotNull(target.getEnvironments().get("p"));
        assertEquals(now, target.getCreatedAt());
        assertEquals(now, target.getUpdatedAt());
    }

    @Test
    void apply_handlesNullSourcesAndEnvironments() {
        Logger source = new Logger(null, "id", "key", "Name", null, null, false, null, null, null, null);
        Logger target = new Logger(null, null, "old", "Old", null, null, false, List.of(Map.of("a", "b")), Map.of("x", "y"), null, null);

        target._apply(source);

        assertNotNull(target.getSources());
        assertTrue(target.getSources().isEmpty());
        assertNotNull(target.getEnvironments());
        assertTrue(target.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Test
    void toString_includesKeyFields() {
        Logger lg = new Logger(null, "id-1", "my.logger", "My Logger", null, null, false, null, null, null, null);
        String str = lg.toString();
        assertTrue(str.contains("my.logger"));
        assertTrue(str.contains("My Logger"));
        assertTrue(str.contains("id-1"));
    }
}
