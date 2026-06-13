package com.smplkit.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Factory-method tests for the fused logging client's CRUD sub-clients,
 * reachable as {@code client.loggers} and {@code client.logGroups}. A standalone
 * {@link LoggingClient} is built without any network round-trip — the factory
 * methods ({@code new_}) only construct unsaved in-memory models.
 */
class LoggersClientTest {

    @Test
    void newWithIdOnly_defaultsManagedTrue() {
        // Mirrors Python rule 9: loggers.new_(id) drops the name kwarg
        // and defaults managed=true.
        try (LoggingClient client = LoggingClient.builder()
                .apiKey("test-key").build()) {
            Logger lg = client.loggers.new_("showcase.payments");
            assertEquals("showcase.payments", lg.getId());
            assertTrue(lg.isManaged());
            assertNotNull(lg.getName());  // auto-derived from id
        }
    }

    @Test
    void newWithExplicitManaged_respectsArgument() {
        try (LoggingClient client = LoggingClient.builder()
                .apiKey("test-key").build()) {
            Logger lg = client.loggers.new_("showcase.unmanaged", false);
            assertFalse(lg.isManaged());
        }
    }

    @Test
    void logGroupsNew_constructsUnsavedGroup() {
        try (LoggingClient client = LoggingClient.builder()
                .apiKey("test-key").build()) {
            LogGroup grp = client.logGroups.new_("showcase-group");
            assertEquals("showcase-group", grp.getId());
            assertNotNull(grp.getName());
        }
    }

    @Test
    void logGroupsNewWithParent_setsParentField() {
        try (LoggingClient client = LoggingClient.builder()
                .apiKey("test-key").build()) {
            LogGroup grp = client.logGroups.new_("showcase-child", "Child", "showcase-parent");
            assertEquals("showcase-child", grp.getId());
            assertEquals("Child", grp.getName());
            assertEquals("showcase-parent", grp.getGroup());
        }
    }
}
