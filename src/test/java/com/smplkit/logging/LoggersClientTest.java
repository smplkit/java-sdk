package com.smplkit.logging;

import com.smplkit.management.SmplManagementClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoggersClientTest {

    @Test
    void newWithIdOnly_defaultsManagedTrue() {
        // Mirrors Python rule 9: mgmt.loggers.new(id) drops the name kwarg
        // and defaults managed=true.
        try (SmplManagementClient mc = SmplManagementClient.create("test-key")) {
            Logger lg = mc.loggers.new_("showcase.payments");
            assertEquals("showcase.payments", lg.getId());
            assertTrue(lg.isManaged());
            assertNotNull(lg.getName());  // auto-derived from id
        }
    }

    @Test
    void newWithExplicitManaged_respectsArgument() {
        try (SmplManagementClient mc = SmplManagementClient.create("test-key")) {
            Logger lg = mc.loggers.new_("showcase.unmanaged", false);
            assertFalse(lg.isManaged());
        }
    }

    @Test
    void logGroupsNew_constructsUnsavedGroup() {
        try (SmplManagementClient mc = SmplManagementClient.create("test-key")) {
            LogGroup grp = mc.logGroups.new_("showcase-group");
            assertEquals("showcase-group", grp.getId());
            assertNotNull(grp.getName());
        }
    }

    @Test
    void logGroupsNewWithParent_setsParentField() {
        try (SmplManagementClient mc = SmplManagementClient.create("test-key")) {
            LogGroup grp = mc.logGroups.new_("showcase-child", "Child", "showcase-parent");
            assertEquals("showcase-child", grp.getId());
            assertEquals("Child", grp.getName());
            assertEquals("showcase-parent", grp.getGroup());
        }
    }
}
