package com.smplkit.config;

import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigBulkItem;
import com.smplkit.internal.generated.config.model.ConfigBulkRequest;
import com.smplkit.internal.generated.config.model.ConfigBulkResponse;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.management.ConfigRegistrationBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the declarative discovery API (ADR-037 §2.13/§2.14):
 *   1. {@link ConfigRegistrationBuffer} — declare/add_item/drain semantics.
 *   2. {@link ConfigManagement} — registerConfig/registerConfigItem/flush.
 *   3. {@link ConfigClient#getOrCreate} — idempotency, parent-by-reference,
 *      pre-fetch flush wiring.
 *   4. {@link LiveConfigProxy} typed getters — happy paths, mismatch paths,
 *      default-fallback paths.
 */
class ConfigDiscoveryTest {

    private ConfigsApi mockApi;
    private ConfigClient client;

    @BeforeEach
    void setUp() throws ApiException {
        mockApi = Mockito.mock(ConfigsApi.class);
        client = new ConfigClient(mockApi, HttpClient.newHttpClient(), "test-key");
        client.setEnvironment("production");
        client.setService("test-service");
        // Default stub so lazy init doesn't fail
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class)))
                .thenReturn(new ConfigBulkResponse());
    }

    private static void seedCache(ConfigClient client, String id, Map<String, Object> values) {
        try {
            // Force the client into a "connected" state so a subsequent
            // get/getOrCreate does NOT trigger lazy init (which would rebuild
            // the cache from the empty list mock and blow away our seed).
            Field connected = ConfigClient.class.getDeclaredField("connected");
            connected.setAccessible(true);
            connected.setBoolean(client, true);

            Field f = ConfigClient.class.getDeclaredField("configCache");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) f.get(client);
            Map<String, Map<String, Object>> mutable = new HashMap<>(cache);
            mutable.put(id, values);
            f.set(client, mutable);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================================================================
    // 1. ConfigRegistrationBuffer
    // ==================================================================

    @Test
    void buffer_declare_recordsMetaAndPending() {
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.declare("billing", "svc", "prod", null, "Billing", "Plan limits");
        assertEquals(1, buf.pendingCount());
    }

    @Test
    void buffer_declare_firstWriterWins() {
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.declare("billing", "svc-a", "prod", null, "Billing-A", "first");
        buf.declare("billing", "svc-b", "stg", null, "Billing-B", "second");
        List<ConfigRegistrationBuffer.Entry> batch = buf.drain();
        assertEquals(1, batch.size());
        assertEquals("svc-a", batch.get(0).service);
        assertEquals("prod", batch.get(0).environment);
        assertEquals("Billing-A", batch.get(0).name);
        assertEquals("first", batch.get(0).description);
    }

    @Test
    void buffer_addItem_noMeta_isNoOp() {
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.addItem("billing", "max_seats", "NUMBER", 5, null);
        assertEquals(0, buf.pendingCount());
        assertTrue(buf.drain().isEmpty());
    }

    @Test
    void buffer_addItem_afterDeclare_queuesItem() {
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.declare("billing", "svc", "prod", null, null, null);
        buf.addItem("billing", "max_seats", "NUMBER", 5, "Default seats.");
        List<ConfigRegistrationBuffer.Entry> batch = buf.drain();
        assertEquals(1, batch.size());
        assertEquals(1, batch.get(0).items.size());
        ConfigRegistrationBuffer.ItemEntry item = batch.get(0).items.get("max_seats");
        assertEquals(5, item.defaultValue);
        assertEquals("NUMBER", item.itemType);
        assertEquals("Default seats.", item.description);
    }

    @Test
    void buffer_addItem_secondTimeSameKey_isNoOp() {
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.declare("billing", "svc", "prod", null, null, null);
        buf.addItem("billing", "max_seats", "NUMBER", 5, null);
        buf.addItem("billing", "max_seats", "NUMBER", 99, "different");
        List<ConfigRegistrationBuffer.Entry> batch = buf.drain();
        assertEquals(1, batch.get(0).items.size());
        assertEquals(5, batch.get(0).items.get("max_seats").defaultValue);
    }

    @Test
    void buffer_drain_clearsPending() {
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.declare("a", "svc", "env", null, null, null);
        assertEquals(1, buf.pendingCount());
        buf.drain();
        assertEquals(0, buf.pendingCount());
    }

    @Test
    void buffer_drain_emptyReturnsEmpty() {
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        assertTrue(buf.drain().isEmpty());
    }

    @Test
    void buffer_postDrain_itemAlreadySent_notResentEvenAfterRequeue() {
        // Drain "billing+max_seats", then add same item — must not appear
        // again (sent-item dedup is process-lifetime).
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.declare("billing", "svc", "prod", null, null, null);
        buf.addItem("billing", "max_seats", "NUMBER", 5, null);
        List<ConfigRegistrationBuffer.Entry> batch1 = buf.drain();
        assertEquals(1, batch1.get(0).items.size());

        buf.addItem("billing", "max_seats", "NUMBER", 5, null);
        assertEquals(0, buf.pendingCount());
        assertTrue(buf.drain().isEmpty());
    }

    @Test
    void buffer_postDrain_newItemAttributesToSameConfig() {
        // After draining a config's first item, a new item should re-create
        // a delta entry (using retained meta) so it can be sent.
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.declare("billing", "svc", "prod", null, "Billing", null);
        buf.addItem("billing", "first", "STRING", "a", null);
        buf.drain();

        buf.addItem("billing", "second", "NUMBER", 7, "added later");
        List<ConfigRegistrationBuffer.Entry> batch = buf.drain();
        assertEquals(1, batch.size());
        assertEquals("billing", batch.get(0).id);
        assertEquals("Billing", batch.get(0).name);
        assertEquals(1, batch.get(0).items.size());
        assertTrue(batch.get(0).items.containsKey("second"));
    }

    @Test
    void buffer_declare_withParent_retainsParent() {
        ConfigRegistrationBuffer buf = new ConfigRegistrationBuffer();
        buf.declare("billing", "svc", "prod", "common", null, null);
        List<ConfigRegistrationBuffer.Entry> batch = buf.drain();
        assertEquals("common", batch.get(0).parent);
    }

    // ==================================================================
    // 2. ConfigManagement.register* / flush
    // ==================================================================

    @Test
    void mgmt_flush_noPending_doesNothing() throws Exception {
        client.management().flush();
        verify(mockApi, never()).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    @Test
    void mgmt_registerConfig_queuesDeclaration_flushPosts() throws Exception {
        client.management().registerConfig(
                "billing", "svc", "prod", "common", "Billing", "Plan limits");
        assertEquals(1, client.management().pendingCount());

        client.management().flush();
        assertEquals(0, client.management().pendingCount());

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi, times(1)).bulkRegisterConfigs(captor.capture());
        List<ConfigBulkItem> sent = captor.getValue().getConfigs();
        assertEquals(1, sent.size());
        assertEquals("billing", sent.get(0).getId());
        assertEquals("common", sent.get(0).getParent());
        assertEquals("Billing", sent.get(0).getName());
        assertEquals("Plan limits", sent.get(0).getDescription());
    }

    @Test
    void mgmt_registerConfigItem_flushIncludesAllTypes() throws Exception {
        client.management().registerConfig("billing", "svc", "prod", null, null, null);
        client.management().registerConfigItem("billing", "max_seats", "NUMBER", 5, "seats");
        client.management().registerConfigItem("billing", "tier", "STRING", "free", null);
        client.management().registerConfigItem("billing", "enabled", "BOOLEAN", false, null);
        client.management().registerConfigItem("billing", "payload", "JSON", null, null);
        client.management().registerConfigItem("billing", "weird", "WEIRD", "?", null);

        client.management().flush();

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        Map<String, ConfigItemDefinition> items = captor.getValue().getConfigs().get(0).getItems();
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, items.get("tier").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER, items.get("max_seats").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN, items.get("enabled").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.JSON, items.get("payload").getType());
        // Unknown item type maps to null.
        assertNull(items.get("weird").getType());
    }

    @Test
    void mgmt_flush_serverError_swallowed() throws Exception {
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class)))
                .thenThrow(new RuntimeException("boom"));
        client.management().registerConfig("billing", "svc", "prod", null, null, null);
        // Per ADR-024 §2.9: bulk POST failures are fire-and-forget. Should not throw.
        assertDoesNotThrow(() -> client.management().flush());
        // Items are still drained — discovery never blocks customer code.
        assertEquals(0, client.management().pendingCount());
    }

    @Test
    void mgmt_registerConfig_thresholdTriggersBackgroundFlush() throws Exception {
        // Threshold is 50 declarations.
        for (int i = 0; i < 51; i++) {
            client.management().registerConfig("cfg-" + i, "svc", "prod", null, null, null);
        }
        Thread t = client.management().lastFlushThread;
        assertNotNull(t, "expected a background flush thread to be spawned");
        t.join(5000);
        verify(mockApi, times(1)).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    @Test
    void mgmt_registerConfigItem_thresholdTriggersBackgroundFlush() throws Exception {
        // 50 distinct config declarations brings us to the threshold.
        for (int i = 0; i < 50; i++) {
            client.management().registerConfig("cfg-" + i, "svc", "prod", null, null, null);
        }
        client.management().registerConfig("cfg-extra", "svc", "prod", null, null, null);
        client.management().registerConfigItem("cfg-extra", "k", "STRING", "v", null);
        Thread t = client.management().lastFlushThread;
        assertNotNull(t);
        t.join(5000);
        verify(mockApi, Mockito.atLeastOnce()).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    // ==================================================================
    // 3. ConfigClient.getOrCreate
    // ==================================================================

    @Test
    void getOrCreate_returnsLiveConfigProxy() {
        LiveConfigProxy proxy = client.getOrCreate("billing", "discovered");
        assertNotNull(proxy);
        assertEquals("billing", proxy.configId());
    }

    @Test
    void getOrCreate_idempotent_returnsSameInstance() {
        LiveConfigProxy a = client.getOrCreate("billing");
        LiveConfigProxy b = client.getOrCreate("billing");
        assertSame(a, b);
    }

    @Test
    void getOrCreate_get_returnsSameInstance() {
        // Mike's "parent by reference" invariant — same proxy through
        // either entry point once the cache has the config.
        seedCache(client, "billing", Map.of("k", "v"));
        LiveConfigProxy declared = client.getOrCreate("billing");
        LiveConfigProxy resolved = client.get("billing");
        assertSame(declared, resolved);
    }

    @Test
    void getOrCreate_parentByString_accepted() {
        LiveConfigProxy proxy = client.getOrCreate("billing", "common", null, null);
        assertEquals("billing", proxy.configId());
    }

    @Test
    void getOrCreate_parentByLiveConfigProxy_accepted() {
        LiveConfigProxy common = client.getOrCreate("common");
        LiveConfigProxy billing = client.getOrCreate("billing", common, "desc");
        assertEquals("billing", billing.configId());
    }

    @Test
    void getOrCreate_parentInvalidType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> client.getOrCreate("billing", 42, null, null));
    }

    @Test
    void getOrCreate_flushesBufferBeforeInitialFetch() throws Exception {
        // Per ADR-037 §2.14: pre-fetch flush must POST before the list call.
        java.util.List<String> calls = new ArrayList<>();
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class))).thenAnswer(inv -> {
            calls.add("bulk"); return new ConfigBulkResponse();
        });
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenAnswer(inv -> { calls.add("list"); return new ConfigListResponse().data(List.of()); });

        client.getOrCreate("billing");
        assertTrue(calls.size() >= 2);
        assertEquals("bulk", calls.get(0));
        assertEquals("list", calls.get(1));
    }

    @Test
    void getOrCreate_unknownConfig_doesNotThrow() {
        // Per ADR-037: getOrCreate is the declarative entry — never throws
        // NotFoundError. The proxy constructs fine; subsequent reads return
        // defaults via typed getters.
        assertDoesNotThrow(() -> client.getOrCreate("brand-new"));
    }

    // ==================================================================
    // 4. LiveConfigProxy typed getters
    // ==================================================================

    private LiveConfigProxy seededProxy() {
        seedCache(client, "billing", Map.of(
                "max_seats", 25L,
                "tier", "pro",
                "enabled", true,
                "ratio", 1.5,
                "payload", Map.of("k", "v")
        ));
        return client.getOrCreate("billing");
    }

    @Test
    void getBool_readsBooleanValue() {
        assertTrue(seededProxy().getBool("enabled", false));
    }

    @Test
    void getBool_missingKey_returnsDefault() {
        assertTrue(seededProxy().getBool("missing", true));
    }

    @Test
    void getBool_typeMismatch_returnsDefault() {
        assertFalse(seededProxy().getBool("tier", false));
    }

    @Test
    void getInt_fromNumberValue() {
        assertEquals(25, seededProxy().getInt("max_seats", 0));
    }

    @Test
    void getInt_missingKey_returnsDefault() {
        assertEquals(7, seededProxy().getInt("missing", 7));
    }

    @Test
    void getInt_boolValue_returnsDefault() {
        assertEquals(99, seededProxy().getInt("enabled", 99));
    }

    @Test
    void getInt_stringValue_returnsDefault() {
        assertEquals(99, seededProxy().getInt("tier", 99));
    }

    @Test
    void getInt_fractionalDouble_returnsDefault() {
        // 1.5 → not a whole number → falls back.
        assertEquals(0, seededProxy().getInt("ratio", 0));
    }

    @Test
    void getInt_integralDouble_truncates() {
        seedCache(client, "billing", Map.of("seats", 8.0));
        assertEquals(8, client.getOrCreate("billing").getInt("seats", 0));
    }

    @Test
    void getInt_intValue() {
        seedCache(client, "billing", Map.of("seats", Integer.valueOf(3)));
        assertEquals(3, client.getOrCreate("billing").getInt("seats", 0));
    }

    @Test
    void getFloat_fromNumberValue() {
        assertEquals(1.5, seededProxy().getFloat("ratio", 0.0));
    }

    @Test
    void getFloat_fromIntegralNumber() {
        assertEquals(25.0, seededProxy().getFloat("max_seats", 0.0));
    }

    @Test
    void getFloat_missingKey_returnsDefault() {
        assertEquals(2.5, seededProxy().getFloat("missing", 2.5));
    }

    @Test
    void getFloat_boolValue_returnsDefault() {
        assertEquals(9.9, seededProxy().getFloat("enabled", 9.9));
    }

    @Test
    void getFloat_stringValue_returnsDefault() {
        assertEquals(3.14, seededProxy().getFloat("tier", 3.14));
    }

    @Test
    void getString_readsStringValue() {
        assertEquals("pro", seededProxy().getString("tier", "free"));
    }

    @Test
    void getString_missingKey_returnsDefault() {
        assertEquals("fallback", seededProxy().getString("missing", "fallback"));
    }

    @Test
    void getString_typeMismatch_returnsDefault() {
        assertEquals("default", seededProxy().getString("max_seats", "default"));
    }

    @Test
    void getJson_readsAnyShape() {
        assertNotNull(seededProxy().getJson("payload", null));
    }

    @Test
    void getJson_missingKey_returnsDefault() {
        assertEquals("default", seededProxy().getJson("missing", "default"));
    }

    @Test
    void typedGetters_registerDeclarationOnFirstCall() throws Exception {
        seedCache(client, "billing", Map.of(
                "max_seats", 25L,
                "tier", "pro",
                "enabled", true,
                "ratio", 1.5,
                "payload", Map.of("k", "v")
        ));
        LiveConfigProxy proxy = client.getOrCreate("billing");
        proxy.getInt("max_seats", 5, "Maximum seats.");
        proxy.getString("tier", "free", "Plan tier.");
        proxy.getBool("enabled", false);
        proxy.getFloat("ratio", 0.0);
        proxy.getJson("payload", null);

        client.management().flush();

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        Map<String, ConfigItemDefinition> sent = captor.getValue().getConfigs().get(0).getItems();
        assertTrue(sent.containsKey("max_seats"));
        assertTrue(sent.containsKey("tier"));
        assertTrue(sent.containsKey("enabled"));
        assertTrue(sent.containsKey("ratio"));
        assertTrue(sent.containsKey("payload"));
        assertEquals("Maximum seats.", sent.get("max_seats").getDescription());
    }

    // ==================================================================
    // 5. AsyncConfigManagement passthroughs
    // ==================================================================

    @Test
    void asyncMgmt_passthroughs() throws Exception {
        AsyncConfigManagement async = new AsyncConfigManagement(
                client.management(), java.util.concurrent.Executors.newSingleThreadExecutor());
        async.registerConfig("billing", "svc", "prod", null, "Billing", null);
        async.registerConfigItem("billing", "max_seats", "NUMBER", 5, "seats");
        assertEquals(1, async.pendingCount());
        async.flush().get();
        assertEquals(0, async.pendingCount());
        verify(mockApi).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }
}
