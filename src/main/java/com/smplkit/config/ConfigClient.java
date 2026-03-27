package com.smplkit.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.smplkit.errors.SmplNotFoundException;
import com.smplkit.internal.Transport;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the Smpl Config service.
 *
 * <p>Provides CRUD operations on configuration resources. Obtained via
 * {@link com.smplkit.SmplkitClient#config()}.</p>
 *
 * <p>All methods communicate with the server synchronously and raise
 * structured exceptions on failure.</p>
 */
public final class ConfigClient {

    private static final String BASE_PATH = "/api/v1/configs";
    private static final Gson GSON = new GsonBuilder().create();

    private final Transport transport;

    /**
     * Creates a new ConfigClient. Package-private; use {@link com.smplkit.SmplkitClient}.
     *
     * @param transport the HTTP transport
     */
    public ConfigClient(Transport transport) {
        this.transport = transport;
    }

    /**
     * Fetches a single config by UUID.
     *
     * @param id the config UUID
     * @return the matching config
     * @throws SmplNotFoundException if no matching config exists
     */
    public Config get(String id) {
        HttpResponse<String> response = transport.get(BASE_PATH + "/" + id);
        return parseSingleResponse(response.body());
    }

    /**
     * Fetches a single config by its human-readable key.
     *
     * <p>Uses the list endpoint with a {@code filter[key]} query parameter.</p>
     *
     * @param key the config key
     * @return the matching config
     * @throws SmplNotFoundException if no matching config exists
     */
    public Config getByKey(String key) {
        String query = "filter[key]=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        HttpResponse<String> response = transport.get(BASE_PATH, query);
        List<Config> configs = parseListResponse(response.body());
        if (configs.isEmpty()) {
            throw new SmplNotFoundException("Config with key '" + key + "' not found", response.body());
        }
        return configs.get(0);
    }

    /**
     * Creates a new config.
     *
     * @param params the creation parameters
     * @return the created config
     * @throws com.smplkit.errors.SmplValidationException if the server rejects the request
     */
    public Config create(CreateConfigParams params) {
        String body = buildCreateBody(params);
        HttpResponse<String> response = transport.post(BASE_PATH, body);
        return parseSingleResponse(response.body());
    }

    /**
     * Lists all configs for the account.
     *
     * @return an unmodifiable list of configs
     */
    public List<Config> list() {
        HttpResponse<String> response = transport.get(BASE_PATH);
        return Collections.unmodifiableList(parseListResponse(response.body()));
    }

    /**
     * Deletes a config by UUID.
     *
     * @param id the UUID of the config to delete
     * @throws SmplNotFoundException                    if the config does not exist
     * @throws com.smplkit.errors.SmplConflictException if the config has children
     */
    public void delete(String id) {
        transport.delete(BASE_PATH + "/" + id);
    }

    private String buildCreateBody(CreateConfigParams params) {
        JsonObject attributes = new JsonObject();
        attributes.addProperty("name", params.name());
        if (params.key() != null) {
            attributes.addProperty("key", params.key());
        }
        if (params.description() != null) {
            attributes.addProperty("description", params.description());
        }
        if (params.parent() != null) {
            attributes.addProperty("parent", params.parent());
        }
        if (params.values() != null) {
            attributes.add("values", GSON.toJsonTree(params.values()));
        }

        JsonObject data = new JsonObject();
        data.addProperty("type", "config");
        data.add("attributes", attributes);

        JsonObject envelope = new JsonObject();
        envelope.add("data", data);
        return GSON.toJson(envelope);
    }

    private Config parseSingleResponse(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        JsonObject data = root.getAsJsonObject("data");
        return parseResource(data);
    }

    private List<Config> parseListResponse(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        JsonArray dataArray = root.getAsJsonArray("data");
        List<Config> result = new ArrayList<>();
        if (dataArray != null) {
            for (JsonElement element : dataArray) {
                result.add(parseResource(element.getAsJsonObject()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Config parseResource(JsonObject resource) {
        String id = resource.get("id").getAsString();
        JsonObject attrs = resource.getAsJsonObject("attributes");

        String key = getStringOrNull(attrs, "key");
        String name = getStringOrNull(attrs, "name");
        String description = getStringOrNull(attrs, "description");
        String parent = getStringOrNull(attrs, "parent");

        Map<String, Object> values = Map.of();
        if (attrs.has("values") && !attrs.get("values").isJsonNull()) {
            values = GSON.fromJson(attrs.get("values"), Map.class);
            if (values == null) {
                values = Map.of();
            }
        }

        Map<String, Map<String, Object>> environments = Map.of();
        if (attrs.has("environments") && !attrs.get("environments").isJsonNull()) {
            Map<String, Map<String, Object>> parsed = GSON.fromJson(attrs.get("environments"), Map.class);
            if (parsed != null) {
                environments = new HashMap<>(parsed);
            }
        }

        Instant createdAt = parseInstant(attrs, "created_at");
        Instant updatedAt = parseInstant(attrs, "updated_at");

        return new Config(
                id,
                key != null ? key : "",
                name != null ? name : "",
                description,
                parent,
                values,
                environments,
                createdAt,
                updatedAt
        );
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static Instant parseInstant(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return Instant.parse(obj.get(key).getAsString());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
