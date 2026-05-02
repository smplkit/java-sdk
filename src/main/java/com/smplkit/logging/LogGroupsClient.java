package com.smplkit.logging;

import com.smplkit.Helpers;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Management-plane client for log groups, exposed as {@code mgmt.log_groups}.
 *
 * <p>Separate namespace from {@link LoggersClient} (matches the Python SDK split).</p>
 */
public final class LogGroupsClient {

    private final LoggingClient inner;

    public LogGroupsClient(LoggingClient inner) {
        this.inner = inner;
    }

    /** Construct an unsaved {@link LogGroup}. Call {@link LogGroup#save()} to persist. */
    public LogGroup new_(String id) {
        return new LogGroup(inner, id, Helpers.keyToDisplayName(id),
                null, null, null, null, null);
    }

    /** Construct an unsaved {@link LogGroup} with explicit name and parent group. */
    public LogGroup new_(String id, String name, String parentGroup) {
        return new LogGroup(inner, id, name, null, parentGroup, null, null, null);
    }

    /** Get a log group by id. */
    public LogGroup get(String id) {
        try {
            LogGroupResponse response = inner.logGroupsApi.getLogGroup(id);
            return inner.logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /** List all log groups. */
    public List<LogGroup> list() {
        try {
            LogGroupListResponse response = inner.logGroupsApi.listLogGroups();
            List<LogGroup> result = new ArrayList<>();
            if (response.getData() != null) {
                for (LogGroupResource r : response.getData()) {
                    result.add(inner.logGroupResourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /** Delete a log group by id. */
    public void delete(String id) {
        try {
            inner.logGroupsApi.deleteLogGroup(id);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }
}
