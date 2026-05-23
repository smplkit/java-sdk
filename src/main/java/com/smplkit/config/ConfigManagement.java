package com.smplkit.config;

import com.smplkit.Helpers;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.model.ConfigBulkItem;
import com.smplkit.internal.generated.config.model.ConfigBulkRequest;
import com.smplkit.internal.generated.config.model.ConfigItemDefinition;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;
import com.smplkit.internal.Debug;
import com.smplkit.management.ConfigRegistrationBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Management-plane API for configs: CRUD operations and factory methods.
 *
 * <p>Obtain an instance via {@link ConfigClient#management()}.</p>
 */
public final class ConfigManagement {

    private static final int REGISTRATION_FLUSH_SIZE = 50;

    private final ConfigClient client;
    private final ConfigRegistrationBuffer buffer = new ConfigRegistrationBuffer();

    ConfigManagement(ConfigClient client) {
        this.client = client;
    }

    // -----------------------------------------------------------------------
    // Discovery: declare + add_item + flush (ADR-037 §2.13/§2.14)
    // -----------------------------------------------------------------------

    /**
     * Queue a configuration declaration for bulk-discovery upload.
     * Called by {@link ConfigClient#bind} and
     * {@link ConfigClient#get(String, String, Object)}.
     */
    public void registerConfig(String configId, String service, String environment,
                               String parent, String name, String description) {
        buffer.declare(configId, service, environment, parent, name, description);
        if (buffer.pendingCount() >= REGISTRATION_FLUSH_SIZE) {
            triggerBackgroundFlush();
        }
    }

    /**
     * Queue a config item declaration. Called by {@link ConfigClient#bind}
     * (for every leaf in the bound target) and
     * {@link ConfigClient#get(String, String, Object)} (for the key being
     * read with a default).
     */
    public void registerConfigItem(String configId, String itemKey, String itemType,
                                   Object defaultValue, String description) {
        buffer.addItem(configId, itemKey, itemType, defaultValue, description);
        if (buffer.pendingCount() >= REGISTRATION_FLUSH_SIZE) {
            triggerBackgroundFlush();
        }
    }

    /** Number of pending config declarations awaiting flush. */
    public int pendingCount() {
        return buffer.pendingCount();
    }

    /**
     * Sends any pending config declarations to {@code POST /api/v1/configs/bulk}.
     * Per ADR-024 §2.9 the bulk endpoint is plan-limit-exempt; failures here
     * never propagate to customer code. Drained entries are not requeued.
     */
    public void flush() {
        List<ConfigRegistrationBuffer.Entry> batch = buffer.drain();
        if (batch.isEmpty()) return;

        ConfigBulkRequest body = new ConfigBulkRequest();
        List<ConfigBulkItem> items = new ArrayList<>(batch.size());
        for (ConfigRegistrationBuffer.Entry entry : batch) {
            ConfigBulkItem item = new ConfigBulkItem();
            item.setId(entry.id);
            if (entry.service != null) item.setService(entry.service);
            if (entry.environment != null) item.setEnvironment(entry.environment);
            if (entry.parent != null) item.setParent(entry.parent);
            if (entry.name != null) item.setName(entry.name);
            if (entry.description != null) item.setDescription(entry.description);
            if (!entry.items.isEmpty()) {
                Map<String, ConfigItemDefinition> defs = new LinkedHashMap<>();
                for (Map.Entry<String, ConfigRegistrationBuffer.ItemEntry> e : entry.items.entrySet()) {
                    ConfigItemDefinition def = new ConfigItemDefinition();
                    def.setValue(e.getValue().defaultValue);
                    def.setType(toTypeEnum(e.getValue().itemType));
                    if (e.getValue().description != null) {
                        def.setDescription(e.getValue().description);
                    }
                    defs.put(e.getKey(), def);
                }
                item.setItems(defs);
            }
            items.add(item);
        }
        body.setConfigs(items);
        try {
            client.configsApi.bulkRegisterConfigs(body);
        } catch (Exception ex) {
            // Fire-and-forget per ADR-024 §2.9.
            Debug.log("registration", "bulk register failed: " + ex.getMessage());
        }
    }

    /** Latest in-flight background-flush thread; package-private for test waits. */
    volatile Thread lastFlushThread;

    private synchronized void triggerBackgroundFlush() {
        Thread existing = lastFlushThread;
        if (existing != null && existing.isAlive()) return; // Coalesce — one in-flight flush at a time.
        Thread t = new Thread(() -> {
            try { flush(); } catch (Exception ignored) { }
        }, "smplkit-config-flush");
        t.setDaemon(true);
        lastFlushThread = t;
        t.start();
    }

    private static ConfigItemDefinition.TypeEnum toTypeEnum(String itemType) {
        return switch (itemType) {
            case "STRING" -> ConfigItemDefinition.TypeEnum.STRING;
            case "NUMBER" -> ConfigItemDefinition.TypeEnum.NUMBER;
            case "BOOLEAN" -> ConfigItemDefinition.TypeEnum.BOOLEAN;
            case "JSON" -> ConfigItemDefinition.TypeEnum.JSON;
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Returns a new unsaved {@link Config}. Call {@link Config#save()} to persist.
     *
     * @param id the config id (slug)
     * @return a new unsaved Config
     */
    public Config new_(String id) {
        return new_(id, null, null, (String) null);
    }

    /**
     * Returns a new unsaved {@link Config}. Call {@link Config#save()} to persist.
     *
     * @param id          the config id (slug)
     * @param name        display name (auto-generated from id if null)
     * @param description optional description
     * @param parent      parent config identifier, or null
     * @return a new unsaved Config
     */
    public Config new_(String id, String name, String description, String parent) {
        Config config = new Config(client, id, name != null ? name : Helpers.keyToDisplayName(id));
        config.setDescription(description);
        config.setParent(parent);
        return config;
    }

    /** Convenience overload accepting a {@link Config} for the parent (uses its id). */
    public Config new_(String id, String name, String description, Config parent) {
        return new_(id, name, description, parent != null ? parent.getId() : null);
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    /**
     * Fetches a config by its id (slug).
     *
     * @param id the config id
     * @return the matching Config
     * @throws com.smplkit.errors.NotFoundError if no matching config exists
     */
    public Config get(String id) {
        try {
            ConfigResponse response = client.configsApi.getConfig(id);
            return client.parseResource(response.getData());
        } catch (ApiException e) {
            throw ConfigClient.mapException(e);
        }
    }

    /**
     * Lists configs using the server's default pagination (first page, up to 1000 rows).
     *
     * @return an unmodifiable list of configs
     */
    public List<Config> list() {
        return list(null, null);
    }

    /**
     * Lists a single page of configs. Pass {@code null} for either argument to use the
     * server default ({@code page[number]=1}, {@code page[size]=1000}). The wrapper
     * does not loop — customers paginate by calling this method with successive
     * {@code pageNumber} values.
     */
    public List<Config> list(Integer pageNumber, Integer pageSize) {
        try {
            // Positional args: filterParent, filterSearch, filterManaged,
            // sort, pageNumber, pageSize, metaTotal.
            ConfigListResponse response = client.configsApi.listConfigs(
                    null, null, null, null, pageNumber, pageSize, null);
            List<ConfigResource> data = response.getData();
            if (data == null) {
                return Collections.emptyList();
            }
            List<Config> result = new ArrayList<>(data.size());
            for (ConfigResource resource : data) {
                result.add(client.parseResource(resource));
            }
            return Collections.unmodifiableList(result);
        } catch (ApiException e) {
            throw ConfigClient.mapException(e);
        }
    }

    /**
     * Deletes a config by id.
     *
     * @param id the config id
     * @throws com.smplkit.errors.NotFoundError if the config does not exist
     */
    public void delete(String id) {
        try {
            client.configsApi.deleteConfig(id);
        } catch (ApiException e) {
            throw ConfigClient.mapException(e);
        }
    }
}
