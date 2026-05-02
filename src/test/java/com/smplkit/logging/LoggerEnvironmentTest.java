package com.smplkit.logging;

import com.smplkit.LogLevel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Mirrors Python rule 8 for logging: typed LoggerEnvironment with LogLevel enum. */
class LoggerEnvironmentTest {

    @Test
    void loggerEnvironment_isFrozenRecord() {
        LoggerEnvironment env = new LoggerEnvironment(LogLevel.WARN);
        assertEquals(LogLevel.WARN, env.level());
        assertEquals(env, new LoggerEnvironment(LogLevel.WARN));
        assertNotEquals(env, new LoggerEnvironment(LogLevel.DEBUG));
    }

    @Test
    void loggerEnvironment_acceptsNullLevel() {
        LoggerEnvironment env = new LoggerEnvironment(null);
        assertNull(env.level());
    }

    @Test
    void logger_environments_returnsTypedLogLevelEnum() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("production", Map.of("level", "ERROR"));
        envs.put("staging", Map.of("level", "DEBUG"));

        Logger lg = new Logger(null, "app.payments", "App Payments",
                null, null, true, null, envs, null, null);
        Map<String, LoggerEnvironment> typed = lg.environments();
        assertEquals(2, typed.size());
        assertEquals(LogLevel.ERROR, typed.get("production").level());
        assertEquals(LogLevel.DEBUG, typed.get("staging").level());
    }

    @Test
    void logger_environments_handlesUnknownLevelGracefully() {
        Map<String, Object> envs = Map.of("staging", Map.of("level", "FUTURISTIC"));
        Logger lg = new Logger(null, "x", "X", null, null, true, null,
                new HashMap<>(envs), null, null);
        Map<String, LoggerEnvironment> typed = lg.environments();
        // Unknown level → null on the typed view
        assertNull(typed.get("staging").level());
    }

    @Test
    void logger_environments_returnedMapIsImmutable() {
        Logger lg = new Logger(null, "x", "X", null, null, true, null,
                new HashMap<>(), null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> lg.environments().put("p", new LoggerEnvironment(LogLevel.INFO)));
    }

    @Test
    void logGroup_environments_returnsTypedView() {
        Map<String, Object> envs = new HashMap<>();
        envs.put("production", Map.of("level", "ERROR"));
        LogGroup grp = new LogGroup(null, "app", "App", null, null, envs, null, null);
        assertEquals(LogLevel.ERROR, grp.environments().get("production").level());
    }
}
