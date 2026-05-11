package com.smplkit.audit;

/** Input for {@link AuditActionsClient#list(ListActionsInput)}. */
public final class ListActionsInput {
    /** Filter to actions seen with a specific resource_type. */
    public String filterResourceType;
    public Integer pageSize;
    public String pageAfter;
}
