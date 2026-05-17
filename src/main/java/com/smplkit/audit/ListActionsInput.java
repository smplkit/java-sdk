package com.smplkit.audit;

/** Input for {@link AuditActionsClient#list(ListActionsInput)}. */
public final class ListActionsInput {
    /** Filter to actions seen with a specific {@code resourceType}. */
    public String filterResourceType;
    /** 1-based page number to return. Defaults to 1 server-side when null. */
    public Integer pageNumber;
    /** Items per page (1–1000). Defaults to 1000 server-side when null. */
    public Integer pageSize;
    /** When true, the server populates {@code total} and {@code totalPages} on the response. */
    public Boolean metaTotal;
}
