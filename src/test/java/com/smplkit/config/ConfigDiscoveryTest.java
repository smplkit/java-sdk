package com.smplkit.config;

import com.smplkit.internal.ConfigRegistrationBuffer;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigBulkItem;
import com.smplkit.internal.generated.config.model.ConfigBulkRequest;
import com.smplkit.internal.generated.config.model.ConfigBulkResponse;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the declarative discovery + binding surface on the fused
 * {@link ConfigClient}:
 * <ol>
 *   <li>{@link ConfigRegistrationBuffer} — declare / addItem / drain semantics.</li>
 *   <li>{@code registerConfig} / {@code registerConfigItem} / {@code flush} on the client.</li>
 *   <li>{@code bind} — POJO / Map binding, idempotency, parent chaining, in-place mutation.</li>
 *   <li>{@code getValue(id, key[, default])} — auto-register with default, cache reads.</li>
 *   <li>{@code applyChangeToTarget} / reflection helpers — coercion + bail paths.</li>
 * </ol>
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
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenReturn(new ConfigListResponse().data(List.of()));
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class)))
                .thenReturn(new ConfigBulkResponse());
    }

    private static void seedCache(ConfigClient client, String id, Map<String, Object> values) {
        try {
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
    // 2. ConfigClient.register* / flush
    // ==================================================================

    @Test
    void flush_noPending_doesNothing() throws Exception {
        client.flush();
        verify(mockApi, never()).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    @Test
    void registerConfig_queuesDeclaration_flushPosts() throws Exception {
        client.registerConfig("billing", "svc", "prod", "common", "Billing", "Plan limits");
        assertEquals(1, client.pendingCount());

        client.flush();
        assertEquals(0, client.pendingCount());

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi, times(1)).bulkRegisterConfigs(captor.capture());
        List<ConfigBulkItem> sent = captor.getValue().getConfigs();
        assertEquals(1, sent.size());
        assertEquals("billing", sent.get(0).getId());
        assertEquals("common", sent.get(0).getParent());
        assertEquals("Billing", sent.get(0).getName());
        assertEquals("Plan limits", sent.get(0).getDescription());
        assertEquals("svc", sent.get(0).getService());
        assertEquals("prod", sent.get(0).getEnvironment());
    }

    @Test
    void registerConfigItem_flushIncludesAllTypes() throws Exception {
        client.registerConfig("billing", "svc", "prod", null, null, null);
        client.registerConfigItem("billing", "max_seats", "NUMBER", 5, "seats");
        client.registerConfigItem("billing", "tier", "STRING", "free", null);
        client.registerConfigItem("billing", "enabled", "BOOLEAN", false, null);
        client.registerConfigItem("billing", "payload", "JSON", null, null);
        client.registerConfigItem("billing", "weird", "WEIRD", "?", null);

        client.flush();

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        Map<String, ConfigItemDefinition> items = captor.getValue().getConfigs().get(0).getItems();
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, items.get("tier").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER, items.get("max_seats").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN, items.get("enabled").getType());
        assertEquals(ConfigItemDefinition.TypeEnum.JSON, items.get("payload").getType());
        assertEquals("seats", items.get("max_seats").getDescription());
        assertNull(items.get("weird").getType());
    }

    @Test
    void flush_serverError_swallowed() throws Exception {
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class)))
                .thenThrow(new RuntimeException("boom"));
        client.registerConfig("billing", "svc", "prod", null, null, null);
        assertDoesNotThrow(() -> client.flush());
        assertEquals(0, client.pendingCount());
    }

    @Test
    void flushAsync_drainsBuffer() throws Exception {
        client.registerConfig("billing", "svc", "prod", null, "Billing", null);
        client.flushAsync().get();
        assertEquals(0, client.pendingCount());
        verify(mockApi).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    @Test
    void registerConfig_thresholdTriggersBackgroundFlush() throws Exception {
        for (int i = 0; i < 51; i++) {
            client.registerConfig("cfg-" + i, "svc", "prod", null, null, null);
        }
        Thread t = client.lastFlushThread;
        assertNotNull(t, "expected a background flush thread to be spawned");
        t.join(5000);
        verify(mockApi, times(1)).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    @Test
    void registerConfigItem_thresholdTriggersBackgroundFlush() throws Exception {
        // Declare 49 configs and drain synchronously so the registerConfig path
        // never crosses the flush threshold during setup (no competing flush
        // thread, deterministic coverage of the registerConfigItem flush branch).
        for (int i = 0; i < 49; i++) {
            client.registerConfig("cfg-" + i, "svc", "prod", null, null, null);
        }
        client.flush();
        client.registerConfig("cfg-49", "svc", "prod", null, null, null);
        assertEquals(1, client.pendingCount());

        // Re-attribute an item to each drained config. addItem re-creates the
        // drained entry, growing pendingCount toward the threshold; the 50th
        // pending config is the SOLE trigger for the background flush.
        for (int i = 0; i < 49; i++) {
            client.registerConfigItem("cfg-" + i, "k", "STRING", "v", null);
        }
        Thread t = client.lastFlushThread;
        assertNotNull(t, "expected registerConfigItem to spawn a background flush");
        t.join(5000);
        verify(mockApi, Mockito.atLeastOnce()).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    @Test
    void triggerBackgroundFlush_coalescesWhilePriorThreadAlive() throws Exception {
        // Block the first flush so the in-flight thread is still alive when the
        // second threshold crossing happens — exercising the coalesce branch.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class))).thenAnswer(inv -> {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new ConfigBulkResponse();
        });

        for (int i = 0; i < 51; i++) {
            client.registerConfig("cfg-" + i, "svc", "prod", null, null, null);
        }
        Thread first = client.lastFlushThread;
        assertNotNull(first);

        // Second crossing while first is blocked → coalesced, same thread.
        for (int i = 51; i < 102; i++) {
            client.registerConfig("cfg-" + i, "svc", "prod", null, null, null);
        }
        assertSame(first, client.lastFlushThread, "second crossing should coalesce onto the in-flight flush");

        release.countDown();
        first.join(5000);
    }

    // ==================================================================
    // 3. ConfigClient.bind — POJO and Map paths
    // ==================================================================

    public static final class Plan {
        public int max_seats = 5;
        public String tier = "free";
        public boolean enabled = false;
    }

    public static final class Billing {
        public Plan plan = new Plan();
        public String app_name = "Acme";
    }

    @Test
    void bind_pojo_registersClassAndItems() throws Exception {
        Billing bound = client.bind("billing", new Billing());
        assertNotNull(bound);

        client.flush();

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);

        assertEquals("billing", sent.getId());
        assertEquals("Billing", sent.getName());
        assertNull(sent.getParent());

        Map<String, ConfigItemDefinition> items = sent.getItems();
        assertTrue(items.containsKey("plan.max_seats"));
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER, items.get("plan.max_seats").getType());
        assertEquals(5, items.get("plan.max_seats").getValue());
        assertTrue(items.containsKey("plan.tier"));
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, items.get("plan.tier").getType());
        assertTrue(items.containsKey("plan.enabled"));
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN, items.get("plan.enabled").getType());
        assertTrue(items.containsKey("app_name"));
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, items.get("app_name").getType());
    }

    public enum Color { RED, GREEN }

    public static final class Nested {
        public int leaf = 1;
    }

    /** A POJO with a field of every shape isNestedNamespace must classify. */
    public static final class AllShapes {
        public Map<String, Object> mapField = new LinkedHashMap<>(Map.of("a", 1));
        public String strField = "s";
        public int numField = 1;
        public boolean boolField = true;
        public char charField = 'c';
        public Color enumField = Color.RED;
        public List<String> listField = List.of("x");
        public java.time.LocalDate temporalField = java.time.LocalDate.of(2024, 1, 1);
        public java.util.Date dateField = new java.util.Date(0L);
        public int[] arrayField = {1, 2};
        public java.util.UUID uuidField = new java.util.UUID(0L, 0L);
        public Nested nestedField = new Nested();
        public Object nullField = null;
    }

    @Test
    void bind_classifiesEveryFieldShape() throws Exception {
        // Binding a POJO with one field of every shape drives the leaf/namespace
        // classifier through all its branches: maps and nested POJOs are
        // descended; everything else (string, number, boolean, char, enum,
        // iterable, temporal, date, array, JDK type, null) is a leaf.
        client.bind("shapes", new AllShapes());
        client.flush();

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        Map<String, ConfigItemDefinition> items = captor.getValue().getConfigs().get(0).getItems();

        // Map + nested POJO descended into dotted leaves.
        assertTrue(items.containsKey("mapField.a"));
        assertTrue(items.containsKey("nestedField.leaf"));
        // Everything else registered as its own leaf (not descended).
        assertTrue(items.containsKey("strField"));
        assertTrue(items.containsKey("numField"));
        assertTrue(items.containsKey("boolField"));
        assertTrue(items.containsKey("charField"));
        assertTrue(items.containsKey("enumField"));
        assertTrue(items.containsKey("listField"));
        assertTrue(items.containsKey("temporalField"));
        assertTrue(items.containsKey("dateField"));
        assertTrue(items.containsKey("arrayField"));
        assertTrue(items.containsKey("uuidField"));
        assertTrue(items.containsKey("nullField"));
        // None of the scalar/JDK leaves were dot-flattened.
        assertFalse(items.containsKey("strField.value"));
    }

    @Test
    void bind_returnsSameInstance() {
        Billing src = new Billing();
        Billing bound = client.bind("billing", src);
        assertSame(src, bound);
    }

    @Test
    void bind_idempotent_returnsOriginalIgnoresNew() {
        Billing first = new Billing();
        Billing returnedFirst = client.bind("billing", first);
        Billing second = new Billing();
        Billing returnedSecond = client.bind("billing", second);
        assertSame(first, returnedFirst);
        assertSame(first, returnedSecond);
        assertNotSame(second, returnedSecond);
    }

    @Test
    void bind_nullTarget_throws() {
        assertThrows(IllegalArgumentException.class, () -> client.bind("billing", null));
    }

    @Test
    void bind_parentUnknown_throws() {
        Billing stranger = new Billing();
        assertThrows(IllegalArgumentException.class,
                () -> client.bind("billing", new Billing(), stranger));
    }

    @Test
    void bind_parentPreviouslyBound_registersParentReference() throws Exception {
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("app_name", "Acme");
        client.bind("showcase-common", common);
        client.bind("showcase-billing", new Billing(), common);
        client.flush();

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi, Mockito.atLeastOnce()).bulkRegisterConfigs(captor.capture());
        Map<String, String> idToParent = new HashMap<>();
        for (ConfigBulkRequest req : captor.getAllValues()) {
            for (ConfigBulkItem item : req.getConfigs()) {
                idToParent.put(item.getId(), item.getParent());
            }
        }
        assertNull(idToParent.get("showcase-common"));
        assertEquals("showcase-common", idToParent.get("showcase-billing"));
    }

    @Test
    void bind_map_flattensNestedKeys() throws Exception {
        Map<String, Object> db = new LinkedHashMap<>();
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("host", "db.example.com");
        primary.put("port", 5432);
        db.put("primary", primary);
        db.put("pool_size", 10);
        db.put("enabled", true);

        client.bind("db", db);
        client.flush();

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);
        Map<String, ConfigItemDefinition> items = sent.getItems();

        assertTrue(items.containsKey("primary.host"));
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, items.get("primary.host").getType());
        assertTrue(items.containsKey("primary.port"));
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER, items.get("primary.port").getType());
        assertTrue(items.containsKey("pool_size"));
        assertTrue(items.containsKey("enabled"));
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN, items.get("enabled").getType());
        assertNull(sent.getName());
    }

    @Test
    void bind_triggersLazyConnect_listFetched() throws Exception {
        // The first bind() connects (flush-empty then list); the buffered
        // declaration is sent on the next explicit flush.
        client.bind("billing", new Billing());
        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
        assertTrue(client.isConnected());

        client.flush();
        verify(mockApi).bulkRegisterConfigs(any(ConfigBulkRequest.class));
    }

    @Test
    void bind_inPlaceMutation_pojo_viaApplyChange() {
        Billing bound = client.bind("billing", new Billing());
        assertEquals(5, bound.plan.max_seats);

        ConfigClient.applyChangeToTarget(bound, "plan.max_seats", 25);
        assertEquals(25, bound.plan.max_seats);
    }

    @Test
    void bind_inPlaceMutation_map_viaApplyChange() {
        Map<String, Object> db = new LinkedHashMap<>();
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("host", "db-local");
        primary.put("port", 5432);
        db.put("primary", primary);

        client.bind("db", db);

        ConfigClient.applyChangeToTarget(db, "primary.host", "db-prod");

        @SuppressWarnings("unchecked")
        Map<String, Object> primaryAfter = (Map<String, Object>) db.get("primary");
        assertEquals("db-prod", primaryAfter.get("host"));
        assertEquals(5432, primaryAfter.get("port"));
    }

    @Test
    void bind_pendingSeed_survivesRefreshWhenServerAbsent() throws ApiException {
        // Bind a config the server doesn't know about yet, then refresh against
        // an empty list. mergePendingSeeds must re-seed the bound config so its
        // resolved values survive the cache rebuild.
        Billing bound = client.bind("billing", new Billing());
        assertEquals(5, client.getValue("billing", "plan.max_seats"));

        // refresh() lists empty again — "billing" is absent server-side.
        client.refresh();

        // The bound config's seed survived the rebuild.
        assertEquals(5, client.getValue("billing", "plan.max_seats"));
        assertEquals(5, bound.plan.max_seats);
    }

    @Test
    void apply_missingMapIntermediateKey_bailsCleanly() {
        // The dotted path steps through a map whose intermediate key is absent —
        // the walk bails without mutating anything.
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("present", "x");
        client.bind("rootmap", root);

        assertDoesNotThrow(() -> ConfigClient.applyChangeToTarget(root, "absent.leaf", 99));
        assertFalse(root.containsKey("absent"));
        assertEquals("x", root.get("present"));
    }

    @Test
    void bind_syncsFromCacheOnBind() {
        // Pre-seed the cache so bind() sees existing server overrides.
        seedCache(client, "billing", Map.of("plan.max_seats", 25, "plan.tier", "pro"));

        Billing bound = client.bind("billing", new Billing());
        assertEquals(25, bound.plan.max_seats);
        assertEquals("pro", bound.plan.tier);
        assertFalse(bound.plan.enabled);
    }

    @Test
    void bind_syncFromEmptyCache_isNoOp() {
        // The config id is present in the resolved cache but with no values —
        // sync should early-return, leaving the bound object at its defaults.
        seedCache(client, "billing", new HashMap<>());

        Billing bound = client.bind("billing", new Billing());
        assertEquals(5, bound.plan.max_seats);
        assertEquals("free", bound.plan.tier);
    }

    @Test
    void bind_appliesChangeViaWsEvent_targetSeesNewValue() throws ApiException {
        Billing bound = client.bind("billing", new Billing());

        AtomicInteger seenSeats = new AtomicInteger();
        client.onChange("billing", "plan.max_seats", evt -> seenSeats.set(bound.plan.max_seats));

        // Drive a real config_changed: getConfig returns the updated value.
        com.smplkit.internal.generated.config.model.Config attrs =
                new com.smplkit.internal.generated.config.model.Config(null, null);
        attrs.setName("Billing");
        ConfigItemDefinition def = new ConfigItemDefinition();
        def.setValue(25);
        def.setType(ConfigItemDefinition.TypeEnum.NUMBER);
        attrs.setItems(Map.of("plan.max_seats", def));
        com.smplkit.internal.generated.config.model.ConfigResource res =
                new com.smplkit.internal.generated.config.model.ConfigResource();
        res.setId("billing");
        res.setType(com.smplkit.internal.generated.config.model.ConfigResource.TypeEnum.CONFIG);
        res.setAttributes(attrs);
        com.smplkit.internal.generated.config.model.ConfigResponse resp =
                new com.smplkit.internal.generated.config.model.ConfigResponse();
        resp.setData(res);
        when(mockApi.getConfig("billing")).thenReturn(resp);

        client.simulateConfigChanged(Map.of("id", "billing"));

        assertEquals(25, bound.plan.max_seats);
        assertEquals(25, seenSeats.get(), "Listener should see the value already applied to the target");
    }

    @Test
    void applyChange_typeCoercion_doubleIntoIntField() {
        Billing bound = client.bind("billing", new Billing());
        ConfigClient.applyChangeToTarget(bound, "plan.max_seats", 42.0);
        assertEquals(42, bound.plan.max_seats);
    }

    @Test
    void applyChange_typeCoercion_stringFieldFromNonString() {
        Billing bound = client.bind("billing", new Billing());
        ConfigClient.applyChangeToTarget(bound, "plan.tier", 42);
        assertEquals("42", bound.plan.tier);
    }

    @Test
    void applyChange_unknownDottedKey_silentlyBails() {
        Billing bound = client.bind("billing", new Billing());
        assertDoesNotThrow(() -> {
            ConfigClient.applyChangeToTarget(bound, "plan.does_not_exist", "ignored");
            ConfigClient.applyChangeToTarget(bound, "non_existent.path", "also ignored");
        });
        assertEquals(5, bound.plan.max_seats);
    }

    @Test
    void applyChange_unknownNamespacePrefix_bailsCleanly() {
        Billing bound = client.bind("billing", new Billing());
        // First segment ("missing") has no matching field.
        assertDoesNotThrow(() -> ConfigClient.applyChangeToTarget(bound, "missing.leaf", 99));
        assertEquals(5, bound.plan.max_seats);
    }

    // ==================================================================
    // 4. getValue(id, key) and getValue(id, key, default)
    // ==================================================================

    @Test
    void getValue_byIdAndKey_returnsValue() {
        seedCache(client, "billing", Map.of("plan.max_seats", 25));
        assertEquals(25, client.getValue("billing", "plan.max_seats"));
    }

    @Test
    void getValue_byIdAndKey_unknownConfig_throws() {
        markConnected(client);
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> client.getValue("missing", "key"));
    }

    @Test
    void getValue_byIdAndKey_unknownKey_throws() {
        seedCache(client, "billing", Map.of("plan.max_seats", 25));
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> client.getValue("billing", "plan.unknown"));
    }

    @Test
    void getValueOr_unknownConfig_returnsDefault_andRegisters() throws Exception {
        Object result = client.getValue("brand-new", "slow_query_ms", 500);
        assertEquals(500, result);

        client.flush();
        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);
        assertEquals("brand-new", sent.getId());
        Map<String, ConfigItemDefinition> items = sent.getItems();
        assertTrue(items.containsKey("slow_query_ms"));
        assertEquals(500, items.get("slow_query_ms").getValue());
        assertEquals(ConfigItemDefinition.TypeEnum.NUMBER, items.get("slow_query_ms").getType());
    }

    @Test
    void getValueOr_unknownKey_returnsDefault() {
        seedCache(client, "billing", Map.of("plan.max_seats", 25));
        Object v = client.getValue("billing", "plan.unknown", "fallback");
        assertEquals("fallback", v);
    }

    @Test
    void getValueOr_existingValue_returnsCachedValue_overDefault() {
        seedCache(client, "billing", Map.of("plan.max_seats", 25));
        Object v = client.getValue("billing", "plan.max_seats", 999);
        assertEquals(25, v);
    }

    @Test
    void getValueOr_inferType_boolean_overNumber() throws Exception {
        client.getValue("brand-new", "enabled", true);
        client.flush();
        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN, sent.getItems().get("enabled").getType());
    }

    @Test
    void getValueOr_inferType_stringFromCharSequence() throws Exception {
        StringBuilder sb = new StringBuilder("hello");
        client.getValue("brand-new", "name", sb);
        client.flush();
        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, sent.getItems().get("name").getType());
    }

    @Test
    void getValueOr_inferType_unknownLeafFallsBackToString() throws Exception {
        client.getValue("brand-new", "blob", Map.of("k", "v"));
        client.flush();
        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);
        // The buffer records the "STRING" label for the unknown leaf.
        assertEquals(ConfigItemDefinition.TypeEnum.STRING, sent.getItems().get("blob").getType());
    }

    // ==================================================================
    // 5. Reflection helpers + edge cases
    // ==================================================================

    public static final class Outer {
        public Inner inner;
    }

    public static final class Inner {
        public int leaf = 0;
    }

    @Test
    void apply_intermediateNullField_bailsCleanly() {
        Outer o = new Outer();
        client.bind("outer", o);
        assertDoesNotThrow(() -> ConfigClient.applyChangeToTarget(o, "inner.leaf", 99));
        assertNull(o.inner);
    }

    @Test
    void apply_booleanCoercion_assignsTrueValueToField() {
        Billing bound = client.bind("billing", new Billing());
        ConfigClient.applyChangeToTarget(bound, "plan.enabled", Boolean.TRUE);
        assertTrue(bound.plan.enabled);
    }

    @Test
    void apply_typeMismatch_intIntoBooleanField_silentlySkips() {
        Billing bound = client.bind("billing", new Billing());
        ConfigClient.applyChangeToTarget(bound, "plan.enabled", 42);
        assertFalse(bound.plan.enabled);
    }

    public static final class Numeric {
        public long l = 0L;
        public double d = 0.0;
        public float f = 0.0f;
        public short s = 0;
        public byte b = 0;
        public int i = 0;
        public boolean flag = false;
        public String str = "";
    }

    @Test
    void apply_coercesNumberIntoEveryNumericPrimitive() {
        Numeric n = client.bind("numeric", new Numeric());
        // A single Number (Integer) coerces into each numeric primitive field.
        ConfigClient.applyChangeToTarget(n, "l", 7);
        ConfigClient.applyChangeToTarget(n, "d", 7);
        ConfigClient.applyChangeToTarget(n, "f", 7);
        ConfigClient.applyChangeToTarget(n, "s", 7);
        ConfigClient.applyChangeToTarget(n, "b", 7);
        ConfigClient.applyChangeToTarget(n, "i", 7);
        assertEquals(7L, n.l);
        assertEquals(7.0, n.d);
        assertEquals(7.0f, n.f);
        assertEquals((short) 7, n.s);
        assertEquals((byte) 7, n.b);
        assertEquals(7, n.i);
    }

    @Test
    void apply_sameTypeValue_assignedDirectly() {
        Numeric n = client.bind("numeric", new Numeric());
        // String into a String field: coerce sees fieldType.isInstance(value) true
        // and returns the value as-is.
        ConfigClient.applyChangeToTarget(n, "str", "hello");
        assertEquals("hello", n.str);
        // Boolean into a boolean field exercises the boolean coercion branch.
        ConfigClient.applyChangeToTarget(n, "flag", Boolean.TRUE);
        assertTrue(n.flag);
    }

    @Test
    void apply_unknownObject_intoObjectField_passesThrough() {
        class Holder { public Object payload = null; }
        Holder h = new Holder();
        client.bind("holder", h);
        Object payload = new java.util.HashMap<>();
        ConfigClient.applyChangeToTarget(h, "payload", payload);
        assertSame(payload, h.payload);
    }

    @Test
    void apply_nullValue_assignsNullToObjectField() {
        class Holder { public Object payload = "non-null"; }
        Holder h = new Holder();
        client.bind("holder", h);
        ConfigClient.applyChangeToTarget(h, "payload", null);
        assertNull(h.payload);
    }

    @Test
    void readField_wrapsReflectiveFailure() throws Exception {
        java.lang.reflect.Field f = Plan.class.getDeclaredField("max_seats");
        // Null owner — Field.get on an instance field throws NPE, wrapped as IllegalStateException.
        assertThrows(IllegalStateException.class, () -> ConfigClient.readField(f, null));
    }

    @Test
    void writeField_silentlySkipsOnFailure() throws Exception {
        // Null into a primitive boolean field — Field.set throws; writeField catches and skips.
        java.lang.reflect.Field f = Plan.class.getDeclaredField("enabled");
        Plan p = new Plan();
        assertDoesNotThrow(() -> ConfigClient.writeField(f, p, null));
        assertFalse(p.enabled);
    }

    @Test
    void configIdForBinding_iteratesAllEntries_whenNoMatch() {
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        client.bind("config-a", a);
        client.bind("config-b", b);
        Map<String, Object> outsider = new LinkedHashMap<>();
        assertThrows(IllegalArgumentException.class,
                () -> client.bind("config-c", new LinkedHashMap<>(), outsider));
    }

    @Test
    void bind_idempotent_secondCallReturnsOriginal() {
        Billing first = new Billing();
        Billing second = new Billing();
        assertSame(first, client.bind("billing", first));
        assertSame(first, client.bind("billing", second));
    }

    private static void markConnected(ConfigClient client) {
        try {
            Field f = ConfigClient.class.getDeclaredField("connected");
            f.setAccessible(true);
            f.setBoolean(client, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
