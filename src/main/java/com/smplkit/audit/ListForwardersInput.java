package com.smplkit.audit;

/** Filters and pagination for {@link AuditForwarders#list(ListForwardersInput)}. */
public final class ListForwardersInput {
    public ForwarderType forwarderType; // nullable
    public Boolean enabled; // nullable
    public Integer pageSize; // nullable
    public String pageAfter; // nullable
}
