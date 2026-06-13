package com.smplkit.logging;

import com.smplkit.Helpers;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LogGroupsApi;
import com.smplkit.internal.generated.logging.model.LogGroupCreateRequest;
import com.smplkit.internal.generated.logging.model.LogGroupCreateResource;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupRequest;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Surface for {@code client.logging.logGroups.*}.
 */
public final class LogGroupsClient {

    private final LogGroupsApi logGroupsApi;

    LogGroupsClient(LogGroupsApi logGroupsApi) {
        this.logGroupsApi = logGroupsApi;
    }

    /**
     * Build a new unsaved {@link LogGroup}. Call {@link LogGroup#save} to persist.
     *
     * <p>The display name defaults to a title-cased version of {@code id}, and
     * the group is created as top-level. Use {@link #new_(String, String, String)}
     * to set an explicit name or nest the group under a parent.</p>
     *
     * @param id the identifier for the log group
     * @return an unsaved {@link LogGroup} bound to this client
     */
    public LogGroup new_(String id) {
        return new LogGroup(this, id, Helpers.keyToDisplayName(id), null, null, null, null, null);
    }

    /**
     * Build a new unsaved {@link LogGroup} with an explicit name and parent group.
     * Call {@link LogGroup#save} to persist.
     *
     * @param id    the identifier for the log group
     * @param name  the human-readable display name; when {@code null}, defaults
     *     to a title-cased version of {@code id}
     * @param group the identifier of the parent log group when nesting groups;
     *     {@code null} for a top-level group
     * @return an unsaved {@link LogGroup} bound to this client
     */
    public LogGroup new_(String id, String name, String group) {
        return new LogGroup(this, id, name != null ? name : Helpers.keyToDisplayName(id),
                null, group, null, null, null);
    }

    /**
     * List log groups for the authenticated account using server-default pagination.
     *
     * @return the log groups on the first page
     */
    public List<LogGroup> list() {
        return list(null, null);
    }

    /**
     * List log groups for the authenticated account.
     *
     * @param pageNumber the 1-based page index to fetch; when {@code null}, the
     *     server returns the first page
     * @param pageSize   the maximum number of log groups per page; when
     *     {@code null}, the server applies its default page size
     * @return the log groups on the requested page
     */
    public List<LogGroup> list(Integer pageNumber, Integer pageSize) {
        try {
            LogGroupListResponse response = logGroupsApi.listLogGroups(null, pageNumber, pageSize, null);
            List<LogGroup> result = new ArrayList<>();
            if (response.getData() != null) {
                for (LogGroupResource r : response.getData()) {
                    result.add(resourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * Fetch a single log group by id.
     *
     * @param id the identifier of the log group to fetch
     * @return the editable {@link LogGroup} resource
     * @throws com.smplkit.errors.NotFoundError if no log group with that id exists
     */
    public LogGroup get(String id) {
        try {
            LogGroupResponse response = logGroupsApi.getLogGroup(id);
            return resourceToModel(response.getData());
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * Delete a log group by id.
     *
     * @param id the identifier of the log group to delete
     */
    public void delete(String id) {
        try {
            logGroupsApi.deleteLogGroup(id);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Internal: save (called by LogGroup.save())
    // -----------------------------------------------------------------------

    /**
     * Save a group on the server. POSTs when {@code createdAt} is null (new group),
     * else PUTs. Called by {@link LogGroup#save()}.
     */
    LogGroup saveGroup(LogGroup grp) {
        try {
            LogGroupResponse response;
            if (grp.getCreatedAt() == null) {
                response = logGroupsApi.createLogGroup(buildCreateGroupBody(grp));
            } else {
                response = logGroupsApi.updateLogGroup(grp.getId(), buildGroupBody(grp));
            }
            return resourceToModel(response.getData());
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    private LogGroupRequest buildGroupBody(LogGroup grp) {
        var attrs = buildGroupAttrs(grp);

        LogGroupResource data = new LogGroupResource();
        data.setType(LogGroupResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);
        data.setId(grp.getId());

        LogGroupRequest body = new LogGroupRequest();
        body.setData(data);
        return body;
    }

    private LogGroupCreateRequest buildCreateGroupBody(LogGroup grp) {
        var attrs = buildGroupAttrs(grp);

        LogGroupCreateResource data = new LogGroupCreateResource();
        data.setType(LogGroupCreateResource.TypeEnum.LOG_GROUP);
        data.setAttributes(attrs);
        // Create requires a client-supplied id. The server rejects a missing /
        // empty id with 422; the LogGroup model exposes id as caller-required
        // for new groups, since the id is the group's stable identifier.
        data.setId(grp.getId());

        LogGroupCreateRequest body = new LogGroupCreateRequest();
        body.setData(data);
        return body;
    }

    private com.smplkit.internal.generated.logging.model.LogGroup buildGroupAttrs(LogGroup grp) {
        var attrs = new com.smplkit.internal.generated.logging.model.LogGroup();
        attrs.setName(grp.getName());
        if (grp.getLevel() != null) {
            attrs.setLevel(com.smplkit.internal.generated.logging.model.LogLevel.fromValue(grp.getLevel()));
        }
        if (grp.getGroup() != null) {
            attrs.setParentId(grp.getGroup());
        }
        // Always include environments — see LoggersClient.buildLoggerBody for rationale.
        attrs.setEnvironments(new HashMap<>(
                grp.getEnvironments() != null ? grp.getEnvironments() : new HashMap<>()));
        return attrs;
    }

    LogGroup resourceToModel(LogGroupResource resource) {
        var attrs = resource.getAttributes();
        return new LogGroup(
                this,
                resource.getId() != null ? resource.getId() : null,
                attrs.getName(),
                attrs.getLevel() != null ? attrs.getLevel().getValue() : null,
                attrs.getParentId(),
                attrs.getEnvironments() != null ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>(),
                toInstant(attrs.getCreatedAt()),
                toInstant(attrs.getUpdatedAt()));
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
