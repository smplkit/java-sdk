package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ForwardersApi;
import com.smplkit.internal.generated.audit.model.Forwarder;
import com.smplkit.internal.generated.audit.model.ForwarderDelivery;
import com.smplkit.internal.generated.audit.model.ForwarderDeliveryListResponse;
import com.smplkit.internal.generated.audit.model.ForwarderDeliveryResource;
import com.smplkit.internal.generated.audit.model.ForwarderDeliveryResponse;
import com.smplkit.internal.generated.audit.model.ForwarderHttp;
import com.smplkit.internal.generated.audit.model.ForwarderListResponse;
import com.smplkit.internal.generated.audit.model.ForwarderResource;
import com.smplkit.internal.generated.audit.model.ForwarderResponse;
import com.smplkit.internal.generated.audit.model.HttpHeader;
import com.smplkit.internal.generated.audit.model.RetryFailedDeliveriesSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SIEM streaming forwarders for the authenticated account.
 *
 * <p>Pro tier only — every method here returns a wrapped 402 from
 * the audit service on lower tiers.</p>
 */
public final class AuditForwarders {

    private final ForwardersApi api;

    AuditForwarders(ForwardersApi api) {
        this.api = api;
    }

    public com.smplkit.audit.Forwarder create(CreateForwarderInput input) throws ApiException {
        ForwarderResponse body = wrap(null, input);
        ForwarderResponse resp = api.createForwarder(body);
        return fromResource(resp.getData());
    }

