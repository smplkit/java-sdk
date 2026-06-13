package com.smplkit.errors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ApiErrorDetail#toJson()} (including the {@code code} and
 * {@code meta} fields) and the {@link ApiErrorDetail#toMap()} projection,
 * mirroring the canonical Python {@code ApiErrorDetail.to_dict()} / {@code to_json()}.
 */
class ApiErrorDetailSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toJson_roundTrip_includesCodeAndMeta() throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("environment", "staging");
        meta.put("managed_count", 2);
        ApiErrorDetail err = new ApiErrorDetail("400", "environment_unmanaged",
                "Bad Request", "Environment is unmanaged",
                new ApiErrorDetail.Source("/data/attributes/env"), meta);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = MAPPER.readValue(err.toJson(), Map.class);

        assertEquals("400", parsed.get("status"));
        assertEquals("environment_unmanaged", parsed.get("code"));
        assertEquals("Bad Request", parsed.get("title"));
        assertEquals("Environment is unmanaged", parsed.get("detail"));
        assertEquals(Map.of("pointer", "/data/attributes/env"), parsed.get("source"));
        assertEquals("staging", ((Map<?, ?>) parsed.get("meta")).get("environment"));
        assertEquals(2, ((Map<?, ?>) parsed.get("meta")).get("managed_count"));
    }

    @Test
    void toJson_codeOnly_whenStatusAbsent() {
        ApiErrorDetail err = new ApiErrorDetail(null, "some_code", null, null, null, null);
        assertEquals("{\"code\": \"some_code\"}", err.toJson());
    }

    @Test
    void toJson_metaOnly_whenOtherFieldsAbsent() throws Exception {
        ApiErrorDetail err = new ApiErrorDetail(null, null, null, null, null, Map.of("k", "v"));
        String json = err.toJson();
        assertTrue(json.startsWith("{\"meta\": "));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
        assertEquals("v", ((Map<?, ?>) parsed.get("meta")).get("k"));
    }

    @Test
    void toJson_emptyMeta_omitted() {
        ApiErrorDetail err = new ApiErrorDetail("400", null, null, null, null, Map.of());
        assertEquals("{\"status\": \"400\"}", err.toJson());
    }

    @Test
    void toJson_nullMeta_omitted() {
        ApiErrorDetail err = new ApiErrorDetail("400", null, null, null, null, null);
        assertEquals("{\"status\": \"400\"}", err.toJson());
    }

    @Test
    void toMap_fullProjection() {
        Map<String, Object> meta = Map.of("environment", "staging");
        ApiErrorDetail err = new ApiErrorDetail("400", "code_x", "Title", "Detail",
                new ApiErrorDetail.Source("/p"), meta);

        Map<String, Object> map = err.toMap();

        assertEquals("400", map.get("status"));
        assertEquals("code_x", map.get("code"));
        assertEquals("Title", map.get("title"));
        assertEquals("Detail", map.get("detail"));
        assertEquals(Map.of("pointer", "/p"), map.get("source"));
        assertEquals(meta, map.get("meta"));
    }

    @Test
    void toMap_omitsUnsetFieldsEmptySourceAndEmptyMeta() {
        ApiErrorDetail err = new ApiErrorDetail(null, null, null, "only detail",
                new ApiErrorDetail.Source(null), Map.of());

        Map<String, Object> map = err.toMap();

        assertEquals(Map.of("detail", "only detail"), map);
        assertFalse(map.containsKey("status"));
        assertFalse(map.containsKey("code"));
        assertFalse(map.containsKey("source"));
        assertFalse(map.containsKey("meta"));
    }

    @Test
    void toMap_omitsSource_whenSourceNull() {
        ApiErrorDetail err = new ApiErrorDetail("400", null, null, null, null, null);
        assertEquals(Map.of("status", "400"), err.toMap());
    }
}
