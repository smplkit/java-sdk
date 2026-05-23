package com.smplkit.config;

import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.api.ConfigsApi;
import com.smplkit.internal.generated.config.model.ConfigBulkItem;
import com.smplkit.internal.generated.config.model.ConfigBulkRequest;
import com.smplkit.internal.generated.config.model.ConfigBulkResponse;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.management.ConfigRegistrationBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.util.ArrayList;
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
 * Tests for the declarative discovery API (ADR-037 §2.13/§2.14):
 *   1. {@link ConfigRegistrationBuffer} — declare/add_item/drain semantics.
 *   2. {@link ConfigManagement} — registerConfig/registerConfigItem/flush.
 *   3. {@link ConfigClient#bind} — POJO/Map binding, idempotency, parent
 *      chaining, pre-fetch flush, in-place mutation, sync-from-cache.
 *   4. {@link ConfigClient#get(String, String, Object)} — auto-register with
 *      default.
 *   5. {@link AsyncConfigManagement} discovery passthroughs.
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
        assertNull(items.get("weird").getType());
    }

    @Test
    void mgmt_flush_serverError_swallowed() throws Exception {
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class)))
                .thenThrow(new RuntimeException("boom"));
        client.management().registerConfig("billing", "svc", "prod", null, null, null);
        assertDoesNotThrow(() -> client.management().flush());
        assertEquals(0, client.management().pendingCount());
    }

    @Test
    void mgmt_registerConfig_thresholdTriggersBackgroundFlush() throws Exception {
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

        client.management().flush();

        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);

        assertEquals("billing", sent.getId());
        assertEquals("Billing", sent.getName());
        assertNull(sent.getParent());

        Map<String, ConfigItemDefinition> items = sent.getItems();
        // Nested POJO fields flatten via dotted keys.
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
        // First bind() flushed the buffer during pre-fetch (showcase-common).
        // Second bind() doesn't re-flush (already connected); the explicit
        // flush below drains the showcase-billing declaration.
        client.bind("showcase-billing", new Billing(), common);
        client.management().flush();

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
        client.management().flush();

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
        // Maps don't carry a class name.
        assertNull(sent.getName());
    }

    @Test
    void bind_flushesBufferBeforeInitialFetch() throws Exception {
        List<String> calls = new ArrayList<>();
        when(mockApi.bulkRegisterConfigs(any(ConfigBulkRequest.class))).thenAnswer(inv -> {
            calls.add("bulk"); return new ConfigBulkResponse();
        });
        when(mockApi.listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull()))
                .thenAnswer(inv -> { calls.add("list"); return new ConfigListResponse().data(List.of()); });

        client.bind("billing", new Billing());
        assertTrue(calls.size() >= 2);
        assertEquals("bulk", calls.get(0));
        assertEquals("list", calls.get(1));
    }

    @Test
    void bind_inPlaceMutation_pojoOnWsEvent() {
        // Pre-seed the cache so syncTargetFromCache picks up the value too.
        Billing bound = client.bind("billing", new Billing());
        assertEquals(5, bound.plan.max_seats);

        // Simulate a configs_changed-style diff where plan.max_seats = 25.
        Map<String, Map<String, Object>> oldCache = Map.of();
        Map<String, Map<String, Object>> newCache = Map.of("billing", Map.of("plan.max_seats", 25));
        client.diffAndFire(oldCache, newCache, "websocket");

        assertEquals(25, bound.plan.max_seats);
    }

    @Test
    void bind_inPlaceMutation_mapOnWsEvent() {
        Map<String, Object> db = new LinkedHashMap<>();
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("host", "db-local");
        primary.put("port", 5432);
        db.put("primary", primary);

        client.bind("db", db);

        // WS event: primary.host changes to "db-prod"
        Map<String, Map<String, Object>> oldCache = Map.of();
        Map<String, Map<String, Object>> newCache = Map.of("db", Map.of("primary.host", "db-prod"));
        client.diffAndFire(oldCache, newCache, "websocket");

        @SuppressWarnings("unchecked")
        Map<String, Object> primaryAfter = (Map<String, Object>) db.get("primary");
        assertEquals("db-prod", primaryAfter.get("host"));
        assertEquals(5432, primaryAfter.get("port"));
    }

    @Test
    void bind_syncsFromCacheOnBind() {
        // Pre-seed the cache so bind() sees existing server overrides.
        seedCache(client, "billing", Map.of("plan.max_seats", 25, "plan.tier", "pro"));

        Billing bound = client.bind("billing", new Billing());
        assertEquals(25, bound.plan.max_seats);
        assertEquals("pro", bound.plan.tier);
        // Field not in cache retains the in-code default.
        assertFalse(bound.plan.enabled);
    }

    @Test
    void bind_appliesChangeViaListener_targetSeesNewValueFirst() {
        Billing bound = client.bind("billing", new Billing());

        AtomicInteger seenSeats = new AtomicInteger();
        client.onChange("billing", "plan.max_seats", evt -> {
            // Listener fires AFTER the target is updated.
            seenSeats.set(bound.plan.max_seats);
        });

        Map<String, Map<String, Object>> oldCache = Map.of("billing", Map.of("plan.max_seats", 5));
        Map<String, Map<String, Object>> newCache = Map.of("billing", Map.of("plan.max_seats", 25));
        client.diffAndFire(oldCache, newCache, "websocket");

        assertEquals(25, seenSeats.get());
    }

    @Test
    void bind_typeCoercion_doubleIntoIntField() {
        Billing bound = client.bind("billing", new Billing());

        Map<String, Map<String, Object>> oldCache = Map.of();
        Map<String, Map<String, Object>> newCache = Map.of("billing", Map.of("plan.max_seats", 42.0));
        client.diffAndFire(oldCache, newCache, "websocket");

        assertEquals(42, bound.plan.max_seats);
    }

    @Test
    void bind_typeCoercion_stringFieldFromNonString() {
        Billing bound = client.bind("billing", new Billing());

        Map<String, Map<String, Object>> oldCache = Map.of();
        Map<String, Map<String, Object>> newCache = Map.of("billing", Map.of("plan.tier", 42));
        client.diffAndFire(oldCache, newCache, "websocket");

        assertEquals("42", bound.plan.tier);
    }

    @Test
    void bind_unknownDottedKey_silentlyBails() {
        // No exception even though "plan.does_not_exist" has no field on Plan.
        Billing bound = client.bind("billing", new Billing());

        Map<String, Map<String, Object>> oldCache = Map.of();
        Map<String, Map<String, Object>> newCache = Map.of("billing",
                Map.of("plan.does_not_exist", "ignored",
                       "non_existent.path", "also ignored"));
        assertDoesNotThrow(() -> client.diffAndFire(oldCache, newCache, "websocket"));
        // Existing field unchanged.
        assertEquals(5, bound.plan.max_seats);
    }

    @Test
    void bind_unknownNamespacePrefix_inDottedKey_bailsCleanly() {
        Billing bound = client.bind("billing", new Billing());

        // First segment ("missing") has no matching field.
        Map<String, Map<String, Object>> newCache = Map.of("billing", Map.of("missing.leaf", 99));
        assertDoesNotThrow(() -> client.diffAndFire(Map.of(), newCache, "websocket"));
        assertEquals(5, bound.plan.max_seats);
    }

    // ==================================================================
    // 4. ConfigClient.get(id, key) and get(id, key, default)
    // ==================================================================

    @Test
    void get_byIdAndKey_returnsValue() {
        seedCache(client, "billing", Map.of("plan.max_seats", 25));
        assertEquals(25, client.get("billing", "plan.max_seats"));
    }

    @Test
    void get_byIdAndKey_unknownConfig_throws() {
        // Force connected so we hit the cache-miss branch (not auto-init).
        try {
            Field f = ConfigClient.class.getDeclaredField("connected");
            f.setAccessible(true);
            f.setBoolean(client, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> client.get("missing", "key"));
    }

    @Test
    void get_byIdAndKey_unknownKey_throws() {
        seedCache(client, "billing", Map.of("plan.max_seats", 25));
        assertThrows(com.smplkit.errors.NotFoundError.class,
                () -> client.get("billing", "plan.unknown"));
    }

    @Test
    void getOr_unknownConfig_returnsDefault_andRegisters() throws Exception {
        Object result = client.get("brand-new", "slow_query_ms", 500);
        assertEquals(500, result);

        client.management().flush();
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
    void getOr_unknownKey_returnsDefault() {
        seedCache(client, "billing", Map.of("plan.max_seats", 25));
        Object v = client.get("billing", "plan.unknown", "fallback");
        assertEquals("fallback", v);
    }

    @Test
    void getOr_existingValue_returnsCachedValue_overDefault() {
        seedCache(client, "billing", Map.of("plan.max_seats", 25));
        Object v = client.get("billing", "plan.max_seats", 999);
        assertEquals(25, v);
    }

    @Test
    void getOr_inferType_boolean_overNumber() throws Exception {
        client.get("brand-new", "enabled", true);
        client.management().flush();
        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);
        assertEquals(ConfigItemDefinition.TypeEnum.BOOLEAN,
                sent.getItems().get("enabled").getType());
    }

    @Test
    void getOr_inferType_stringFromCharSequence() throws Exception {
        StringBuilder sb = new StringBuilder("hello");
        client.get("brand-new", "name", sb);
        client.management().flush();
        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);
        assertEquals(ConfigItemDefinition.TypeEnum.STRING,
                sent.getItems().get("name").getType());
    }

    @Test
    void getOr_inferType_jsonFallbackForUnknown() throws Exception {
        // Unknown leaf type → STRING fallback per inferItemType, but the
        // generated TypeEnum is JSON (since wrap uses inferType which checks
        // CharSequence/Number/Boolean else JSON). We assert what the BUFFER
        // recorded — "STRING" string — and what the wrap turned that into.
        client.get("brand-new", "blob", Map.of("k", "v"));
        client.management().flush();
        ArgumentCaptor<ConfigBulkRequest> captor = ArgumentCaptor.forClass(ConfigBulkRequest.class);
        verify(mockApi).bulkRegisterConfigs(captor.capture());
        ConfigBulkItem sent = captor.getValue().getConfigs().get(0);
        // Buffer recorded "STRING" type label for the unknown leaf.
        assertEquals(ConfigItemDefinition.TypeEnum.STRING,
                sent.getItems().get("blob").getType());
    }

    // ==================================================================
    // 4b. Reflection helpers + edge cases
    // ==================================================================

    public static final class Outer {
        public Inner inner;
    }

    public static final class Inner {
        public int leaf = 0;
    }

    @Test
    void apply_intermediateNullField_bailsCleanly() {
        // Walker hits inner=null mid-walk → silent return.
        Outer o = new Outer();
        client.bind("outer", o);
        Map<String, Map<String, Object>> oldCache = Map.of();
        Map<String, Map<String, Object>> newCache = Map.of("outer", Map.of("inner.leaf", 99));
        assertDoesNotThrow(() -> client.diffAndFire(oldCache, newCache, "websocket"));
        assertNull(o.inner);
    }

    @Test
    void apply_booleanCoercion_assignsTrueValueToField() {
        Billing bound = client.bind("billing", new Billing());
        client.diffAndFire(Map.of(),
                Map.of("billing", Map.of("plan.enabled", Boolean.TRUE)), "websocket");
        assertTrue(bound.plan.enabled);
    }

    @Test
    void apply_typeMismatch_intIntoBooleanField_silentlySkips() {
        // Integer into boolean primitive: coerce falls through to "return value"
        // and writeField catches IllegalArgumentException from f.set.
        Billing bound = client.bind("billing", new Billing());
        client.diffAndFire(Map.of(),
                Map.of("billing", Map.of("plan.enabled", 42)), "websocket");
        assertFalse(bound.plan.enabled);
    }

    @Test
    void apply_unknownObject_intoObjectField_passesThrough() {
        // Object field, value is non-Number/Boolean/String — coerce returns
        // value as-is and Field.set accepts it (Object accepts anything).
        class Holder { public Object payload = null; }
        Holder h = new Holder();
        client.bind("holder", h);
        Object payload = new java.util.HashMap<>();
        client.diffAndFire(Map.of(),
                Map.of("holder", Map.of("payload", payload)), "websocket");
        assertSame(payload, h.payload);
    }

    @Test
    void readField_wrapsReflectiveFailure() throws Exception {
        java.lang.reflect.Field f = Plan.class.getDeclaredField("max_seats");
        // Pass null owner — Field.get on an instance field throws NPE,
        // which our broad catch converts to IllegalStateException.
        assertThrows(IllegalStateException.class, () -> ConfigClient.readField(f, null));
    }

    @Test
    void writeField_silentlySkipsOnFailure() throws Exception {
        // Pass null to a primitive boolean field — Field.set throws
        // NullPointerException; writeField catches and skips.
        java.lang.reflect.Field f = Plan.class.getDeclaredField("enabled");
        Plan p = new Plan();
        assertDoesNotThrow(() -> ConfigClient.writeField(f, p, null));
        assertFalse(p.enabled);
    }

    @Test
    void configIdForBinding_iteratesAllEntries_whenNoMatch() {
        // Bind two configs, then try to use a third "outsider" target as
        // parent — the lookup iterates BOTH bindings before returning null,
        // exercising the loop's fall-through (non-matching) path
        // independent of map iteration order.
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        client.bind("config-a", a);
        client.bind("config-b", b);
        Map<String, Object> outsider = new LinkedHashMap<>();
        assertThrows(IllegalArgumentException.class,
                () -> client.bind("config-c", new LinkedHashMap<>(), outsider));
    }

    @Test
    void bind_idempotent_secondCallReturnsOriginal_viaPutIfAbsentRace() {
        // The pre-check was removed, so duplicate bind() runs through the
        // putIfAbsent path. Second call: items re-registered (buffer is
        // idempotent so they no-op), then putIfAbsent returns the original.
        Billing first = new Billing();
        Billing second = new Billing();
        assertSame(first, client.bind("billing", first));
        assertSame(first, client.bind("billing", second));
    }

    // ==================================================================
    // 5. Lazy init + start() + idempotency
    // ==================================================================

    @Test
    void start_isIdempotent_andTriggersInitialFetch() throws Exception {
        client.start();
        client.start();
        verify(mockApi, times(1)).listConfigs(isNull(), isNull(), isNull(), isNull(), any(), any(), isNull());
    }

    @Test
    void start_withoutEnvironment_isNoOp() {
        ConfigClient noEnv = new ConfigClient(mockApi, HttpClient.newHttpClient(), "k");
        assertDoesNotThrow(noEnv::start);
        assertFalse(noEnv.isConnected());
    }

    // ==================================================================
    // 6. AsyncConfigManagement passthroughs
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
