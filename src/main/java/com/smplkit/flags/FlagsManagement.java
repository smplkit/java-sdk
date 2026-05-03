package com.smplkit.flags;

import com.smplkit.Helpers;
import com.smplkit.errors.SmplError;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagResponse;

import java.util.List;
import java.util.Map;

/**
 * Management-plane API for flags: CRUD operations and factory methods.
 *
 * <p>Obtain an instance via {@link FlagsClient#management()}.</p>
 */
public final class FlagsManagement {

    private final FlagsClient client;

    FlagsManagement(FlagsClient client) {
        this.client = client;
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    public Flag<Boolean> newBooleanFlag(String id, boolean defaultValue) {
        return newBooleanFlag(id, defaultValue, null, null);
    }

    public Flag<Boolean> newBooleanFlag(String id, boolean defaultValue, String name, String description) {
        return new Flag<>(client, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                "BOOLEAN", defaultValue,
                List.of(Map.of("name", "True", "value", true), Map.of("name", "False", "value", false)),
                description, null, null, null, Boolean.class);
    }

    public Flag<String> newStringFlag(String id, String defaultValue) {
        return newStringFlag(id, defaultValue, null, null);
    }

    public Flag<String> newStringFlag(String id, String defaultValue, String name, String description) {
        return newStringFlag(id, defaultValue, name, description, null);
    }

    public Flag<String> newStringFlag(String id, String defaultValue, String name, String description,
                                      List<Map<String, Object>> values) {
        return new Flag<>(client, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                "STRING", defaultValue, values, description, null, null, null, String.class);
    }

    public Flag<Number> newNumberFlag(String id, Number defaultValue) {
        return newNumberFlag(id, defaultValue, null, null);
    }

    public Flag<Number> newNumberFlag(String id, Number defaultValue, String name, String description) {
        return newNumberFlag(id, defaultValue, name, description, null);
    }

    public Flag<Number> newNumberFlag(String id, Number defaultValue, String name, String description,
                                      List<Map<String, Object>> values) {
        return new Flag<>(client, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                "NUMERIC", defaultValue, values, description, null, null, null, Number.class);
    }

    public Flag<Object> newJsonFlag(String id, Object defaultValue) {
        return newJsonFlag(id, defaultValue, null, null);
    }

    public Flag<Object> newJsonFlag(String id, Object defaultValue, String name, String description) {
        return newJsonFlag(id, defaultValue, name, description, null);
    }

    public Flag<Object> newJsonFlag(String id, Object defaultValue, String name, String description,
                                    List<Map<String, Object>> values) {
        return new Flag<>(client, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                "JSON", defaultValue, values, description, null, null, null, Object.class);
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    /** Fetches a flag by id. */
    public Flag<?> get(String id) {
        try {
            FlagResponse response = client.flagsApi.getFlag(id);
            return client.parseSingleResponse(response);
        } catch (ApiException e) {
            throw FlagsClient.mapException(e);
        }
    }

    /** Lists all flags. */
    public List<Flag<?>> list() {
        try {
            FlagListResponse response = client.flagsApi.listFlags(null, null, null, null);
            return client.parseListResponse(response);
        } catch (ApiException e) {
            throw FlagsClient.mapException(e);
        }
    }

    /** Deletes a flag by id. */
    public void delete(String id) {
        try {
            client.flagsApi.deleteFlag(id);
        } catch (ApiException e) {
            throw FlagsClient.mapException(e);
        }
    }
}
