package com.smplkit.platform;

import com.smplkit.Helpers;
import com.smplkit.internal.ApiExceptionHandler;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.ContextTypesApi;
import com.smplkit.internal.generated.app.model.ContextType;
import com.smplkit.internal.generated.app.model.ContextTypeListResponse;
import com.smplkit.internal.generated.app.model.ContextTypeResource;
import com.smplkit.internal.generated.app.model.ContextTypeRequest;
import com.smplkit.internal.generated.app.model.ContextTypeResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sync context-type CRUD ({@code client.platform.context_types}).
 */
public final class ContextTypesClient {

    private final ContextTypesApi api;

    ContextTypesClient(ApiClient appApiClient) {
        this.api = new ContextTypesApi(appApiClient);
    }

    /**
     * Return an unsaved {@link com.smplkit.platform.ContextType} whose display name
     * is derived from {@code id}. Call {@link com.smplkit.platform.ContextType#save()}
     * to persist.
     *
     * @param id stable, human-readable identifier for the context type
     *     (for example {@code "user"})
     * @return an unsaved {@link com.smplkit.platform.ContextType} bound to this client
     */
    public com.smplkit.platform.ContextType new_(String id) {
        return new com.smplkit.platform.ContextType(this, id, Helpers.keyToDisplayName(id), null, null, null);
    }

    /**
     * Return an unsaved {@link com.smplkit.platform.ContextType} with an explicit name
     * and attributes. Call {@link com.smplkit.platform.ContextType#save()} to persist.
     *
     * @param id stable, human-readable identifier for the context type
     *     (for example {@code "user"})
     * @param name display name shown in the Console; a name derived from
     *     {@code id} is used when {@code null}
     * @param attributes known-attribute slots, keyed by attribute name, with a
     *     metadata map per slot; may be {@code null} for no declared attributes
     * @return an unsaved {@link com.smplkit.platform.ContextType} bound to this client
     */
    public com.smplkit.platform.ContextType new_(String id, String name,
                                                    Map<String, Map<String, Object>> attributes) {
        return new com.smplkit.platform.ContextType(this, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                attributes, null, null);
    }

    /**
     * Lists context types using the server's default pagination (first page, up to 1000 rows).
     *
     * @return the context types on the first page
     */
    public List<com.smplkit.platform.ContextType> list() {
        return list(null, null);
    }

    /**
     * Lists a single page of context types. Pass {@code null} for either argument to use the
     * server default ({@code page[number]=1}, {@code page[size]=1000}). The wrapper
     * does not loop — customers paginate by calling this method with successive
     * {@code pageNumber} values.
     *
     * @param pageNumber 1-based page to fetch. Defaults to the first page.
     * @param pageSize maximum items per page; server default when omitted.
     * @return the context types on the requested page
     */
    public List<com.smplkit.platform.ContextType> list(Integer pageNumber, Integer pageSize) {
        try {
            ContextTypeListResponse resp = api.listContextTypes(null, pageNumber, pageSize, null);
            List<com.smplkit.platform.ContextType> result = new ArrayList<>();
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

    /**
     * Fetch a single context type by id.
     *
     * @param id identifier of the context type to fetch
     * @return the matching context type
     * @throws com.smplkit.errors.NotFoundError if no context type with that id exists
     */
    public com.smplkit.platform.ContextType get(String id) {
        try {
            ContextTypeResponse resp = api.getContextType(id);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Delete a context type by id.
     *
     * @param id identifier of the context type to delete
     */
    public void delete(String id) {
        try {
            api.deleteContextType(id);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.platform.ContextType _create(com.smplkit.platform.ContextType ct) {
        try {
            ContextTypeRequest body = buildRequest(ct);
            ContextTypeResponse resp = api.createContextType(body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.platform.ContextType _update(com.smplkit.platform.ContextType ct) {
        try {
            ContextTypeRequest body = buildRequest(ct);
            ContextTypeResponse resp = api.updateContextType(ct.getId(), body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    private ContextTypeRequest buildRequest(com.smplkit.platform.ContextType ct) {
        ContextType attrs = new ContextType();
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
        return new ContextTypeRequest().data(data);
    }

    private com.smplkit.platform.ContextType responseToModel(ContextTypeResponse resp) {
        if (resp == null || resp.getData() == null) {
            throw new IllegalStateException("Empty response from context_types API");
        }
        return resourceToModel(resp.getData());
    }

    @SuppressWarnings("unchecked")
    private com.smplkit.platform.ContextType resourceToModel(ContextTypeResource r) {
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
        return new com.smplkit.platform.ContextType(this, id, name, attributes, createdAt, updatedAt);
    }

    private static com.smplkit.errors.SmplError mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
