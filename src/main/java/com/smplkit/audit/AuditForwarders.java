package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ForwardersApi;
import com.smplkit.internal.generated.audit.model.ForwarderCreateRequest;
import com.smplkit.internal.generated.audit.model.ForwarderCreateResource;
import com.smplkit.internal.generated.audit.model.ForwarderListResponse;
import com.smplkit.internal.generated.audit.model.ForwarderRequest;
import com.smplkit.internal.generated.audit.model.ForwarderResource;
import com.smplkit.internal.generated.audit.model.ForwarderResponse;
import com.smplkit.internal.generated.audit.model.HttpConfiguration;
import com.smplkit.internal.generated.audit.model.HttpHeader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * <p>If you also want to set a {@code transform} now, prefer the
     * six-arg overload — {@link Forwarder#save()} rejects a forwarder
     * with {@code transform} set but {@code transformType} unset.</p>
     *
     * @param id stable caller-supplied key for the forwarder
     * @param name display name for the forwarder
     * @param forwarderType destination type
     * @param configuration destination request configuration
     */
    public Forwarder newForwarder(String id, String name, ForwarderType forwarderType,
                                  com.smplkit.audit.HttpConfiguration configuration) {
        return new Forwarder(this, id, name, forwarderType, configuration);
    }

    /**
     * Returns an unsaved {@link Forwarder} bound to this client, with a
     * transform pre-configured.
     *
     * <p>{@code transform} is accepted as {@code Object} so future template
     * engines can carry structured templates; for the only currently
     * supported engine ({@link TransformType#JSONATA}), pass a {@code String}
     * containing the JSONata expression.</p>
     *
     * @param id stable caller-supplied key for the forwarder
     * @param name display name for the forwarder
     * @param forwarderType destination type
     * @param configuration destination request configuration
     * @param transformType template engine — must be paired with {@code transform}
     * @param transform template applied to each event before delivery; may be {@code null}
     *     (in which case {@code transformType} must also be {@code null})
     * @throws IllegalArgumentException if exactly one of {@code transform} /
     *     {@code transformType} is set, or if {@code transformType} is
     *     {@link TransformType#JSONATA} and {@code transform} is not a {@code String}
     */
    public Forwarder newForwarder(String id, String name, ForwarderType forwarderType,
                                  com.smplkit.audit.HttpConfiguration configuration,
                                  TransformType transformType, Object transform) {
        Forwarder.validateTransform(transformType, transform);
        Forwarder fwd = new Forwarder(this, id, name, forwarderType, configuration);
        fwd.transformType = transformType;
        fwd.transform = transform;
        return fwd;
    }

    /** List forwarders for the authenticated account (default filters and page size). */
    public ListForwardersPage list() throws ApiException {
        return list(new ListForwardersInput());
    }

    /** List forwarders for the authenticated account. */
    public ListForwardersPage list(ListForwardersInput input) throws ApiException {
        String filterType = input.forwarderType == null ? null : input.forwarderType.getValue();
        ForwarderListResponse resp = api.listForwarders(
                filterType, null, input.pageNumber, input.pageSize, input.metaTotal);
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
    public Forwarder get(String forwarderId) throws ApiException {
        ForwarderResponse resp = api.getForwarder(forwarderId);
        return fromResource(resp.getData());
    }

    /** Soft-delete a forwarder by id. */
    public void delete(String forwarderId) throws ApiException {
        api.deleteForwarder(forwarderId);
    }

    // ------------------------------------------------------------------
    // Active-record helpers (called by Forwarder.save)
    // ------------------------------------------------------------------

    Forwarder create(Forwarder forwarder) throws ApiException {
        if (forwarder.id == null) {
            throw new IllegalStateException("cannot create a Forwarder with no id (caller must supply a stable key)");
        }
        ForwarderResponse resp = api.createForwarder(wrapCreateRequest(forwarder));
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

    private static com.smplkit.internal.generated.audit.model.Forwarder buildAttrs(Forwarder forwarder) {
        com.smplkit.internal.generated.audit.model.Forwarder attrs =
                new com.smplkit.internal.generated.audit.model.Forwarder();
        attrs.setName(forwarder.name);
        if (forwarder.description != null) attrs.setDescription(forwarder.description);
        attrs.setForwarderType(toGenForwarderType(forwarder.forwarderType));
        // The base ``enabled`` is server-pinned false (ADR-055); it's read-only,
        // so we never send it. Enablement travels entirely through ``environments``.
        attrs.setConfiguration(toGenConfiguration(forwarder.configuration));
        if (forwarder.environments != null && !forwarder.environments.isEmpty()) {
            attrs.setEnvironments(environmentsToGen(forwarder.environments));
        }
        if (forwarder.filter != null) attrs.setFilter(forwarder.filter);
        if (forwarder.transformType != null) {
            attrs.setTransformType(com.smplkit.internal.generated.audit.model.Forwarder.TransformTypeEnum
                    .fromValue(forwarder.transformType.getValue()));
        }
        if (forwarder.transform != null) attrs.setTransform(forwarder.transform);
        attrs.setForwardSmplkitEvents(forwarder.forwardSmplkitEvents);
        return attrs;
    }

    private static ForwarderRequest wrapRequest(String id, Forwarder forwarder) {
        ForwarderResource r = new ForwarderResource();
        r.setId(id != null ? id : "");
        r.setType("forwarder");
        r.setAttributes(buildAttrs(forwarder));
        ForwarderRequest body = new ForwarderRequest();
        body.setData(r);
        return body;
    }

    private static ForwarderCreateRequest wrapCreateRequest(Forwarder forwarder) {
        // Create uses a dedicated envelope where the caller-supplied id is required.
        ForwarderCreateResource r = new ForwarderCreateResource();
        r.setId(forwarder.id);
        r.setType(ForwarderCreateResource.TypeEnum.FORWARDER);
        r.setAttributes(buildAttrs(forwarder));
        ForwarderCreateRequest body = new ForwarderCreateRequest();
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
        out.setTlsVerify(src.tlsVerify);
        out.setCaCert(src.caCert);
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
        String id = (r.getId() != null && !r.getId().isEmpty()) ? r.getId() : null;
        com.smplkit.internal.generated.audit.model.Forwarder.TransformTypeEnum tt = a.getTransformType();
        return new Forwarder(
                this,
                id,
                a.getName(),
                a.getDescription(),
                fromGenForwarderType(a.getForwarderType()),
                // The base ``enabled`` is server-pinned false; round-trip whatever
                // the server returned (always false) without assuming a default of true.
                a.getEnabled() != null ? a.getEnabled() : false,
                environmentsFromGen(a.getEnvironments()),
                a.getFilter(),
                tt != null ? TransformType.fromValue(tt.getValue()) : null,
                a.getTransform(),
                // Absent on the wire means a forwarder persisted before the
                // field landed — default to false (no platform events).
                a.getForwardSmplkitEvents() != null ? a.getForwardSmplkitEvents() : false,
                cfg,
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getDeletedAt(),
                a.getVersion());
    }

    /**
     * Convert the wrapper {@code environments} map to the generated model.
     * Per-environment {@code configuration} overrides are sent as full
     * {@link com.smplkit.audit.HttpConfiguration} payloads (plaintext headers
     * in), mirroring the base configuration's round-trip semantics.
     */
    private static Map<String, com.smplkit.internal.generated.audit.model.ForwarderEnvironment>
            environmentsToGen(Map<String, ForwarderEnvironment> environments) {
        Map<String, com.smplkit.internal.generated.audit.model.ForwarderEnvironment> out = new HashMap<>();
        for (Map.Entry<String, ForwarderEnvironment> e : environments.entrySet()) {
            ForwarderEnvironment env = e.getValue();
            com.smplkit.internal.generated.audit.model.ForwarderEnvironment gen =
                    new com.smplkit.internal.generated.audit.model.ForwarderEnvironment();
            gen.setEnabled(env.enabled);
            if (env.configuration != null) {
                gen.setConfiguration(toGenConfiguration(env.configuration));
            }
            out.put(e.getKey(), gen);
        }
        return out;
    }

    /** Convert the generated {@code environments} map to wrapper instances. */
    private static Map<String, ForwarderEnvironment> environmentsFromGen(
            Map<String, com.smplkit.internal.generated.audit.model.ForwarderEnvironment> environments) {
        Map<String, ForwarderEnvironment> out = new HashMap<>();
        if (environments == null) return out;
        for (Map.Entry<String, com.smplkit.internal.generated.audit.model.ForwarderEnvironment> e
                : environments.entrySet()) {
            com.smplkit.internal.generated.audit.model.ForwarderEnvironment gen = e.getValue();
            ForwarderEnvironment env = new ForwarderEnvironment();
            if (gen != null) {
                env.enabled = gen.getEnabled() != null ? gen.getEnabled() : false;
                env.configuration = gen.getConfiguration() != null
                        ? configurationFromGen(gen.getConfiguration()) : null;
            }
            out.put(e.getKey(), env);
        }
        return out;
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
        // Absent ``tls_verify`` on the wire means a forwarder persisted
        // before the field landed — default to verifying so its prior
        // secure behaviour is preserved.
        out.tlsVerify = src.getTlsVerify() == null ? true : src.getTlsVerify();
        out.caCert = src.getCaCert();
        out.headers = new ArrayList<>();
        if (src.getHeaders() != null) {
            for (HttpHeader h : src.getHeaders()) {
                out.headers.add(new com.smplkit.audit.HttpHeader(h.getName(), h.getValue()));
            }
        }
        return out;
    }

}
