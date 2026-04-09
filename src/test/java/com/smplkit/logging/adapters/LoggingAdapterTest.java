package com.smplkit.logging.adapters;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify the LoggingAdapter interface contract using a mock implementation.
 */
class LoggingAdapterTest {

    /** Minimal mock adapter for interface verification. */
    static class MockAdapter implements LoggingAdapter {
        final List<DiscoveredLogger> loggers = new ArrayList<>();
        final List<String[]> appliedLevels = new ArrayList<>();
        BiConsumer<String, String> hook;
        boolean hookUninstalled = false;

        @Override
        public String name() {
            return "mock";
        }

        @Override
        public List<DiscoveredLogger> discover() {
            return loggers;
        }

        @Override
        public void applyLevel(String loggerName, String level) {
            appliedLevels.add(new String[]{loggerName, level});
        }

        @Override
        public void installHook(BiConsumer<String, String> onNewLogger) {
            this.hook = onNewLogger;
        }

        @Override
        public void uninstallHook() {
            this.hook = null;
            this.hookUninstalled = true;
        }
    }

    @Test
    void mockAdapter_satisfiesInterface() {
        MockAdapter adapter = new MockAdapter();
        assertEquals("mock", adapter.name());
        assertTrue(adapter.discover().isEmpty());
    }

    @Test
    void mockAdapter_discoversConfiguredLoggers() {
        MockAdapter adapter = new MockAdapter();
        adapter.loggers.add(new DiscoveredLogger("com.acme", "INFO"));
        adapter.loggers.add(new DiscoveredLogger("com.acme.payments", "DEBUG"));

        List<DiscoveredLogger> discovered = adapter.discover();
        assertEquals(2, discovered.size());
        assertEquals("com.acme", discovered.get(0).name());
        assertEquals("INFO", discovered.get(0).level());
    }

    @Test
    void mockAdapter_appliesLevel() {
        MockAdapter adapter = new MockAdapter();
        adapter.applyLevel("com.acme", "WARN");
        adapter.applyLevel("com.acme.payments", "ERROR");

        assertEquals(2, adapter.appliedLevels.size());
        assertArrayEquals(new String[]{"com.acme", "WARN"}, adapter.appliedLevels.get(0));
        assertArrayEquals(new String[]{"com.acme.payments", "ERROR"}, adapter.appliedLevels.get(1));
    }

    @Test
    void mockAdapter_installAndFireHook() {
        MockAdapter adapter = new MockAdapter();
        AtomicReference<String> capturedName = new AtomicReference<>();
        AtomicReference<String> capturedLevel = new AtomicReference<>();

        adapter.installHook((name, level) -> {
            capturedName.set(name);
            capturedLevel.set(level);
        });

        assertNotNull(adapter.hook);
        adapter.hook.accept("com.new.logger", "DEBUG");

        assertEquals("com.new.logger", capturedName.get());
        assertEquals("DEBUG", capturedLevel.get());
    }

    @Test
    void mockAdapter_uninstallHookClearsCallback() {
        MockAdapter adapter = new MockAdapter();
        adapter.installHook((name, level) -> {});
        assertNotNull(adapter.hook);

        adapter.uninstallHook();
        assertNull(adapter.hook);
        assertTrue(adapter.hookUninstalled);
    }

    @Test
    void discoveredLogger_recordFields() {
        DiscoveredLogger dl = new DiscoveredLogger("com.acme", "INFO");
        assertEquals("com.acme", dl.name());
        assertEquals("INFO", dl.level());
    }

    @Test
    void discoveredLogger_equalsAndHashCode() {
        DiscoveredLogger dl1 = new DiscoveredLogger("com.acme", "INFO");
        DiscoveredLogger dl2 = new DiscoveredLogger("com.acme", "INFO");
        DiscoveredLogger dl3 = new DiscoveredLogger("com.other", "DEBUG");

        assertEquals(dl1, dl2);
        assertEquals(dl1.hashCode(), dl2.hashCode());
        assertNotEquals(dl1, dl3);
    }

    @Test
    void discoveredLogger_toString() {
        DiscoveredLogger dl = new DiscoveredLogger("com.acme", "INFO");
        String str = dl.toString();
        assertTrue(str.contains("com.acme"));
        assertTrue(str.contains("INFO"));
    }
}
