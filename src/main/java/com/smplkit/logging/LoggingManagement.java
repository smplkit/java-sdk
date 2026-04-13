package com.smplkit.logging;

import com.smplkit.Helpers;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.model.LogGroupListResponse;
import com.smplkit.internal.generated.logging.model.LogGroupResource;
import com.smplkit.internal.generated.logging.model.LogGroupResponse;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Management-plane API for loggers and log groups: CRUD operations and factory methods.
 *
 * <p>Obtain an instance via {@link LoggingClient#management()}.</p>
 */
public final class LoggingManagement {

    private final LoggingClient client;

    LoggingManagement(LoggingClient client) {
        this.client = client;
    }

    // -----------------------------------------------------------------------
    // Logger factory methods
    // -----------------------------------------------------------------------

    /**
     * Create an unsaved Logger with the given id. Call {@link Logger#save()} to persist.
     *
     * @param id the logger id
     * @return an unsaved Logger instance
     */
    public Logger new_(String id) {
        return new Logger(client, id, Helpers.keyToDisplayName(id),
                null, null, false, null, null, null, null);
    }

    /**
     * Create an unsaved Logger with the given id, name, and managed flag.
     *
     * @param id      the logger id
     * @param name    the display name
     * @param managed whether this logger is managed by smplkit
     * @return an unsaved Logger instance
     */
    public Logger new_(String id, String name, boolean managed) {
        return new Logger(client, id, name,
                null, null, managed, null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Logger CRUD
    // -----------------------------------------------------------------------

    /**
     * Get a logger by id.
     *
     * @param id the logger id
     * @return the Logger
     * @throws com.smplkit.errors.SmplNotFoundException if no logger with the given id exists
     */
    public Logger get(String id) {
        try {
            LoggerResponse response = client.loggersApi.getLogger(id);
            return client.loggerResponseToModel(response);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * List all loggers.
     *
     * @return list of all loggers
     */
    public List<Logger> list() {
        try {
            LoggerListResponse response = client.loggersApi.listLoggers(null, null, null);
            List<Logger> result = new ArrayList<>();
            if (response.getData() != null) {
                for (LoggerResource r : response.getData()) {
                    result.add(client.loggerResourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * Delete a logger by id.
     *
     * @param id the logger id to delete
     */
    public void delete(String id) {
        try {
            client.loggersApi.deleteLogger(id);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    // -----------------------------------------------------------------------
    // LogGroup factory methods
    // -----------------------------------------------------------------------

    /**
     * Create an unsaved LogGroup with the given id. Call {@link LogGroup#save()} to persist.
     *
     * @param id the group id
     * @return an unsaved LogGroup instance
     */
    public LogGroup newGroup(String id) {
        return new LogGroup(client, id, Helpers.keyToDisplayName(id),
                null, null, null, null, null);
    }

    /**
     * Create an unsaved LogGroup with the given id, name, and parent group.
     *
     * @param id          the group id
     * @param name        the display name
     * @param parentGroup the parent group slug or null
     * @return an unsaved LogGroup instance
     */
    public LogGroup newGroup(String id, String name, String parentGroup) {
        return new LogGroup(client, id, name,
                null, parentGroup, null, null, null);
    }

    // -----------------------------------------------------------------------
    // LogGroup CRUD
    // -----------------------------------------------------------------------

    /**
     * Get a log group by id.
     *
     * @param id the group id
     * @return the LogGroup
     * @throws com.smplkit.errors.SmplNotFoundException if no group with the given id exists
     */
    public LogGroup getGroup(String id) {
        try {
            LogGroupResponse response = client.logGroupsApi.getLogGroup(id);
            return client.logGroupResponseToModel(response);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * List all log groups.
     *
     * @return list of all log groups
     */
    public List<LogGroup> listGroups() {
        try {
            LogGroupListResponse response = client.logGroupsApi.listLogGroups();
            List<LogGroup> result = new ArrayList<>();
            if (response.getData() != null) {
                for (LogGroupResource r : response.getData()) {
                    result.add(client.logGroupResourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * Delete a log group by id.
     *
     * @param id the group id to delete
     */
    public void deleteGroup(String id) {
        try {
            client.logGroupsApi.deleteLogGroup(id);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }
}
