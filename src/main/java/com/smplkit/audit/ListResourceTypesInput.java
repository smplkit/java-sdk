package com.smplkit.audit;

import java.util.List;

/** Input for {@link AuditResourceTypesClient#list(ListResourceTypesInput)}. */
public final class ListResourceTypesInput {
    /**
     * Environment keys to scope the results to. When non-empty, the keys are
     * sent as a comma-separated {@code filter[environment]} query parameter.
     * {@code null} or empty leaves the filter unset (prior behavior).
     */
    public List<String> environments;
    /** 1-based page number to return. Defaults to 1 server-side when null. */
    public Integer pageNumber;
    /** Items per page (1–1000). Defaults to 1000 server-side when null. */
    public Integer pageSize;
    /** When true, the server populates {@code total} and {@code totalPages} on the response. */
    public Boolean metaTotal;
}
