package com.smplkit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps server error responses to the appropriate SDK exception. */
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
        String message = !errors.isEmpty() ? SmplError.deriveMessage(errors) : "HTTP " + statusCode;

        return switch (statusCode) {
            case 400, 422 -> new ValidationError(message, statusCode, responseBody, errors);
            case 402 -> new PaymentRequiredError(message, responseBody, errors);
            case 404 -> new NotFoundError(message, responseBody, errors);
            case 409 -> new ConflictError(message, responseBody, errors);
            default -> new SmplError(message, statusCode, responseBody, errors);
        };
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
                    metaNode.fields().forEachRemaining(entry -> {
                        JsonNode v = entry.getValue();
                        if (v.isTextual()) metaMap.put(entry.getKey(), v.asText());
                        else if (v.isInt()) metaMap.put(entry.getKey(), v.asInt());
                        else if (v.isLong()) metaMap.put(entry.getKey(), v.asLong());
                        else if (v.isDouble() || v.isFloat()) metaMap.put(entry.getKey(), v.asDouble());
                        else if (v.isBoolean()) metaMap.put(entry.getKey(), v.asBoolean());
                        else if (v.isNull()) metaMap.put(entry.getKey(), null);
                        else metaMap.put(entry.getKey(), v.toString());
                    });
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

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child != null && child.isTextual()) {
            return child.asText();
        }
        return null;
    }
}
