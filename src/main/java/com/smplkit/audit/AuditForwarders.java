package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ForwardersApi;
import com.smplkit.internal.generated.audit.model.Forwarder;
import com.smplkit.internal.generated.audit.model.ForwarderListResponse;
import com.smplkit.internal.generated.audit.model.ForwarderRequest;
import com.smplkit.internal.generated.audit.model.ForwarderResource;
import com.smplkit.internal.generated.audit.model.ForwarderResponse;
import com.smplkit.internal.generated.audit.model.HttpConfiguration;
import com.smplkit.internal.generated.audit.model.HttpHeader;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SIEM streaming forwarder CRUD for the authenticated account.
 *
 * <p>Accessed via {@code SmplManagementClient.audit.forwarders}.</p>
 */
public final class AuditForwarders {

    private final ForwardersApi api;

    AuditForwarders(ForwardersApi api) {
        this.api = api;
    }

    public com.smplkit.audit.Forwarder create(CreateForwarderInput input) throws ApiException {
        ForwarderResponse resp = api.createForwarder(wrapRequest(null, input));
        return fromResource(resp.getData());
    }

    public ListForwardersPage list(ListForwardersInput input) throws ApiException {
        String filterType = input.forwarderType == null ? null : input.forwarderType.getValue();
        ForwarderListResponse resp = api.listForwarders(
                filterType, input.enabled, null, input.pageNumber, input.pageSize, input.metaTotal);
        List<com.smplkit.audit.Forwarder> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (ForwarderResource r : resp.getData()) out.add(fromResource(r));
        }
        return new ListForwardersPage(out, AuditResourceTypesClient.extractPagination(
                resp.getMeta() == null ? null : resp.getMeta().getPagination()));
    }

    public com.smplkit.audit.Forwarder get(UUID forwarderId) throws ApiException {
        ForwarderResponse resp = api.getForwarder(forwarderId);
        return fromResource(resp.getData());
    }

    public com.smplkit.audit.Forwarder update(UUID forwarderId, CreateForwarderInput input)
            throws ApiException {
        ForwarderResponse resp = api.updateForwarder(forwarderId, wrapRequest(forwarderId, input));
        return fromResource(resp.getData());
    }

    public void delete(UUID forwarderId) throws ApiException {
        api.deleteForwarder(forwarderId);
    }

    // ------------------------------------------------------------------
    // Wire <-> wrapper conversions
    // ------------------------------------------------------------------

    private static ForwarderRequest wrapRequest(UUID id, CreateForwarderInput input) {
        Forwarder attrs = new Forwarder();
        attrs.setName(input.name);
        if (input.description != null) attrs.setDescription(input.description);
        attrs.setForwarderType(toGenForwarderType(input.forwarderType));
        attrs.setEnabled(input.enabled);
        attrs.setConfiguration(toGenConfiguration(input.configuration));
        if (input.filter != null) attrs.setFilter(input.filter);
        if (input.transformType != null) {
            attrs.setTransformType(Forwarder.TransformTypeEnum.fromValue(input.transformType));
        }
        if (input.transform != null) attrs.setTransform(input.transform);
        ForwarderResource r = new ForwarderResource();
        r.setId(id != null ? id.toString() : "");
        r.setType("forwarder");
        r.setAttributes(attrs);
        ForwarderRequest body = new ForwarderRequest();
        body.setData(r);
        return body;
    }

    private static HttpConfiguration toGenConfiguration(com.smplkit.audit.HttpConfiguration src) {
        HttpConfiguration out = new HttpConfiguration();
        if (src.method != null) {
            out.setMethod(HttpConfiguration.MethodEnum.fromValue(src.method));
        }
        out.setUrl(src.url);
        if (src.successStatus != null) out.setSuccessStatus(src.successStatus);
        if (src.headers != null) {
            List<HttpHeader> hh = new ArrayList<>();
            for (com.smplkit.audit.HttpHeader h : src.headers) {
                HttpHeader g = new HttpHeader();
                g.setName(h.name);
                g.setValue(h.value);
                hh.add(g);
            }
            out.setHeaders(hh);
        }
        return out;
    }

    private static com.smplkit.audit.Forwarder fromResource(ForwarderResource r) {
        Forwarder a = r.getAttributes();
        com.smplkit.audit.HttpConfiguration cfg = configurationFromGen(a.getConfiguration());
        UUID id = (r.getId() != null && !r.getId().isEmpty()) ? UUID.fromString(r.getId()) : null;
        Forwarder.TransformTypeEnum tt = a.getTransformType();
        return new com.smplkit.audit.Forwarder(
                id,
                a.getName(),
                a.getDescription(),
                fromGenForwarderType(a.getForwarderType()),
                a.getEnabled() != null ? a.getEnabled() : true,
                a.getFilter(),
                tt != null ? tt.getValue() : null,
                a.getTransform(),
                cfg,
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getDeletedAt(),
                a.getVersion());
    }

    private static com.smplkit.internal.generated.audit.model.ForwarderType toGenForwarderType(
            com.smplkit.audit.ForwarderType src) {
        if (src == null) return null;
        return com.smplkit.internal.generated.audit.model.ForwarderType.fromValue(src.getValue());
    }

    private static com.smplkit.audit.ForwarderType fromGenForwarderType(
            com.smplkit.internal.generated.audit.model.ForwarderType src) {
        if (src == null) return null;
        return com.smplkit.audit.ForwarderType.fromValue(src.getValue());
    }

    private static com.smplkit.audit.HttpConfiguration configurationFromGen(HttpConfiguration src) {
        com.smplkit.audit.HttpConfiguration out = new com.smplkit.audit.HttpConfiguration();
        if (src == null) return out;
        if (src.getMethod() != null) out.method = src.getMethod().getValue();
        out.url = src.getUrl() != null ? src.getUrl() : "";
        if (src.getSuccessStatus() != null) out.successStatus = src.getSuccessStatus();
        out.headers = new ArrayList<>();
        if (src.getHeaders() != null) {
            for (HttpHeader h : src.getHeaders()) {
                out.headers.add(new com.smplkit.audit.HttpHeader(h.getName(), h.getValue()));
            }
        }
        return out;
    }

}
