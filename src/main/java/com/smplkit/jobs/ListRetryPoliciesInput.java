package com.smplkit.jobs;

/** Filters and pagination for {@link RetryPoliciesClient#list(ListRetryPoliciesInput)}. */
public final class ListRetryPoliciesInput {
    /**
     * Filter to policies whose name contains this text (case-insensitive),
     * sent as {@code filter[name]}. {@code null} lists all.
     */
    public String name;
    /** 1-based page number to return, sent as {@code page[number]}. {@code null} returns the first page. */
    public Integer pageNumber;
    /** Items per page, sent as {@code page[size]}. {@code null} uses the server default. */
    public Integer pageSize;
}
