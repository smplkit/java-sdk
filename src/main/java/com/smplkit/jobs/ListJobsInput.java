package com.smplkit.jobs;

/** Filters and pagination for {@link JobsManagementClient#list(ListJobsInput)}. */
public final class ListJobsInput {
    /** Filter to jobs matching this enabled state. {@code null} means no filter. */
    public Boolean enabled;
    /** 1-based page number to return. Defaults to 1 server-side when null. */
    public Integer pageNumber;
    /** Items per page (1–1000). Defaults to 1000 server-side when null. */
    public Integer pageSize;
}