    public ListForwardersPage list(ListForwardersInput input) throws ApiException {
        // The generated listForwarders takes the filter as a plain
        // String (it's a query-string param on the wire), so unwrap
        // the typed enum to its slug value.
        String filterType = input.forwarderType == null ? null : input.forwarderType.getValue();
        ForwarderListResponse resp = api.listForwarders(
                filterType, input.enabled, input.pageSize, input.pageAfter);
        List<com.smplkit.audit.Forwarder> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (ForwarderResource r : resp.getData()) out.add(fromResource(r));
        }
        return new ListForwardersPage(out, nextCursor(
                resp.getLinks() == null ? null : resp.getLinks().getNext()));
    }

    public com.smplkit.audit.Forwarder get(UUID forwarderId) throws ApiException {
        ForwarderResponse resp = api.getForwarder(forwarderId);
        return fromResource(resp.getData());
    }

    public com.smplkit.audit.Forwarder update(UUID forwarderId, CreateForwarderInput input)
            throws ApiException {
        ForwarderResponse body = wrap(forwarderId, input);
        ForwarderResponse resp = api.updateForwarder(forwarderId, body);
        return fromResource(resp.getData());
    }

    public void delete(UUID forwarderId) throws ApiException {
        api.deleteForwarder(forwarderId);
    }

    /** List delivery rows for a forwarder. */
    public ListDeliveriesPage listDeliveries(UUID forwarderId, ListDeliveriesInput input)
            throws ApiException {
        ForwarderDeliveryListResponse resp = api.listForwarderDeliveries(
                forwarderId, input.status, input.createdAtRange,
                input.pageSize, input.pageAfter);
        List<com.smplkit.audit.ForwarderDelivery> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (ForwarderDeliveryResource r : resp.getData()) out.add(deliveryFromResource(r));
        }
        return new ListDeliveriesPage(out, nextCursor(
                resp.getLinks() == null ? null : resp.getLinks().getNext()));
    }

    /** Retry a single failed delivery; returns the new attempt row. */
    public com.smplkit.audit.ForwarderDelivery retryDelivery(UUID forwarderId, UUID deliveryId)
            throws ApiException {
        ForwarderDeliveryResponse resp = api.retryForwarderDelivery(forwarderId, deliveryId);
        return deliveryFromResource(resp.getData());
    }

    /** Retry every failed delivery for a forwarder. */
    public com.smplkit.audit.RetryFailedDeliveriesSummary retryFailedDeliveries(UUID forwarderId)
            throws ApiException {
        RetryFailedDeliveriesSummary s = api.retryFailedForwarderDeliveries(forwarderId);
        return new com.smplkit.audit.RetryFailedDeliveriesSummary(
                s.getAttempted() != null ? s.getAttempted() : 0,
                s.getSucceeded() != null ? s.getSucceeded() : 0,
                s.getFailed() != null ? s.getFailed() : 0);
    }

    // ------------------------------------------------------------------
    // Wire <-> wrapper conversions
    // ------------------------------------------------------------------

    private static ForwarderResponse wrap(UUID id, CreateForwarderInput input) {
        Forwarder attrs = new Forwarder();
        attrs.setName(input.name);
        attrs.setForwarderType(toGenForwarderType(input.forwarderType));
        attrs.setEnabled(input.enabled);
        attrs.setHttp(toGenHttp(input.http));
        if (input.filter != null) attrs.setFilter(input.filter);
        if (input.transform != null) attrs.setTransform(input.transform);
        if (input.data != null) attrs.setData(input.data);
        ForwarderResource r = new ForwarderResource();
        r.setId(id != null ? id.toString() : "");
        r.setType("forwarder");
        r.setAttributes(attrs);
        ForwarderResponse body = new ForwarderResponse();
        body.setData(r);
        return body;
    }

    private static ForwarderHttp toGenHttp(com.smplkit.audit.ForwarderHttp src) {
        ForwarderHttp out = new ForwarderHttp();
        out.setMethod(src.method);
        out.setUrl(src.url);
        if (src.body != null) out.setBody(src.body);
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
        com.smplkit.audit.ForwarderHttp http = httpFromGen(a.getHttp());
        UUID id = (r.getId() != null && !r.getId().isEmpty()) ? UUID.fromString(r.getId()) : null;
        return new com.smplkit.audit.Forwarder(
                id,
                a.getName(),
                a.getSlug(),
                fromGenForwarderType(a.getForwarderType()),
                a.getEnabled() != null ? a.getEnabled() : true,
                a.getFilter(),
                a.getTransform(),
                http,
                a.getData(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getDeletedAt(),
                a.getVersion());
    }

    /** Convert the wrapper's public enum to the codegen's internal one. */
    private static com.smplkit.internal.generated.audit.model.ForwarderType toGenForwarderType(
            com.smplkit.audit.ForwarderType src) {
        if (src == null) return null;
        return com.smplkit.internal.generated.audit.model.ForwarderType.fromValue(src.getValue());
    }

    /** Convert the codegen's internal enum to the wrapper's public one. */
    private static com.smplkit.audit.ForwarderType fromGenForwarderType(
            com.smplkit.internal.generated.audit.model.ForwarderType src) {
        if (src == null) return null;
        return com.smplkit.audit.ForwarderType.fromValue(src.getValue());
    }

    private static com.smplkit.audit.ForwarderHttp httpFromGen(ForwarderHttp src) {
        com.smplkit.audit.ForwarderHttp out = new com.smplkit.audit.ForwarderHttp();
        if (src == null) return out;
        if (src.getMethod() != null) out.method = src.getMethod();
        out.url = src.getUrl() != null ? src.getUrl() : "";
        if (src.getBody() != null) out.body = src.getBody();
        if (src.getSuccessStatus() != null) out.successStatus = src.getSuccessStatus();
        out.headers = new ArrayList<>();
        if (src.getHeaders() != null) {
            for (HttpHeader h : src.getHeaders()) {
                out.headers.add(new com.smplkit.audit.HttpHeader(h.getName(), h.getValue()));
            }
        }
        return out;
    }

    private static com.smplkit.audit.ForwarderDelivery deliveryFromResource(
            ForwarderDeliveryResource r) {
        ForwarderDelivery a = r.getAttributes();
        UUID id = (r.getId() != null && !r.getId().isEmpty()) ? UUID.fromString(r.getId()) : null;
        return new com.smplkit.audit.ForwarderDelivery(
                id,
                a.getForwarderId(),
                a.getEventId(),
                a.getAttemptNumber(),
                a.getStatus() != null ? a.getStatus().getValue() : "",
                a.getRequest(),
                a.getResponseStatus(),
                a.getResponseBody(),
                a.getLatencyMs(),
                a.getError(),
                a.getCreatedAt());
    }

    private static String nextCursor(String link) {
        if (link == null) return null;
        int i = link.indexOf("page[after]=");
        if (i < 0) return null;
        String token = link.substring(i + "page[after]=".length());
        int amp = token.indexOf('&');
        return amp >= 0 ? token.substring(0, amp) : token;
    }
}
