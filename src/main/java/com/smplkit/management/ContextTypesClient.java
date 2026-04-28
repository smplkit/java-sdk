package com.smplkit.management;

import com.smplkit.Helpers;
import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.ContextTypesApi;
import com.smplkit.internal.generated.app.model.ContextType;
import com.smplkit.internal.generated.app.model.ContextTypeListResponse;
import com.smplkit.internal.generated.app.model.ContextTypeResource;
import com.smplkit.internal.generated.app.model.ContextTypeResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context-type CRUD under {@code client.management.context_types}.
 */
public final class ContextTypesClient {

    private final ContextTypesApi api;

    ContextTypesClient(ApiClient appApiClient) {
        this.api = new ContextTypesApi(appApiClient);
    }

    /** Return an unsaved {@link com.smplkit.management.ContextType}. Call {@link com.smplkit.management.ContextType#save()} to persist. */
    public com.smplkit.management.ContextType new_(String id) {
        return new com.smplkit.management.ContextType(this, id, Helpers.keyToDisplayName(id), null, null, null);
    }

    /** Return an unsaved {@link com.smplkit.management.ContextType} with an explicit name and attributes. */
    public com.smplkit.management.ContextType new_(String id, String name,
                                                    Map<String, Map<String, Object>> attributes) {
        return new com.smplkit.management.ContextType(this, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                attributes, null, null);
    }

    /** List all context types. */
    public List<com.smplkit.management.ContextType> list() {
        try {
            ContextTypeListResponse resp = api.listContextTypes();
            List<com.smplkit.management.ContextType> result = new ArrayList<>();
            if (resp.getData() != null) {
                for (ContextTypeResource r : resp.getData()) {
                    result.add(resourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Get a context type by id. */
    public com.smplkit.management.ContextType get(String id) {
        try {
            ContextTypeResponse resp = api.getContextType(id);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Delete a context type by id. */
    public void delete(String id) {
        try {
            api.deleteContextType(id);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.management.ContextType _create(com.smplkit.management.ContextType ct) {
        try {
            ContextTypeResponse body = buildRequest(ct);
            ContextTypeResponse resp = api.createContextType(body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.management.ContextType _update(com.smplkit.management.ContextType ct) {
        try {
            ContextTypeResponse body = buildRequest(ct);
            ContextTypeResponse resp = api.updateContextType(ct.getId(), body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    private ContextTypeResponse buildRequest(com.smplkit.management.ContextType ct) {
        ContextType attrs = new ContextType();
        attrs.setId(ct.getId());
        attrs.setName(ct.getName());
        // attributes is Map<String, Map<String, Object>> — cast to Map<String, Object> for the generated model
        if (!ct.getAttributes().isEmpty()) {
            Map<String, Object> flatAttrs = new HashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : ct.getAttributes().entrySet()) {
                flatAttrs.put(e.getKey(), e.getValue());
            }
            attrs.setAttributes(flatAttrs);
        }
        ContextTypeResource data = new ContextTypeResource()
                .id(ct.getId())
                .type(ContextTypeResource.TypeEnum.CONTEXT_TYPE)
                .attributes(attrs);
        return new ContextTypeResponse().data(data);
    }

    private com.smplkit.management.ContextType responseToModel(ContextTypeResponse resp) {
        if (resp == null || resp.getData() == null) {
            throw new IllegalStateException("Empty response from context_types API");
        }
        return resourceToModel(resp.getData());
    }

    @SuppressWarnings("unchecked")
    private com.smplkit.management.ContextType resourceToModel(ContextTypeResource r) {
        String id = r.getId();
        ContextType attrs = r.getAttributes();
        String name = attrs != null ? attrs.getName() : id;
        Map<String, Map<String, Object>> attributes = new HashMap<>();
        if (attrs != null && attrs.getAttributes() != null) {
            for (Map.Entry<String, Object> e : attrs.getAttributes().entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m) {
                    Map<String, Object> meta = new HashMap<>();
                    for (Map.Entry<?, ?> me : m.entrySet()) {
                        meta.put(String.valueOf(me.getKey()), me.getValue());
                    }
                    attributes.put(e.getKey(), meta);
                } else {
                    attributes.put(e.getKey(), new HashMap<>());
                }
            }
        }
        Instant createdAt = null;
        Instant updatedAt = null;
        if (attrs != null) {
            if (attrs.getCreatedAt() != null) createdAt = attrs.getCreatedAt().toInstant();
            if (attrs.getUpdatedAt() != null) updatedAt = attrs.getUpdatedAt().toInstant();
        }
        return new com.smplkit.management.ContextType(this, id, name, attributes, createdAt, updatedAt);
    }

    private static com.smplkit.errors.SmplException mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
