package com.smplkit.platform;

import com.smplkit.internal.ApiExceptionHandler;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.EnvironmentsApi;
import com.smplkit.internal.generated.app.model.Environment;
import com.smplkit.internal.generated.app.model.EnvironmentCreateRequest;
import com.smplkit.internal.generated.app.model.EnvironmentCreateResource;
import com.smplkit.internal.generated.app.model.EnvironmentListResponse;
import com.smplkit.internal.generated.app.model.EnvironmentResource;
import com.smplkit.internal.generated.app.model.EnvironmentRequest;
import com.smplkit.internal.generated.app.model.EnvironmentResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Sync environment CRUD ({@code client.platform.environments}).
 */
public final class EnvironmentsClient {

    private final EnvironmentsApi api;

    EnvironmentsClient(ApiClient appApiClient) {
        this.api = new EnvironmentsApi(appApiClient);
    }

    /**
     * Return an unsaved {@link com.smplkit.platform.Environment} with no color
     * and the default {@link EnvironmentClassification#STANDARD} classification.
     * Call {@link com.smplkit.platform.Environment#save()} to persist.
     *
     * @param id stable caller-supplied identifier for the environment
     * @param name human-readable display name
     * @return an unsaved {@link com.smplkit.platform.Environment} bound to this client
     */
    public com.smplkit.platform.Environment new_(String id, String name) {
        return new_(id, name, null, EnvironmentClassification.STANDARD);
    }

    /**
     * Return an unsaved {@link com.smplkit.platform.Environment}. Call
     * {@link com.smplkit.platform.Environment#save()} to persist.
     *
     * @param id stable caller-supplied identifier for the environment
     * @param name human-readable display name
     * @param color optional display color (hex string), or {@code null} for none
     * @param classification the environment classification; {@code null} defaults
     *     to {@link EnvironmentClassification#STANDARD}
     * @return an unsaved {@link com.smplkit.platform.Environment} bound to this client
     */
    public com.smplkit.platform.Environment new_(String id, String name,
                                                    String color,
                                                    EnvironmentClassification classification) {
        return new com.smplkit.platform.Environment(this, id, name, color,
                classification != null ? classification : EnvironmentClassification.STANDARD,
                true, null, null);
    }

    /**
     * Lists environments using the server's default pagination (first page, up to 1000 rows).
     *
     * @return the environments on the first page
     */
    public List<com.smplkit.platform.Environment> list() {
        return list(null, null);
    }

    /**
     * Lists a single page of environments. Pass {@code null} for either argument to use the
     * server default ({@code page[number]=1}, {@code page[size]=1000}). The wrapper
     * does not loop — customers paginate by calling this method with successive
     * {@code pageNumber} values.
     *
     * @param pageNumber 1-based page to fetch. Defaults to the first page.
     * @param pageSize maximum items per page; server default when omitted.
     * @return the environments on the requested page
     */
    public List<com.smplkit.platform.Environment> list(Integer pageNumber, Integer pageSize) {
        try {
            // Positional args: filterSearch, filterClassification,
            // filterManaged, sort, pageNumber, pageSize, metaTotal.
            EnvironmentListResponse resp = api.listEnvironments(
                    null, null, null, null, pageNumber, pageSize, null);
            List<com.smplkit.platform.Environment> result = new ArrayList<>();
            if (resp.getData() != null) {
                for (EnvironmentResource r : resp.getData()) {
                    result.add(resourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Fetch a single environment by id.
     *
     * @param id identifier of the environment to fetch
     * @return the matching environment
     * @throws com.smplkit.errors.NotFoundError if no environment with that id exists
     */
    public com.smplkit.platform.Environment get(String id) {
        try {
            EnvironmentResponse resp = api.getEnvironment(id);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Delete an environment by id.
     *
     * @param id identifier of the environment to delete
     */
    public void delete(String id) {
        try {
            api.deleteEnvironment(id, null);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.platform.Environment _create(com.smplkit.platform.Environment env) {
        try {
            EnvironmentCreateRequest body = buildCreateRequest(env);
            EnvironmentResponse resp = api.createEnvironment(body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.platform.Environment _update(com.smplkit.platform.Environment env) {
        try {
            EnvironmentRequest body = buildRequest(env);
            EnvironmentResponse resp = api.updateEnvironment(env.getId(), body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    private Environment buildAttrs(com.smplkit.platform.Environment env) {
        Environment attrs = new Environment();
        attrs.setName(env.getName());
        if (env.getColor() != null) attrs.setColor(env.getColor());
        // Round-trip classification + managed (get-mutate-put). The server
        // requires admin when classification or managed is changing, so
        // non-admin edits to name/color still work as long as we send the
        // existing values back.
        attrs.setClassification(env.getClassification() == EnvironmentClassification.AD_HOC
                ? Environment.ClassificationEnum.AD_HOC
                : Environment.ClassificationEnum.STANDARD);
        attrs.setManaged(env.isManaged());
        return attrs;
    }

    private EnvironmentRequest buildRequest(com.smplkit.platform.Environment env) {
        EnvironmentResource data = new EnvironmentResource()
                .id(env.getId())
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT)
                .attributes(buildAttrs(env));
        return new EnvironmentRequest().data(data);
    }

    private EnvironmentCreateRequest buildCreateRequest(com.smplkit.platform.Environment env) {
        // Create uses a dedicated envelope where the caller-supplied id is required.
        EnvironmentCreateResource data = new EnvironmentCreateResource()
                .id(env.getId())
                .type(EnvironmentCreateResource.TypeEnum.ENVIRONMENT)
                .attributes(buildAttrs(env));
        return new EnvironmentCreateRequest().data(data);
    }

    private com.smplkit.platform.Environment responseToModel(EnvironmentResponse resp) {
        if (resp == null || resp.getData() == null) {
            throw new IllegalStateException("Empty response from environments API");
        }
        return resourceToModel(resp.getData());
    }

    private com.smplkit.platform.Environment resourceToModel(EnvironmentResource r) {
        String id = r.getId();
        Environment attrs = r.getAttributes();
        String name = attrs != null ? attrs.getName() : "";
        String color = attrs != null ? attrs.getColor() : null;
        EnvironmentClassification classification = EnvironmentClassification.STANDARD;
        boolean managed = false;
        if (attrs != null) {
            Environment.ClassificationEnum cls = attrs.getClassification();
            if (cls == Environment.ClassificationEnum.AD_HOC) {
                classification = EnvironmentClassification.AD_HOC;
            }
            Boolean rawManaged = attrs.getManaged();
            if (rawManaged != null) managed = rawManaged;
        }
        Instant createdAt = null;
        Instant updatedAt = null;
        if (attrs != null) {
            if (attrs.getCreatedAt() != null) createdAt = attrs.getCreatedAt().toInstant();
            if (attrs.getUpdatedAt() != null) updatedAt = attrs.getUpdatedAt().toInstant();
        }
        return new com.smplkit.platform.Environment(this, id, name, color, classification, managed, createdAt, updatedAt);
    }

    private static com.smplkit.errors.SmplError mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
