package com.smplkit.audit;

/**
 * Offset-pagination metadata returned with every offset-paginated list
 * response.
 *
 * <p>{@code page} and {@code size} echo the parameters that served the
 * response (their defaults when the caller omitted them). {@code total}
 * and {@code totalPages} are populated only when the request included
 * {@code metaTotal=true}; otherwise they are {@code null}.</p>
 */
public final class PageInfo {
    public final int page;
    public final int size;
    public final Integer total;
    public final Integer totalPages;

    public PageInfo(int page, int size, Integer total, Integer totalPages) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.totalPages = totalPages;
    }
}
