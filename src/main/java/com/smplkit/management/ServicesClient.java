package com.smplkit.management;

import com.smplkit.errors.ApiExceptionHandler;
import com.smplkit.internal.generated.app.ApiClient;
import com.smplkit.internal.generated.app.ApiException;
import com.smplkit.internal.generated.app.api.ServicesApi;
import com.smplkit.internal.generated.app.model.Service;
import com.smplkit.internal.generated.app.model.ServiceCreateRequest;
import com.smplkit.internal.generated.app.model.ServiceCreateResource;
import com.smplkit.internal.generated.app.model.ServiceListResponse;
import com.smplkit.internal.generated.app.model.ServiceRequest;
import com.smplkit.internal.generated.app.model.ServiceResource;
import com.smplkit.internal.generated.app.model.ServiceResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service CRUD under {@code client.management.services}.
 */
public final class ServicesClient {

    private final ServicesApi api;

    ServicesClient(ApiClient appApiClient) {
        this.api = new ServicesApi(appApiClient);
    }

    /** Return an unsaved {@link com.smplkit.management.Service}. Call {@link com.smplkit.management.Service#save()} to persist. */
    public com.smplkit.management.Service new_(String id, String name) {
        return new com.smplkit.management.Service(this, id, name, null, null);
    }

    /** Lists services using the server's default pagination (first page, up to 1000 rows). */
    public List<com.smplkit.management.Service> list() {
        return list(null, null);
    }

    /**
     * Lists a single page of services. Pass {@code null} for either argument to use the
     * server default ({@code page[number]=1}, {@code page[size]=1000}). The wrapper
     * does not loop — customers paginate by calling this method with successive
     * {@code pageNumber} values.
     */
    public List<com.smplkit.management.Service> list(Integer pageNumber, Integer pageSize) {
        try {
            // Positional args: filterSearch, sort, pageNumber, pageSize, metaTotal.
            ServiceListResponse resp = api.listServices(
                    null, null, pageNumber, pageSize, null);
            List<com.smplkit.management.Service> result = new ArrayList<>();
            if (resp.getData() != null) {
                for (ServiceResource r : resp.getData()) {
                    result.add(resourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Get a service by id. */
    public com.smplkit.management.Service get(String id) {
        try {
            ServiceResponse resp = api.getService(id);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Delete a service by id. */
    public void delete(String id) {
        try {
            api.deleteService(id);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.management.Service _create(com.smplkit.management.Service svc) {
        try {
            ServiceCreateRequest body = buildCreateRequest(svc);
            ServiceResponse resp = api.createService(body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.management.Service _update(com.smplkit.management.Service svc) {
        try {
            ServiceRequest body = buildRequest(svc);
            ServiceResponse resp = api.updateService(svc.getId(), body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    private ServiceRequest buildRequest(com.smplkit.management.Service svc) {
        Service attrs = new Service();
        attrs.setName(svc.getName());
        ServiceResource data = new ServiceResource()
                .id(svc.getId())
                .type(ServiceResource.TypeEnum.SERVICE)
                .attributes(attrs);
        return new ServiceRequest().data(data);
    }

    private ServiceCreateRequest buildCreateRequest(com.smplkit.management.Service svc) {
        Service attrs = new Service();
        attrs.setName(svc.getName());
        ServiceCreateResource data = new ServiceCreateResource()
                .id(svc.getId())
                .type(ServiceCreateResource.TypeEnum.SERVICE)
                .attributes(attrs);
        return new ServiceCreateRequest().data(data);
    }

    private com.smplkit.management.Service responseToModel(ServiceResponse resp) {
        if (resp == null || resp.getData() == null) {
            throw new IllegalStateException("Empty response from services API");
        }
        return resourceToModel(resp.getData());
    }

    private com.smplkit.management.Service resourceToModel(ServiceResource r) {
        String id = r.getId();
        Service attrs = r.getAttributes();
        String name = attrs != null ? attrs.getName() : "";
        Instant createdAt = null;
        Instant updatedAt = null;
        if (attrs != null) {
            if (attrs.getCreatedAt() != null) createdAt = attrs.getCreatedAt().toInstant();
            if (attrs.getUpdatedAt() != null) updatedAt = attrs.getUpdatedAt().toInstant();
        }
        return new com.smplkit.management.Service(this, id, name, createdAt, updatedAt);
    }

    private static com.smplkit.errors.SmplError mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
