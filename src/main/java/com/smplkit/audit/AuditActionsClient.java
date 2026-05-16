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
 * ADR-047 §2.5. Sorted alphabetically; offset pagination via
 * {@link ListActionsInput#pageNumber} / {@link
 * ListActionsInput#pageSize}.</p>
 */
public final class AuditActionsClient {

    private final ActionsApi api;

    AuditActionsClient(ActionsApi api) {
        this.api = api;
    }

    /** List the distinct action slugs seen in the account. */
    public ListActionsPage list(ListActionsInput input) throws ApiException {
        ActionListResponse resp = api.listActions(
                input.filterResourceType, null, input.pageNumber, input.pageSize, input.metaTotal);
        List<AuditAction> rows = new ArrayList<>();
        if (resp.getData() != null) {
            for (ActionResource r : resp.getData()) {
                rows.add(fromResource(r));
            }
        }
        return new ListActionsPage(rows, AuditResourceTypesClient.extractPagination(
                resp.getMeta() == null ? null : resp.getMeta().getPagination()));
    }

    private static AuditAction fromResource(ActionResource r) {
        String id = r.getId() != null ? r.getId() : "";
        String action = (r.getAttributes() != null && r.getAttributes().getAction() != null)
                ? r.getAttributes().getAction() : id;
        java.time.OffsetDateTime createdAt = r.getAttributes() != null
                ? r.getAttributes().getCreatedAt() : null;
        return new AuditAction(id, action, createdAt);
    }
}
