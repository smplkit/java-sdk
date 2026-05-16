package com.smplkit.audit;

/** Filters and pagination for {@link AuditForwarders#list(ListForwardersInput)}. */
public final class ListForwardersInput {
    public ForwarderType forwarderType; // nullable
    public Boolean enabled; // nullable
    /** 1-based page number to return. Defaults to 1 server-side when null. */
    public Integer pageNumber;
    /** Items per page (1–1000). Defaults to 1000 server-side when null. */
    public Integer pageSize;
    /** When true, the server populates {@code total} and {@code totalPages} on the response. */
    public Boolean metaTotal;
}
