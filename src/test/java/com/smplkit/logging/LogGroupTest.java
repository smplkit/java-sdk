package com.smplkit.logging;

import com.smplkit.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the {@link LogGroup} model class.
 *
 * <p>The active-record {@code save()} delegates to {@link LogGroupsClient#saveGroup}
 * (which POSTs a brand-new group and PUTs an existing one); {@code delete()}
 * delegates to {@link LogGroupsClient#delete}.</p>
 */
class LogGroupTest {

    // -----------------------------------------------------------------------
    // Construction and getters
    // -----------------------------------------------------------------------

    @Test
    void constructor_setsAllFields() {
        Instant now = Instant.now();
        Map<String, Object> envs = Map.of("prod", Map.of("level", "WARN"));

        LogGroup grp = new LogGroup(null, "my.group", "My Group",
                "DEBUG", "parent-1", envs, now, now);

        assertEquals("my.group", grp.getId());
        assertEquals("My Group", grp.getName());
        assertEquals("DEBUG", grp.getLevel());
        assertEquals("parent-1", grp.getGroup());
        assertEquals("WARN", ((Map<?, ?>) grp.getEnvironments().get("prod")).get("level"));
        assertEquals(now, grp.getCreatedAt());
        assertEquals(now, grp.getUpdatedAt());
    }

    @Test
    void constructor_handlesNullEnvironments() {
        LogGroup grp = new LogGroup(null, null, "name", null, null, null, null, null);
        assertNotNull(grp.getEnvironments());
        assertTrue(grp.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Public setters
    // -----------------------------------------------------------------------

    @Test
    void publicSetters_work() {
        LogGroup grp = new LogGroup(null, null, "name", null, null, null, null, null);

        grp.setName("New Name");
        assertEquals("New Name", grp.getName());

        grp.setGroup("parent-2");
        assertEquals("parent-2", grp.getGroup());
    }

    @Test
    void packagePrivateSetters_work() {
        LogGroup grp = new LogGroup(null, null, "name", null, null, null, null, null);

        grp.setId("new-id");
        assertEquals("new-id", grp.getId());

        grp.setLevelRaw("ERROR");
        assertEquals("ERROR", grp.getLevel());

        LogGroupsClient mockClient = mock(LogGroupsClient.class);
        LogGroup returned = new LogGroup(null, "new-id", "name", null, null, null,
                Instant.now(), Instant.now());
        when(mockClient.saveGroup(grp)).thenReturn(returned);
        grp.setClient(mockClient);
        grp.save();
        verify(mockClient).saveGroup(grp);
    }

    // -----------------------------------------------------------------------
    // Level convenience methods
    // -----------------------------------------------------------------------

    @Test
    void setLevel_setsStringFromEnum() {
        LogGroup grp = new LogGroup(null, null, "name", null, null, null, null, null);
        grp.setLevel(LogLevel.WARN);
        assertEquals("WARN", grp.getLevel());
    }

    @Test
    void clearLevel_setsNull() {
        LogGroup grp = new LogGroup(null, null, "name", "INFO", null, null, null, null);
        grp.clearLevel();
        assertNull(grp.getLevel());
    }

    @Test
    void setLevel_withEnvironment_addsEntry() {
        LogGroup grp = new LogGroup(null, null, "name", null, null, null, null, null);
        grp.setLevel(LogLevel.ERROR, "production");
        Map<?, ?> envData = (Map<?, ?>) grp.getEnvironments().get("production");
        assertNotNull(envData);
        assertEquals("ERROR", envData.get("level"));
    }

    @Test
    void clearLevel_withEnvironment_removesEntry() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("prod", Map.of("level", "WARN"));
        envs.put("staging", Map.of("level", "DEBUG"));
        LogGroup grp = new LogGroup(null, null, "name", null, null, envs, null, null);

        grp.clearLevel("prod");
        assertNull(grp.getEnvironments().get("prod"));
        assertNotNull(grp.getEnvironments().get("staging"));
    }

    @Test
    void clearLevel_withEnvironment_noopIfNotPresent() {
        LogGroup grp = new LogGroup(null, null, "name", null, null, null, null, null);
        grp.clearLevel("nonexistent");
        assertTrue(grp.getEnvironments().isEmpty());
    }

    @Test
    void clearAllEnvironmentLevels_emptiesMap() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("prod", Map.of("level", "WARN"));
        LogGroup grp = new LogGroup(null, null, "name", null, null, envs, null, null);

        grp.clearAllEnvironmentLevels();
        assertTrue(grp.getEnvironments().isEmpty());
    }

    @Test
    void environments_returnsTypedView() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("production", Map.of("level", "ERROR"));
        LogGroup grp = new LogGroup(null, "app", "App", null, null, envs, null, null);
        assertEquals(LogLevel.ERROR, grp.environments().get("production").level());
    }

    // -----------------------------------------------------------------------
    // save()
    // -----------------------------------------------------------------------

    @Test
    void save_throwsIfNoClient() {
        LogGroup grp = new LogGroup(null, null, "name", null, null, null, null, null);
        assertThrows(IllegalStateException.class, grp::save);
    }

    @Test
    void save_newGroup_appliesCreatedResult() {
        LogGroupsClient mockClient = mock(LogGroupsClient.class);
        LogGroup grp = new LogGroup(mockClient, "my-group", "name", null, null, null, null, null);
        LogGroup created = new LogGroup(null, "created-grp", "name", null, null, null,
                Instant.now(), Instant.now());
        when(mockClient.saveGroup(grp)).thenReturn(created);

        grp.save();

        verify(mockClient).saveGroup(grp);
        assertEquals("created-grp", grp.getId());
    }

    @Test
    void save_existingGroup_copiesUpdatedAttributes() {
        LogGroupsClient mockClient = mock(LogGroupsClient.class);
        Instant now = Instant.now();
        LogGroup grp = new LogGroup(mockClient, "existing-id", "name", null, null, null, now, now);
        LogGroup updated = new LogGroup(null, "existing-id", "Updated", "INFO", null, null, now, now);
        when(mockClient.saveGroup(grp)).thenReturn(updated);

        grp.save();

        verify(mockClient).saveGroup(grp);
        assertEquals("Updated", grp.getName());
    }

    @Test
    void saveAsync_runsSaveOnExecutor() {
        LogGroupsClient mockClient = mock(LogGroupsClient.class);
        LogGroup grp = new LogGroup(mockClient, "id", "name", null, null, null, null, null);
        LogGroup returned = new LogGroup(null, "id", "name", null, null, null,
                Instant.now(), Instant.now());
        when(mockClient.saveGroup(grp)).thenReturn(returned);

        grp.saveAsync().join();

        verify(mockClient).saveGroup(grp);
    }

    // -----------------------------------------------------------------------
    // delete()
    // -----------------------------------------------------------------------

    @Test
    void delete_callsClientDelete() {
        LogGroupsClient mockClient = mock(LogGroupsClient.class);
        LogGroup grp = new LogGroup(mockClient, "doomed", "name", null, null, null, null, null);

        grp.delete();

        verify(mockClient).delete("doomed");
    }

    @Test
    void delete_throwsWhenNoClientOrId() {
        LogGroup noClient = new LogGroup(null, "id", "name", null, null, null, null, null);
        assertThrows(IllegalStateException.class, noClient::delete);

        LogGroupsClient mockClient = mock(LogGroupsClient.class);
        LogGroup noId = new LogGroup(mockClient, null, "name", null, null, null, null, null);
        assertThrows(IllegalStateException.class, noId::delete);
    }

    @Test
    void deleteAsync_runsDeleteOnExecutor() {
        LogGroupsClient mockClient = mock(LogGroupsClient.class);
        LogGroup grp = new LogGroup(mockClient, "doomed", "name", null, null, null, null, null);

        grp.deleteAsync().join();

        verify(mockClient).delete("doomed");
    }

    // -----------------------------------------------------------------------
    // applyFrom
    // -----------------------------------------------------------------------

    @Test
    void applyFrom_copiesAllFields() {
        Instant now = Instant.now();
        LogGroup source = new LogGroup(null, "id-1", "Name 1", "DEBUG", "parent",
                Map.of("p", Map.of("level", "WARN")), now, now);
        LogGroup target = new LogGroup(null, null, "Old", null, null, null, null, null);

        target.applyFrom(source);

        assertEquals("id-1", target.getId());
        assertEquals("Name 1", target.getName());
        assertEquals("DEBUG", target.getLevel());
        assertEquals("parent", target.getGroup());
        assertNotNull(target.getEnvironments().get("p"));
        assertEquals(now, target.getCreatedAt());
        assertEquals(now, target.getUpdatedAt());
    }

    @Test
    void applyFrom_handlesNullEnvironments() {
        LogGroup source = new LogGroup(null, "id", "Name", null, null, null, null, null);
        LogGroup target = new LogGroup(null, null, "Old", null, null,
                new HashMap<>(Map.of("x", "y")), null, null);

        target.applyFrom(source);
        assertNotNull(target.getEnvironments());
        assertTrue(target.getEnvironments().isEmpty());
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Test
    void toString_includesKeyFields() {
        LogGroup grp = new LogGroup(null, "my.group", "My Group", null, null, null, null, null);
        String str = grp.toString();
        assertTrue(str.contains("my.group"));
        assertTrue(str.contains("My Group"));
    }
}
