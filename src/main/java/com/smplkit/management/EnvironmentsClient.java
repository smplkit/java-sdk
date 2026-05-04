package com.smplkit.management;

import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.EnvironmentsApi;
import com.smplkit.internal.generated.app.model.Environment;
import com.smplkit.internal.generated.app.model.EnvironmentListResponse;
import com.smplkit.internal.generated.app.model.EnvironmentResource;
import com.smplkit.internal.generated.app.model.EnvironmentResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Environment CRUD under {@code client.management.environments}.
 */
public final class EnvironmentsClient {

    private final EnvironmentsApi api;

    EnvironmentsClient(ApiClient appApiClient) {
        this.api = new EnvironmentsApi(appApiClient);
    }

    /** Return an unsaved {@link com.smplkit.management.Environment}. Call {@link com.smplkit.management.Environment#save()} to persist. */
    public com.smplkit.management.Environment new_(String id, String name,
                                                    String color,
                                                    EnvironmentClassification classification) {
        return new com.smplkit.management.Environment(this, id, name, color,
                classification != null ? classification : EnvironmentClassification.STANDARD,
                null, null);
    }

    /** List all environments. */
    public List<com.smplkit.management.Environment> list() {
        try {
            EnvironmentListResponse resp = api.listEnvironments();
            List<com.smplkit.management.Environment> result = new ArrayList<>();
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

    /** Get an environment by id. */
    public com.smplkit.management.Environment get(String id) {
        try {
            EnvironmentResponse resp = api.getEnvironment(id);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Delete an environment by id. */
    public void delete(String id) {
        try {
            api.deleteEnvironment(id);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.management.Environment _create(com.smplkit.management.Environment env) {
        try {
            EnvironmentResponse body = buildRequest(env);
            EnvironmentResponse resp = api.createEnvironment(body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.management.Environment _update(com.smplkit.management.Environment env) {
        try {
            EnvironmentResponse body = buildRequest(env);
            EnvironmentResponse resp = api.updateEnvironment(env.getId(), body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    private EnvironmentResponse buildRequest(com.smplkit.management.Environment env) {
        Environment attrs = new Environment();
        attrs.setName(env.getName());
        if (env.getColor() != null) attrs.setColor(env.getColor());
        // Round-trip classification (get-mutate-put). The server requires
        // admin only when classification is changing, so non-admin
        // edits to name/color still work as long as we send the existing
        // value back.
        attrs.setClassification(env.getClassification() == EnvironmentClassification.AD_HOC
                ? Environment.ClassificationEnum.AD_HOC
                : Environment.ClassificationEnum.STANDARD);
        EnvironmentResource data = new EnvironmentResource()
                .id(env.getId())
                .type(EnvironmentResource.TypeEnum.ENVIRONMENT)
                .attributes(attrs);
        return new EnvironmentResponse().data(data);
    }

    private com.smplkit.management.Environment responseToModel(EnvironmentResponse resp) {
        if (resp == null || resp.getData() == null) {
            throw new IllegalStateException("Empty response from environments API");
        }
        return resourceToModel(resp.getData());
    }

    private com.smplkit.management.Environment resourceToModel(EnvironmentResource r) {
        String id = r.getId();
        Environment attrs = r.getAttributes();
        String name = attrs != null ? attrs.getName() : "";
        String color = attrs != null ? attrs.getColor() : null;
        EnvironmentClassification classification = EnvironmentClassification.STANDARD;
        if (attrs != null) {
            Environment.ClassificationEnum cls = attrs.getClassification();
            if (cls == Environment.ClassificationEnum.AD_HOC) {
                classification = EnvironmentClassification.AD_HOC;
            }
        }
        Instant createdAt = null;
        Instant updatedAt = null;
        if (attrs != null) {
            if (attrs.getCreatedAt() != null) createdAt = attrs.getCreatedAt().toInstant();
            if (attrs.getUpdatedAt() != null) updatedAt = attrs.getUpdatedAt().toInstant();
        }
        return new com.smplkit.management.Environment(this, id, name, color, classification, createdAt, updatedAt);
    }

    private static com.smplkit.errors.SmplError mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
