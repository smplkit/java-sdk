package com.smplkit.logging;

import com.smplkit.internal.Debug;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LoggerBulkItem;
import com.smplkit.internal.generated.logging.model.LoggerBulkRequest;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerRequest;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Surface for {@code client.logging.loggers.*}.
 *
 * <p>Logger CRUD plus the discovery buffer. The buffer is owned by the fused
 * {@link LoggingClient} and shared here so discovery (driven by
 * {@link LoggingClient#install()}) and explicit {@link #register} drain through
 * one queue.</p>
 */
public final class LoggersClient {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("smplkit.logging");

    /** Eager-flush threshold — mirrors Python's {@code _LOGGER_BATCH_FLUSH_SIZE}. */
    private static final int LOGGER_BATCH_FLUSH_SIZE = 50;

    private final LoggersApi loggersApi;
    private final LoggerRegistrationBuffer buffer;

    LoggersClient(LoggersApi loggersApi, LoggerRegistrationBuffer buffer) {
        this.loggersApi = loggersApi;
        this.buffer = buffer;
    }

    // -----------------------------------------------------------------------
    // Discovery buffer: register / flush / pendingCount
    // -----------------------------------------------------------------------

    /**
     * Queue a single logger source for registration with the server.
     *
     * <p>The source is buffered locally and sent in a batch once enough
     * sources accumulate.</p>
     *
     * @param item the logger source to queue
     */
    public void register(LoggerSource item) {
        register(List.of(item), false);
    }

    /**
     * Queue logger sources for registration with the server.
     *
     * <p>Sources are buffered locally and sent in a batch once enough
     * accumulate.</p>
     *
     * @param items the logger sources to queue
     */
    public void register(List<LoggerSource> items) {
        register(items, false);
    }

    /**
     * Queue a single logger source for registration, optionally flushing now.
     *
     * @param item  the logger source to queue
     * @param flush when {@code true}, send the buffered sources immediately
     *     rather than waiting for the batch to fill
     */
    public void register(LoggerSource item, boolean flush) {
        register(List.of(item), flush);
    }

    /**
     * Queue logger sources for registration, optionally flushing now.
     *
     * @param items the logger sources to queue
     * @param flush when {@code true}, send the buffered sources immediately
     *     rather than waiting for the batch to fill
     */
    public void register(List<LoggerSource> items, boolean flush) {
        if (items == null) {
            return;
        }
        for (LoggerSource src : items) {
            buffer.add(
                    LoggingClient.normalizeKey(src.name()),
                    src.level() != null ? src.level().getValue() : null,
                    src.resolvedLevel() != null ? src.resolvedLevel().getValue() : null,
                    src.service(),
                    src.environment());
        }
        if (flush) {
            flush();
            return;
        }
        if (buffer.pendingCount() >= LOGGER_BATCH_FLUSH_SIZE) {
            Thread t = new Thread(this::thresholdFlush, "smplkit-logger-flush-eager");
            t.setDaemon(true);
            t.start();
        }
    }

    private void thresholdFlush() {
        try {
            flush();
        } catch (Exception exc) {
            LOG.warning("Logger registration flush failed: " + exc);
        }
    }

    /** Drain the buffer and POST pending logger sources to the bulk endpoint. */
    public void flush() {
        LoggerBulkRequest body = buildBulkRequest();
        if (body == null) {
            return;
        }
        try {
            loggersApi.bulkRegisterLoggers(body);
            Debug.log("registration", "bulk-registered logger sources");
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * Number of sources queued and awaiting flush.
     *
     * @return the count of buffered logger sources not yet sent to the server
     */
    public int pendingCount() {
        return buffer.pendingCount();
    }

    private LoggerBulkRequest buildBulkRequest() {
        List<LoggerRegistrationBuffer.Entry> batch = buffer.drain();
        if (batch.isEmpty()) {
            return null;
        }
        LoggerBulkRequest req = new LoggerBulkRequest();
        for (LoggerRegistrationBuffer.Entry entry : batch) {
            LoggerBulkItem item = new LoggerBulkItem();
            item.setId(entry.id());
            // level: only set when explicitly configured on the logger
            if (entry.level() != null) {
                item.setLevel_JsonNullable(JsonNullable.of(entry.level()));
            }
            // resolved_level: always set — effective level after framework inheritance
            item.setResolvedLevel(entry.resolvedLevel());
            if (entry.service() != null) {
                item.setService(entry.service());
            }
            if (entry.environment() != null) {
                item.setEnvironment(entry.environment());
            }
            req.addLoggersItem(item);
        }
        return req;
    }

    // -----------------------------------------------------------------------
    // Logger CRUD
    // -----------------------------------------------------------------------

    /**
     * Build a new unsaved {@link Logger}. Call {@link Logger#save} to persist.
     *
     * <p>The logger is created with {@code managed} set to {@code true}: smplkit
     * controls its level at runtime. Use {@link #new_(String, boolean)} to
     * register a logger for visibility without taking over its level.</p>
     *
     * @param id the identifier for the logger (its normalized name)
     * @return an unsaved {@link Logger} bound to this client
     */
    public Logger new_(String id) {
        return new Logger(this, id, id, null, null, true, null, null, null, null);
    }

    /**
     * Build a new unsaved {@link Logger} with an explicit {@code managed} flag.
     * Call {@link Logger#save} to persist.
     *
     * @param id      the identifier for the logger (its normalized name)
     * @param managed when {@code true}, smplkit controls this logger's level at
     *     runtime; when {@code false}, the logger is registered for visibility
     *     without smplkit taking over its level
     * @return an unsaved {@link Logger} bound to this client
     */
    public Logger new_(String id, boolean managed) {
        return new Logger(this, id, id, null, null, managed, null, null, null, null);
    }

    /**
     * List loggers for the authenticated account using server-default pagination.
     *
     * @return the loggers on the first page
     */
    public List<Logger> list() {
        return list(null, null);
    }

    /**
     * List loggers for the authenticated account.
     *
     * @param pageNumber the 1-based page index to fetch; when {@code null}, the
     *     server returns the first page
     * @param pageSize   the maximum number of loggers per page; when
     *     {@code null}, the server applies its default page size
     * @return the loggers on the requested page
     */
    public List<Logger> list(Integer pageNumber, Integer pageSize) {
        try {
            // Positional args: filterManaged, filterService, filterLastSeen,
            // filterSearch, sort, pageNumber, pageSize, metaTotal.
            LoggerListResponse response = loggersApi.listLoggers(
                    null, null, null, null, null, pageNumber, pageSize, null);
            List<Logger> result = new ArrayList<>();
            if (response.getData() != null) {
                for (LoggerResource r : response.getData()) {
                    result.add(resourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * Fetch a single logger by id.
     *
     * @param id the identifier of the logger to fetch
     * @return the editable {@link Logger} resource
     * @throws com.smplkit.errors.NotFoundError if no logger with that id exists
     */
    public Logger get(String id) {
        try {
            LoggerResponse response = loggersApi.getLogger(id);
            return resourceToModel(response.getData());
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * Delete a logger by id.
     *
     * @param id the identifier of the logger to delete
     */
    public void delete(String id) {
        try {
            loggersApi.deleteLogger(id);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Internal: save (called by Logger.save())
    // -----------------------------------------------------------------------

    /**
     * Save a logger on the server (id-addressed upsert via PUT). Called by
     * {@link Logger#save()}.
     */
    Logger saveLogger(Logger lg) {
        try {
            LoggerRequest body = buildLoggerBody(lg);
            LoggerResponse response = loggersApi.updateLogger(lg.getId(), body);
            return resourceToModel(response.getData());
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    private LoggerRequest buildLoggerBody(Logger lg) {
        var attrs = new com.smplkit.internal.generated.logging.model.Logger();
        attrs.setName(lg.getName());
        if (lg.getLevel() != null) {
            attrs.setLevel(com.smplkit.internal.generated.logging.model.LogLevel.fromValue(lg.getLevel()));
        }
        if (lg.getGroup() != null) {
            attrs.setGroup(lg.getGroup());
        }
        attrs.setManaged(lg.isManaged());
        // Always include environments — even when empty — so a clearLevel()
        // that drains the last override is carried to the server. Omitting
        // the field is read by the JSON:API put as "no change," which
        // strands the clear in client memory only.
        attrs.setEnvironments(new HashMap<>(
                lg.getEnvironments() != null ? lg.getEnvironments() : new HashMap<>()));

        LoggerResource data = new LoggerResource();
        data.setType(LoggerResource.TypeEnum.LOGGER);
        data.setAttributes(attrs);
        data.setId(lg.getId());

        LoggerRequest body = new LoggerRequest();
        body.setData(data);
        return body;
    }

    Logger resourceToModel(LoggerResource resource) {
        var attrs = resource.getAttributes();
        return new Logger(
                this,
                resource.getId() != null ? resource.getId() : null,
                attrs.getName(),
                attrs.getLevel() != null ? attrs.getLevel().getValue() : null,
                attrs.getGroup(),
                attrs.getManaged() != null ? attrs.getManaged() : false,
                attrs.getSources() != null ? new ArrayList<>(attrs.getSources()) : new ArrayList<>(),
                attrs.getEnvironments() != null ? new HashMap<>(attrs.getEnvironments()) : new HashMap<>(),
                toInstant(attrs.getCreatedAt()),
                toInstant(attrs.getUpdatedAt()));
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
