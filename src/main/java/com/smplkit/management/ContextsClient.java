package com.smplkit.management;

import com.smplkit.Context;
import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.model.ContextBulkItem;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.app.model.ContextListResponse;
import com.smplkit.internal.generated.app.model.ContextResource;
import com.smplkit.internal.generated.app.model.ContextResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context registration and read/delete under {@code client.management.contexts}.
 *
 * <p>Write side: {@link #register} buffers contexts for background flush; pass {@code flush=true}
 * to await the round-trip immediately (useful for IaC scripts).</p>
 *
 * <p>Read side: {@link #list}, {@link #get}, {@link #delete}. The id is the composite
 * {@code "{type}:{key}"} form; a {@code (type, key)} two-arg overload is accepted everywhere.</p>
 */
public final class ContextsClient {

    private final ContextsApi api;
    private final ContextRegistrationBuffer buffer;

    ContextsClient(ApiClient appApiClient, ContextRegistrationBuffer buffer) {
        this.api = new ContextsApi(appApiClient);
        this.buffer = buffer;
    }

    /**
     * Buffer contexts for registration; optionally flush immediately.
     *
     * @param contexts one or more contexts to register
     * @param flush    when {@code true}, flushes immediately; default is {@code false}
     */
    public void register(List<Context> contexts, boolean flush) {
        buffer.observeAll(contexts);
        if (flush) flush();
    }

    /** Buffer a single context; optionally flush immediately. */
    public void register(Context context, boolean flush) {
        buffer.observe(context);
        if (flush) flush();
    }

    /** Buffer contexts for background flush (flush=false). */
    public void register(List<Context> contexts) {
        register(contexts, false);
    }

    /** Buffer a single context for background flush (flush=false). */
    public void register(Context context) {
        register(context, false);
    }

    /** Send any pending observations to the server immediately. */
    public void flush() {
        List<Map<String, Object>> batch = buffer.drain();
        if (batch.isEmpty()) return;
        List<ContextBulkItem> items = new ArrayList<>();
        for (Map<String, Object> entry : batch) {
            String type = (String) entry.get("type");
            String key = (String) entry.get("key");
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) entry.getOrDefault("attributes", Map.of());
            items.add(new ContextBulkItem().type(type).key(key).attributes(attrs));
        }
        try {
            api.bulkRegisterContexts(new ContextBulkRegister().contexts(items));
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** List all contexts of the given type. */
    public List<ContextEntity> list(String type) {
        try {
            ContextListResponse resp = api.listContexts(type);
            List<ContextEntity> result = new ArrayList<>();
            if (resp.getData() != null) {
                for (ContextResource r : resp.getData()) {
                    result.add(resourceToEntity(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Get a context by composite {@code "type:key"} id.
     */
    public ContextEntity get(String compositeId) {
        String[] parts = splitCompositeId(compositeId, null);
        return fetchContext(parts[0] + ":" + parts[1]);
    }

    /**
     * Get a context by type and key.
     */
    public ContextEntity get(String type, String key) {
        return fetchContext(type + ":" + key);
    }

    /**
     * Delete a context by composite {@code "type:key"} id.
     */
    public void delete(String compositeId) {
        String[] parts = splitCompositeId(compositeId, null);
        deleteContext(parts[0] + ":" + parts[1]);
    }

    /**
     * Delete a context by type and key.
     */
    public void delete(String type, String key) {
        deleteContext(type + ":" + key);
    }

    private ContextEntity fetchContext(String compositeId) {
        try {
            ContextResponse resp = api.getContext(compositeId);
            if (resp == null || resp.getData() == null) {
                throw new com.smplkit.errors.SmplNotFoundException(
                        "Context '" + compositeId + "' not found", null);
            }
            return resourceToEntity(resp.getData());
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    private void deleteContext(String compositeId) {
        try {
            api.deleteContext(compositeId);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    private static String[] splitCompositeId(String idOrType, String key) {
        if (key != null) return new String[]{idOrType, key};
        int colon = idOrType.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                    "context id must be 'type:key' (got '" + idOrType + "'); " +
                    "alternatively pass type and key as separate args");
        }
        return new String[]{idOrType.substring(0, colon), idOrType.substring(colon + 1)};
    }

    @SuppressWarnings("unchecked")
    private static ContextEntity resourceToEntity(ContextResource r) {
        String compositeId = r.getId() != null ? r.getId() : "";
        String type, key;
        int colon = compositeId.indexOf(':');
        if (colon >= 0) {
            type = compositeId.substring(0, colon);
            key = compositeId.substring(colon + 1);
        } else {
            type = compositeId;
            key = "";
        }
        var attrs = r.getAttributes();
        String name = attrs != null ? attrs.getName() : null;
        Map<String, Object> attributes = new HashMap<>();
        if (attrs != null && attrs.getAttributes() != null) {
            attributes.putAll(attrs.getAttributes());
        }
        Instant createdAt = null;
        Instant updatedAt = null;
        if (attrs != null) {
            if (attrs.getCreatedAt() != null) createdAt = attrs.getCreatedAt().toInstant();
            if (attrs.getUpdatedAt() != null) updatedAt = attrs.getUpdatedAt().toInstant();
        }
        return new ContextEntity(type, key, name, attributes, createdAt, updatedAt);
    }

    private static com.smplkit.errors.SmplException mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
