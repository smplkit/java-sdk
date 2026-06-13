package com.smplkit.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.smplkit.errors.ApiErrorDetail;
import com.smplkit.errors.ConflictError;
import com.smplkit.errors.ConnectionError;
import com.smplkit.errors.NotFoundError;
import com.smplkit.errors.PaymentRequiredError;
import com.smplkit.errors.SmplError;
import com.smplkit.errors.TimeoutError;
import com.smplkit.errors.ValidationError;

import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps server error responses to the appropriate SDK exception.
 *
 * <p>Internal plumbing — not part of the public SDK surface.</p>
 */
public final class ApiExceptionHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiExceptionHandler() {}

    public static SmplError mapApiException(Exception e) {
        Throwable cause = e.getCause();
        if (cause != null) {
            Throwable c = cause;
            while (c != null) {
                if (c instanceof HttpConnectTimeoutException) {
                    return new TimeoutError("Request timed out: " + c.getMessage(), cause);
                }
                if (c instanceof java.net.UnknownHostException) {
                    return new ConnectionError(
                            "Cannot connect: hostname not found: " + c.getMessage(), cause);
                }
                c = c.getCause();
            }
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            return new ConnectionError("Cannot connect: " + msg, cause);
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        return new ConnectionError("Cannot connect: " + msg, e);
    }

    public static SmplError mapApiException(int statusCode, String responseBody) {
        List<ApiErrorDetail> errors = parseErrors(responseBody);
        String message = !errors.isEmpty() ? deriveMessage(errors) : "HTTP " + statusCode;

        return switch (statusCode) {
            case 400, 422 -> new ValidationError(message, statusCode, responseBody, errors);
            case 402 -> new PaymentRequiredError(message, responseBody, errors);
            case 404 -> new NotFoundError(message, responseBody, errors);
            case 409 -> new ConflictError(message, responseBody, errors);
            default -> new SmplError(message, statusCode, responseBody, errors);
        };
    }

    /** Derives a human-readable message from a list of error details. */
    static String deriveMessage(List<ApiErrorDetail> errors) {
        if (errors == null || errors.isEmpty()) {
            return "An API error occurred";
        }
        ApiErrorDetail first = errors.get(0);
        String msg;
        if (first.detail() != null && !first.detail().isEmpty()) {
            msg = first.detail();
        } else if (first.title() != null && !first.title().isEmpty()) {
            msg = first.title();
        } else if (first.status() != null && !first.status().isEmpty()) {
            msg = "HTTP " + first.status();
        } else {
            msg = "An API error occurred";
        }
        int extra = errors.size() - 1;
        if (extra == 1) {
            msg += " (and 1 more error)";
        } else if (extra > 1) {
            msg += " (and " + extra + " more errors)";
        }
        return msg;
    }

    static List<ApiErrorDetail> parseErrors(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode errorsNode = root.get("errors");
            if (errorsNode == null || !errorsNode.isArray()) {
                return Collections.emptyList();
            }
            List<ApiErrorDetail> result = new ArrayList<>();
            for (JsonNode errorNode : errorsNode) {
                String status = textOrNull(errorNode, "status");
                String code = textOrNull(errorNode, "code");
                String title = textOrNull(errorNode, "title");
                String detail = textOrNull(errorNode, "detail");
                ApiErrorDetail.Source source = null;

                JsonNode sourceNode = errorNode.get("source");
                if (sourceNode != null && sourceNode.isObject()) {
                    String pointer = textOrNull(sourceNode, "pointer");
                    source = new ApiErrorDetail.Source(pointer);
                }

                final Map<String, Object> meta;
                JsonNode metaNode = errorNode.get("meta");
                if (metaNode != null && metaNode.isObject()) {
                    final Map<String, Object> metaMap = new LinkedHashMap<>();
                    metaNode.fields().forEachRemaining(entry ->
                        metaMap.put(entry.getKey(), decodeMetaValue(entry.getValue())));
                    meta = metaMap;
                } else {
                    meta = null;
                }

                result.add(new ApiErrorDetail(status, code, title, detail, source, meta));
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Convert a JsonNode meta value into a native Java object — String,
     * Boolean, Number, null, or fallback toString(). Jackson's
     * {@code numberValue()} returns the right boxed numeric type
     * (Integer / Long / Double / BigDecimal) so we don't have to
     * dispatch on {@code isInt}/{@code isLong}/{@code isDouble}
     * separately — which collapses the branch / coverage explosion the
     * earlier if-else chain produced under JaCoCo on JDK 21.
     */
    private static Object decodeMetaValue(JsonNode v) {
        if (v.isNull()) return null;
        if (v.isTextual()) return v.asText();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.numberValue();
        return v.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child != null && child.isTextual()) {
            return child.asText();
        }
        return null;
    }
}
