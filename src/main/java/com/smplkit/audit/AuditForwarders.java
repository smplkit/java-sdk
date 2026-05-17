package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ForwardersApi;
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

    /**
     * Returns an unsaved {@link Forwarder} bound to this client. Mutate any
     * remaining fields ({@code filter}, {@code transform},
     * {@code description}, {@code enabled}, etc.) and call
     * {@link Forwarder#save()} to persist.
     *
     * <p>When {@link Forwarder#transform} is set after the {@code newForwarder}
     * call, also set {@link Forwarder#transformType} (currently only
     * {@link TransformType#JSONATA}); the SDK auto-fills it on {@code save}
     * if {@code transform} is non-{@code null} but {@code transformType} is.</p>
     *
     * @param name display name for the forwarder
     * @param forwarderType destination type
     * @param configuration destination request configuration
     */
    public Forwarder newForwarder(String name, ForwarderType forwarderType,
                                  com.smplkit.audit.HttpConfiguration configuration) {
        return new Forwarder(this, name, forwarderType, configuration);
    }

    /** List forwarders for the authenticated account (default filters and page size). */
    public ListForwardersPage list() throws ApiException {
        return list(new ListForwardersInput());
    }

    /** List forwarders for the authenticated account. */
    public ListForwardersPage list(ListForwardersInput input) throws ApiException {
        String filterType = input.forwarderType == null ? null : input.forwarderType.getValue();
        ForwarderListResponse resp = api.listForwarders(
                filterType, input.enabled, null, input.pageNumber, input.pageSize, input.metaTotal);
        List<Forwarder> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (ForwarderResource r : resp.getData()) out.add(fromResource(r));
        }
        return new ListForwardersPage(out, AuditResourceTypesClient.extractPagination(
                resp.getMeta() == null ? null : resp.getMeta().getPagination()));
    }

    /**
     * Fetch a single forwarder by id; the returned instance is bound to this
     * client so {@code forwarder.save()} and {@code forwarder.delete()} work.
     */
    public Forwarder get(UUID forwarderId) throws ApiException {
        ForwarderResponse resp = api.getForwarder(forwarderId);
        return fromResource(resp.getData());
    }

    /** Soft-delete a forwarder by id. */
    public void delete(UUID forwarderId) throws ApiException {
        api.deleteForwarder(forwarderId);
    }

    // ------------------------------------------------------------------
    // Active-record helpers (called by Forwarder.save)
    // ------------------------------------------------------------------

    Forwarder create(Forwarder forwarder) throws ApiException {
        ForwarderResponse resp = api.createForwarder(wrapRequest(null, forwarder));
        return fromResource(resp.getData());
    }

    Forwarder update(Forwarder forwarder) throws ApiException {
        if (forwarder.id == null) {
            throw new IllegalStateException("cannot update a Forwarder with no id");
        }
        ForwarderResponse resp = api.updateForwarder(forwarder.id, wrapRequest(forwarder.id, forwarder));
        return fromResource(resp.getData());
    }

    // ------------------------------------------------------------------
    // Wire <-> wrapper conversions
    // ------------------------------------------------------------------

    private static ForwarderRequest wrapRequest(UUID id, Forwarder forwarder) {
        com.smplkit.internal.generated.audit.model.Forwarder attrs =
                new com.smplkit.internal.generated.audit.model.Forwarder();
        attrs.setName(forwarder.name);
        if (forwarder.description != null) attrs.setDescription(forwarder.description);
        attrs.setForwarderType(toGenForwarderType(forwarder.forwarderType));
        attrs.setEnabled(forwarder.enabled);
        attrs.setConfiguration(toGenConfiguration(forwarder.configuration));
        if (forwarder.filter != null) attrs.setFilter(forwarder.filter);
        TransformType tt = forwarder.transformType;
        if (tt == null && forwarder.transform != null) tt = TransformType.JSONATA;
        if (tt != null) {
            attrs.setTransformType(com.smplkit.internal.generated.audit.model.Forwarder.TransformTypeEnum
                    .fromValue(tt.getValue()));
        }
        if (forwarder.transform != null) attrs.setTransform(forwarder.transform);
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
            out.setMethod(HttpConfiguration.MethodEnum.fromValue(src.method.getValue()));
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

    private Forwarder fromResource(ForwarderResource r) {
        com.smplkit.internal.generated.audit.model.Forwarder a = r.getAttributes();
        com.smplkit.audit.HttpConfiguration cfg = configurationFromGen(a.getConfiguration());
        UUID id = (r.getId() != null && !r.getId().isEmpty()) ? UUID.fromString(r.getId()) : null;
        com.smplkit.internal.generated.audit.model.Forwarder.TransformTypeEnum tt = a.getTransformType();
        Object rawTransform = a.getTransform();
        return new Forwarder(
                this,
                id,
                a.getName(),
                a.getDescription(),
                fromGenForwarderType(a.getForwarderType()),
                a.getEnabled() != null ? a.getEnabled() : true,
                a.getFilter(),
                tt != null ? TransformType.fromValue(tt.getValue()) : null,
                rawTransform != null ? rawTransform.toString() : null,
                cfg,
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getDeletedAt(),
                a.getVersion());
    }

    private static com.smplkit.internal.generated.audit.model.ForwarderType toGenForwarderType(
            ForwarderType src) {
        if (src == null) return null;
        return com.smplkit.internal.generated.audit.model.ForwarderType.fromValue(src.getValue());
    }

    private static ForwarderType fromGenForwarderType(
            com.smplkit.internal.generated.audit.model.ForwarderType src) {
        if (src == null) return null;
        return ForwarderType.fromValue(src.getValue());
    }

    private static com.smplkit.audit.HttpConfiguration configurationFromGen(HttpConfiguration src) {
        com.smplkit.audit.HttpConfiguration out = new com.smplkit.audit.HttpConfiguration();
        if (src == null) return out;
        if (src.getMethod() != null) out.method = HttpMethod.fromValue(src.getMethod().getValue());
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
