package com.smplkit.logging;

import com.smplkit.Helpers;
import com.smplkit.internal.generated.logging.ApiException;
import com.smplkit.internal.generated.logging.api.LoggersApi;
import com.smplkit.internal.generated.logging.model.LoggerBulkItem;
import com.smplkit.internal.generated.logging.model.LoggerBulkRequest;
import com.smplkit.internal.generated.logging.model.LoggerListResponse;
import com.smplkit.internal.generated.logging.model.LoggerResource;
import com.smplkit.internal.generated.logging.model.LoggerResponse;
import org.openapitools.jackson.nullable.JsonNullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Management-plane client for loggers, exposed as {@code mgmt.loggers}.
 *
 * <p>Mirrors the Python SDK's {@code mgmt.loggers}: pure CRUD (no runtime
 * level resolution, no adapter discovery, no WebSocket). Construction has
 * zero side effects.</p>
 */
public final class LoggersClient {

    private final LoggingClient inner;

    /** Construct from a non-started {@link LoggingClient} that holds the loggersApi. */
    public LoggersClient(LoggingClient inner) {
        this.inner = inner;
    }

    /**
     * Construct an unsaved {@link Logger}.
     *
     * <p>Mirrors Python rule 9: {@code name} is omitted (the id doubles as the
     * display name) and {@code managed} defaults to {@code true} since every
     * caller using the management API to create a logger is doing so to manage it.</p>
     */
    public Logger new_(String id) {
        return new Logger(inner, id, Helpers.keyToDisplayName(id),
                null, null, true, null, null, null, null);
    }

    /** Construct an unsaved {@link Logger} with explicit {@code managed} flag (advanced). */
    public Logger new_(String id, boolean managed) {
        return new Logger(inner, id, Helpers.keyToDisplayName(id),
                null, null, managed, null, null, null, null);
    }

    /** Get a logger by id. */
    public Logger get(String id) {
        try {
            LoggerResponse response = inner.loggersApi.getLogger(id);
            return inner.loggerResponseToModel(response);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /** List all loggers. */
    public List<Logger> list() {
        try {
            LoggerListResponse response = inner.loggersApi.listLoggers(null, null, null);
            List<Logger> result = new ArrayList<>();
            if (response.getData() != null) {
                for (LoggerResource r : response.getData()) {
                    result.add(inner.loggerResourceToModel(r));
                }
            }
            return result;
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /** Delete a logger by id. */
    public void delete(String id) {
        try {
            inner.loggersApi.deleteLogger(id);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }

    /**
     * Bulk-register explicit logger sources with the logging service.
     *
     * <p>Useful for sample-data seeding, cross-tenant migration, and test fixtures.
     * Unlike runtime auto-discovery, this method takes explicit
     * {@code (service, environment)} overrides per source.</p>
     */
    public void registerSources(List<LoggerSource> sources) {
        if (sources == null || sources.isEmpty()) return;
        LoggerBulkRequest req = new LoggerBulkRequest();
        for (LoggerSource src : sources) {
            LoggerBulkItem item = new LoggerBulkItem();
            item.setId(src.name());
            item.setResolvedLevel(src.resolvedLevel().getValue());
            if (src.level() != null) {
                item.setLevel_JsonNullable(JsonNullable.of(src.level().getValue()));
            }
            if (src.service() != null) item.setService(src.service());
            if (src.environment() != null) item.setEnvironment(src.environment());
            req.addLoggersItem(item);
        }
        try {
            inner.loggersApi.bulkRegisterLoggers(req);
        } catch (ApiException e) {
            throw LoggingClient.mapLoggingException(e);
        }
    }
}
