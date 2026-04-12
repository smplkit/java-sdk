package com.smplkit.config;

import com.smplkit.Helpers;
import com.smplkit.internal.generated.config.ApiException;
import com.smplkit.internal.generated.config.model.ConfigListResponse;
import com.smplkit.internal.generated.config.model.ConfigResource;
import com.smplkit.internal.generated.config.model.ConfigResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Management-plane API for configs: CRUD operations and factory methods.
 *
 * <p>Obtain an instance via {@link ConfigClient#management()}.</p>
 */
public final class ConfigManagement {

    private final ConfigClient client;

    ConfigManagement(ConfigClient client) {
        this.client = client;
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
        return new_(id, null, null, null);
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

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    /**
     * Fetches a config by its id (slug).
     *
     * @param id the config id
     * @return the matching Config
     * @throws com.smplkit.errors.SmplNotFoundException if no matching config exists
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
     * Lists all configs for the account.
     *
     * @return an unmodifiable list of configs
     */
    public List<Config> list() {
        try {
            ConfigListResponse response = client.configsApi.listConfigs(null);
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
     * @throws com.smplkit.errors.SmplNotFoundException if the config does not exist
     */
    public void delete(String id) {
        try {
            client.configsApi.deleteConfig(id);
        } catch (ApiException e) {
            throw ConfigClient.mapException(e);
        }
    }
}
