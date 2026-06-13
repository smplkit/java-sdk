package com.smplkit.platform;

import com.smplkit.internal.ApiExceptionHandler;
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
 * Sync service CRUD ({@code client.platform.services}).
 */
public final class ServicesClient {

    private final ServicesApi api;

    ServicesClient(ApiClient appApiClient) {
        this.api = new ServicesApi(appApiClient);
    }

    /**
     * Return an unsaved {@link com.smplkit.platform.Service}. Call
     * {@link com.smplkit.platform.Service#save()} to persist.
     *
     * @param id stable, human-readable identifier for the service
     * @param name display name shown in the Console
     * @return an unsaved {@link com.smplkit.platform.Service} bound to this client
     */
    public com.smplkit.platform.Service new_(String id, String name) {
        return new com.smplkit.platform.Service(this, id, name, null, null);
    }

    /**
     * Lists services using the server's default pagination (first page, up to 1000 rows).
     *
     * @return the services on the first page
     */
    public List<com.smplkit.platform.Service> list() {
        return list(null, null);
    }

    /**
     * Lists a single page of services. Pass {@code null} for either argument to use the
     * server default ({@code page[number]=1}, {@code page[size]=1000}). The wrapper
     * does not loop — customers paginate by calling this method with successive
     * {@code pageNumber} values.
     *
     * @param pageNumber 1-based page to fetch. Defaults to the first page.
     * @param pageSize maximum items per page; server default when omitted.
     * @return the services on the requested page
     */
    public List<com.smplkit.platform.Service> list(Integer pageNumber, Integer pageSize) {
        try {
            // Positional args: filterSearch, sort, pageNumber, pageSize, metaTotal.
            ServiceListResponse resp = api.listServices(
                    null, null, pageNumber, pageSize, null);
            List<com.smplkit.platform.Service> result = new ArrayList<>();
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

    /**
     * Fetch a single service by id.
     *
     * @param id identifier of the service to fetch
     * @return the matching service
     * @throws com.smplkit.errors.NotFoundError if no service with that id exists
     */
    public com.smplkit.platform.Service get(String id) {
        try {
            ServiceResponse resp = api.getService(id);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Delete a service by id.
     *
     * @param id identifier of the service to delete
     */
    public void delete(String id) {
        try {
            api.deleteService(id);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.platform.Service _create(com.smplkit.platform.Service svc) {
        try {
            ServiceCreateRequest body = buildCreateRequest(svc);
            ServiceResponse resp = api.createService(body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    com.smplkit.platform.Service _update(com.smplkit.platform.Service svc) {
        try {
            ServiceRequest body = buildRequest(svc);
            ServiceResponse resp = api.updateService(svc.getId(), body);
            return responseToModel(resp);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    private ServiceRequest buildRequest(com.smplkit.platform.Service svc) {
        Service attrs = new Service();
        attrs.setName(svc.getName());
        ServiceResource data = new ServiceResource()
                .id(svc.getId())
                .type(ServiceResource.TypeEnum.SERVICE)
                .attributes(attrs);
        return new ServiceRequest().data(data);
    }

    private ServiceCreateRequest buildCreateRequest(com.smplkit.platform.Service svc) {
        Service attrs = new Service();
        attrs.setName(svc.getName());
        ServiceCreateResource data = new ServiceCreateResource()
                .id(svc.getId())
                .type(ServiceCreateResource.TypeEnum.SERVICE)
                .attributes(attrs);
        return new ServiceCreateRequest().data(data);
    }

    private com.smplkit.platform.Service responseToModel(ServiceResponse resp) {
        if (resp == null || resp.getData() == null) {
            throw new IllegalStateException("Empty response from services API");
        }
        return resourceToModel(resp.getData());
    }

    private com.smplkit.platform.Service resourceToModel(ServiceResource r) {
        String id = r.getId();
        Service attrs = r.getAttributes();
        String name = attrs != null ? attrs.getName() : "";
        Instant createdAt = null;
        Instant updatedAt = null;
        if (attrs != null) {
            if (attrs.getCreatedAt() != null) createdAt = attrs.getCreatedAt().toInstant();
            if (attrs.getUpdatedAt() != null) updatedAt = attrs.getUpdatedAt().toInstant();
        }
        return new com.smplkit.platform.Service(this, id, name, createdAt, updatedAt);
    }

    private static com.smplkit.errors.SmplError mapException(ApiException e) {
        if (e.getCode() == 0) return ApiExceptionHandler.mapApiException(e);
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }
}
