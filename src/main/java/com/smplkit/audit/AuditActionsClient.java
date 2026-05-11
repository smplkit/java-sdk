package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ActionsApi;
import com.smplkit.internal.generated.audit.model.ActionListResponse;
import com.smplkit.internal.generated.audit.model.ActionResource;

import java.util.ArrayList;
import java.util.List;

/**
 * Distinct action slugs seen for the account — accessed via
 * {@link AuditClient#actions()}.
 *
 * <p>Without {@link ListActionsInput#filterResourceType}, returns one
 * row per distinct action. With the filter, returns the actions seen
 * with that specific resource_type, powering cascading-filter behavior.
 * ADR-047 §2.5. Sorted alphabetically; cursor pagination via
 * {@link ListActionsInput#pageAfter}.</p>
 */
public final class AuditActionsClient {

    private final ActionsApi api;

    AuditActionsClient(ActionsApi api) {
        this.api = api;
    }

    /** List the distinct action slugs seen in the account. */
    public ListActionsPage list(ListActionsInput input) throws ApiException {
        ActionListResponse resp = api.listActions(
                input.filterResourceType, input.pageSize, input.pageAfter);
        List<AuditAction> rows = new ArrayList<>();
        if (resp.getData() != null) {
            for (ActionResource r : resp.getData()) {
                rows.add(fromResource(r));
            }
        }
        String nextCursor = null;
        if (resp.getLinks() != null && resp.getLinks().getNext() != null) {
            nextCursor = extractCursor(resp.getLinks().getNext());
        }
        return new ListActionsPage(rows, nextCursor);
    }

    private static AuditAction fromResource(ActionResource r) {
        String id = r.getId() != null ? r.getId() : "";
        String action = (r.getAttributes() != null && r.getAttributes().getAction() != null)
                ? r.getAttributes().getAction() : id;
        java.time.OffsetDateTime createdAt = r.getAttributes() != null
                ? r.getAttributes().getCreatedAt() : null;
        return new AuditAction(id, action, createdAt);
    }

    private static String extractCursor(String link) {
        int i = link.indexOf("page[after]=");
        if (i < 0) return null;
        String token = link.substring(i + "page[after]=".length());
        int amp = token.indexOf('&');
        return amp >= 0 ? token.substring(0, amp) : token;
    }
}
