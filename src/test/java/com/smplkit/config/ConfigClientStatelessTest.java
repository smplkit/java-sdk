package com.smplkit.config;

import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stateless live surface for {@link ConfigClient} — {@code streaming(false)}.
 *
 * <p>The first live call still flushes discovery (best-effort) and fetches +
 * resolves every config once, blocking, but no event stream is opened and
 * registration threshold flushes run inline on the calling thread instead of
 * on a one-shot background thread. Mock verifications run immediately after
 * the triggering call — no latch, no sleep — proving no background thread
 * sits in the path.</p>
 */
class ConfigClientStatelessTest {

    private ConfigsApi mockApi;
    private ConfigClient client;

    @BeforeEach
    void setUp() {
        mockApi = Mockito.mock(ConfigsApi.class);
        client = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key",
                "https://app.smplkit.test", "staging", "test-svc", false);
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    // ------------------------------------------------------------ lazy connect

    @Test
    void statelessConnect_resolvesValuesAndSkipsEventStream() throws Exception {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse("app", Map.of("timeout", 30)));

        assertEquals(30, client.getValue("app", "timeout"));
        assertTrue(client.isConnected());

        // No event stream was created or owned despite the non-null app base URL.
        assertNull(field(client, "eventStream"));
        assertFalse((boolean) field(client, "ownsEventStream"));
    }

    // ---------------------------------------------------- inline threshold flush

    @Test
    void registerConfig_thresholdFlushRunsInline() throws Exception {
        when(mockApi.bulkRegisterConfigs(any())).thenReturn(null);

        for (int i = 0; i < 50; i++) {
            client.registerConfig("cfg-" + i, "svc", "staging", null, null, null);
        }

        // Inline: the bulk POST already happened on this thread.
        verify(mockApi, times(1)).bulkRegisterConfigs(any());
        assertEquals(0, client.pendingCount());
    }

    @Test
    void registerConfigItem_thresholdFlushRunsInline() throws Exception {
        when(mockApi.bulkRegisterConfigs(any())).thenReturn(null);

        // 50 config declarations trip the registerConfig threshold and drain.
        for (int i = 0; i < 50; i++) {
            client.registerConfig("cfg-" + i, "svc", "staging", null, null, null);
        }
        verify(mockApi, times(1)).bulkRegisterConfigs(any());

        // A fresh item per drained config re-creates its pending entry; the
        // 50th trips the registerConfigItem threshold inline.
        for (int i = 0; i < 50; i++) {
            client.registerConfigItem("cfg-" + i, "key", "STRING", "v", null);
        }
        verify(mockApi, times(2)).bulkRegisterConfigs(any());
        assertEquals(0, client.pendingCount());
    }

    @Test
    void registerConfig_thresholdFlushInline_swallowsFailure() throws Exception {
        when(mockApi.bulkRegisterConfigs(any())).thenThrow(new ApiException(503, "unavailable"));

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 50; i++) {
                client.registerConfig("cfg-" + i, "svc", "staging", null, null, null);
            }
        });

        // The inline flush ran and failed best-effort — discovery failures
        // never propagate; drained entries are re-observed on next start.
        verify(mockApi, times(1)).bulkRegisterConfigs(any());
        assertEquals(0, client.pendingCount());
    }

    // ------------------------------------------------------------------ refresh

    @Test
    void refresh_statelessFiresListenersFromDeltas() throws Exception {
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse("app", Map.of("timeout", 30)));

        assertEquals(30, client.getValue("app", "timeout"));
        AtomicReference<ConfigChangeEvent> received = new AtomicReference<>();
        client.onChange("app", "timeout", received::set);

        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(listResponse("app", Map.of("timeout", 60)));
        client.refresh();

        assertEquals(60, client.getValue("app", "timeout"));
        assertNotNull(received.get());
        assertEquals(30, received.get().oldValue());
        assertEquals(60, received.get().newValue());
        assertEquals("manual", received.get().source());
    }

    // ------------------------------------------------------------------ helpers

    private static ConfigListResponse listResponse(String id, Map<String, Object> items) {
        var attrs = new com.smplkit.internal.generated.config.model.Config(null, null);
        attrs.setName(id);
        Map<String, ConfigItemDefinition> defs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : items.entrySet()) {
            ConfigItemDefinition def = new ConfigItemDefinition();
            def.setValue(e.getValue());
            def.setType(e.getValue() instanceof Number
                    ? ConfigItemDefinition.TypeEnum.NUMBER
                    : ConfigItemDefinition.TypeEnum.STRING);
            defs.put(e.getKey(), def);
        }
        attrs.setItems(defs);

        ConfigResource resource = new ConfigResource();
        resource.setId(id);
        resource.setType(ConfigResource.TypeEnum.CONFIG);
        resource.setAttributes(attrs);

        ConfigListResponse response = new ConfigListResponse();
        response.setData(List.of(resource));
        return response;
    }

    private static Object field(ConfigClient client, String name) throws Exception {
        Field f = ConfigClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(client);
    }
}
