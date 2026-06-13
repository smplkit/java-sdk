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
 * Tests for the {@link Logger} model class.
 *
 * <p>The active-record {@code save()} delegates to {@link LoggersClient#saveLogger}
 * (an id-addressed upsert PUT); {@code delete()} delegates to
 * {@link LoggersClient#delete}. Both are exercised here against a mocked
 * {@link LoggersClient}.</p>
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

        Logger lg = new Logger(null, "my.logger", "My Logger",
                "DEBUG", "group-1", true, sources, envs, now, now);

        assertEquals("my.logger", lg.getId());
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
        Logger lg = new Logger(null, null, "name", null, null, false, null, null, null, null);
        assertNotNull(lg.getSources());
        assertTrue(lg.getSources().isEmpty());
        assertNotNull(lg.getEnvironments());
        assertTrue(lg.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Public setters
    // -----------------------------------------------------------------------

    @Test
    void publicSetters_work() {
        Logger lg = new Logger(null, null, "name", null, null, false, null, null, null, null);

        lg.setName("New Name");
        assertEquals("New Name", lg.getName());

        lg.setGroup("grp-1");
        assertEquals("grp-1", lg.getGroup());

        lg.setManaged(true);
        assertTrue(lg.isManaged());
    }

    @Test
    void packagePrivateSetters_work() {
        Logger lg = new Logger(null, null, "name", null, null, false, null, null, null, null);

        lg.setId("new-id");
        assertEquals("new-id", lg.getId());

        lg.setLevelRaw("ERROR");
        assertEquals("ERROR", lg.getLevel());

        // setClient rebinds the active-record client; save() now routes to it.
        LoggersClient mockClient = mock(LoggersClient.class);
        Logger returned = new Logger(null, "new-id", "name", null, null, false, null, null, null, null);
        when(mockClient.saveLogger(lg)).thenReturn(returned);
        lg.setClient(mockClient);
        lg.save();
        verify(mockClient).saveLogger(lg);
    }

    // -----------------------------------------------------------------------
    // Level convenience methods
    // -----------------------------------------------------------------------

    @Test
    void setLevel_setsStringFromEnum() {
        Logger lg = new Logger(null, null, "name", null, null, false, null, null, null, null);

        lg.setLevel(LogLevel.DEBUG);
        assertEquals("DEBUG", lg.getLevel());

        lg.setLevel(LogLevel.WARN);
        assertEquals("WARN", lg.getLevel());
    }

    @Test
    void setLevel_nullClearsBaseLevel() {
        Logger lg = new Logger(null, null, "name", "INFO", null, false, null, null, null, null);
        lg.setLevel(null);
        assertNull(lg.getLevel());
    }

    @Test
    void clearLevel_setsNull() {
        Logger lg = new Logger(null, null, "name", "INFO", null, false, null, null, null, null);
        assertNotNull(lg.getLevel());

        lg.clearLevel();
        assertNull(lg.getLevel());
    }

    @Test
    void setLevel_withEnvironment_addsEntry() {
        Logger lg = new Logger(null, null, "name", null, null, false, null, null, null, null);

        lg.setLevel(LogLevel.ERROR, "production");
        Map<?, ?> envData = (Map<?, ?>) lg.getEnvironments().get("production");
        assertNotNull(envData);
        assertEquals("ERROR", envData.get("level"));
    }

    @Test
    void setLevel_withEnvironmentAndNull_storesNullOverride() {
        Logger lg = new Logger(null, null, "name", null, null, false, null, null, null, null);
        lg.setLevel(null, "production");
        Map<?, ?> envData = (Map<?, ?>) lg.getEnvironments().get("production");
        assertNotNull(envData);
        assertNull(envData.get("level"));
    }

    @Test
    void clearLevel_withEnvironment_removesEntry() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("prod", Map.of("level", "WARN"));
        envs.put("staging", Map.of("level", "DEBUG"));
        Logger lg = new Logger(null, null, "name", null, null, false, null, envs, null, null);

        lg.clearLevel("prod");
        assertNull(lg.getEnvironments().get("prod"));
        assertNotNull(lg.getEnvironments().get("staging"));
    }

    @Test
    void clearLevel_withEnvironment_noopIfNotPresent() {
        Logger lg = new Logger(null, null, "name", null, null, false, null, null, null, null);
        lg.clearLevel("nonexistent"); // should not throw
        assertTrue(lg.getEnvironments().isEmpty());
    }

    @Test
    void clearAllEnvironmentLevels_emptiesMap() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("prod", Map.of("level", "WARN"));
        envs.put("staging", Map.of("level", "DEBUG"));
        Logger lg = new Logger(null, null, "name", null, null, false, null, envs, null, null);

        lg.clearAllEnvironmentLevels();
        assertTrue(lg.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // environments() typed view
    // -----------------------------------------------------------------------

    @Test
    void environments_returnsTypedView() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("production", Map.of("level", "ERROR"));
        Logger lg = new Logger(null, "x", "X", null, null, true, null, envs, null, null);

        Map<String, LoggerEnvironment> typed = lg.environments();
        assertEquals(LogLevel.ERROR, typed.get("production").level());
    }

    @Test
    void environments_skipsNonMapEntries() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("production", "not-a-map");
        Logger lg = new Logger(null, "x", "X", null, null, true, null, envs, null, null);
        assertTrue(lg.environments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // save()
    // -----------------------------------------------------------------------

    @Test
    void save_throwsIfNoClient() {
        Logger lg = new Logger(null, null, "name", null, null, false, null, null, null, null);
        assertThrows(IllegalStateException.class, lg::save);
    }

    @Test
    void save_callsSaveLoggerAndAppliesResult() {
        LoggersClient mockClient = mock(LoggersClient.class);
        Logger lg = new Logger(mockClient, "my-logger", "name", null, null, false, null, null, null, null);
        Logger updated = new Logger(null, "created-id", "name", null, null, false, null, null,
                Instant.now(), Instant.now());
        when(mockClient.saveLogger(lg)).thenReturn(updated);

        lg.save();

        verify(mockClient).saveLogger(lg);
        assertEquals("created-id", lg.getId());
    }

    @Test
    void save_existingLogger_copiesUpdatedAttributes() {
        LoggersClient mockClient = mock(LoggersClient.class);
        Instant now = Instant.now();
        Logger lg = new Logger(mockClient, "existing-id", "name", null, null, false, null, null, now, now);
        Logger updated = new Logger(null, "existing-id", "Updated Name", "INFO", null, false, null, null, now, now);
        when(mockClient.saveLogger(lg)).thenReturn(updated);

        lg.save();

        verify(mockClient).saveLogger(lg);
        assertEquals("Updated Name", lg.getName());
        assertEquals("INFO", lg.getLevel());
    }

    @Test
    void saveAsync_runsSaveOnExecutor() {
        LoggersClient mockClient = mock(LoggersClient.class);
        Logger lg = new Logger(mockClient, "id", "name", null, null, false, null, null, null, null);
        Logger updated = new Logger(null, "id", "name", null, null, false, null, null,
                Instant.now(), Instant.now());
        when(mockClient.saveLogger(lg)).thenReturn(updated);

        lg.saveAsync().join();

        verify(mockClient).saveLogger(lg);
    }

    // -----------------------------------------------------------------------
    // delete()
    // -----------------------------------------------------------------------

    @Test
    void delete_callsClientDelete() {
        LoggersClient mockClient = mock(LoggersClient.class);
        Logger lg = new Logger(mockClient, "doomed", "name", null, null, false, null, null, null, null);

        lg.delete();

        verify(mockClient).delete("doomed");
    }

    @Test
    void delete_throwsWhenNoClientOrId() {
        Logger noClient = new Logger(null, "id", "name", null, null, false, null, null, null, null);
        assertThrows(IllegalStateException.class, noClient::delete);

        LoggersClient mockClient = mock(LoggersClient.class);
        Logger noId = new Logger(mockClient, null, "name", null, null, false, null, null, null, null);
        assertThrows(IllegalStateException.class, noId::delete);
    }

    @Test
    void deleteAsync_runsDeleteOnExecutor() {
        LoggersClient mockClient = mock(LoggersClient.class);
        Logger lg = new Logger(mockClient, "doomed", "name", null, null, false, null, null, null, null);

        lg.deleteAsync().join();

        verify(mockClient).delete("doomed");
    }

    // -----------------------------------------------------------------------
    // applyFrom
    // -----------------------------------------------------------------------

    @Test
    void applyFrom_copiesAllFields() {
        Instant now = Instant.now();
        Logger source = new Logger(null, "id-1", "Name 1", "DEBUG", "grp",
                true, List.of(Map.of("x", "y")), Map.of("p", Map.of("level", "WARN")), now, now);
        Logger target = new Logger(null, null, "Old", null, null, false, null, null, null, null);

        target.applyFrom(source);

        assertEquals("id-1", target.getId());
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
    void applyFrom_handlesNullSourcesAndEnvironments() {
        Logger source = new Logger(null, "id", "Name", null, null, false, null, null, null, null);
        Logger target = new Logger(null, null, "Old", null, null, false,
                new ArrayList<>(List.of(Map.of("a", "b"))), new HashMap<>(Map.of("x", "y")), null, null);

        target.applyFrom(source);

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
        Logger lg = new Logger(null, "my.logger", "My Logger", null, null, false, null, null, null, null);
        String str = lg.toString();
        assertTrue(str.contains("my.logger"));
        assertTrue(str.contains("My Logger"));
    }
}
