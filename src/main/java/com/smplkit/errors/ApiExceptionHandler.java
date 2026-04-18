package com.smplkit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpConnectTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps server error responses to the appropriate SDK exception.
 */
public final class ApiExceptionHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiExceptionHandler() {}

    /**
     * Maps an ApiException (from the generated client) to the appropriate SDK exception.
     * Handles code=0 cases (network errors like DNS failures) by inspecting the cause chain.
     *
     * @param e the ApiException from the generated client
     * @return the appropriate SmplException
     */
    public static SmplException mapApiException(Exception e) {
        Throwable cause = e.getCause();
        if (cause != null) {
            // Walk the cause chain looking for a timeout or DNS failure.
            Throwable c = cause;
            while (c != null) {
                if (c instanceof HttpConnectTimeoutException) {
                    return new SmplTimeoutException("Request timed out: " + c.getMessage(), cause);
                }
                if (c instanceof java.net.UnknownHostException) {
                    return new SmplConnectionException(
                            "Cannot connect: hostname not found: " + c.getMessage(), cause);
                }
                c = c.getCause();
            }
            // Generic network error — use the immediate cause message.
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            return new SmplConnectionException("Cannot connect: " + msg, cause);
        }
        // No cause: fall back to generic message.
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        return new SmplConnectionException("Cannot connect: " + msg, e);
    }

    /**
     * Maps an HTTP error response to the appropriate SDK exception.
     *
     * @param statusCode   HTTP status code
     * @param responseBody raw response body (may be null)
     * @return the appropriate SmplException
     */
    public static SmplException mapApiException(int statusCode, String responseBody) {
        List<SmplException.ApiError> errors = parseErrors(responseBody);
        String message;
        if (!errors.isEmpty()) {
            message = SmplException.deriveMessage(errors);
        } else {
            message = "HTTP " + statusCode;
        }

        return switch (statusCode) {
            case 400, 422 -> new SmplValidationException(message, statusCode, responseBody, errors);
            case 404 -> new SmplNotFoundException(message, responseBody, errors);
            case 409 -> new SmplConflictException(message, responseBody, errors);
            default -> new SmplException(message, statusCode, responseBody, errors);
        };
    }

    /**
     * Attempts to parse structured errors from a response body.
     *
     * @param responseBody the raw response body
     * @return list of parsed errors, or empty list if parsing fails
     */
    static List<SmplException.ApiError> parseErrors(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode errorsNode = root.get("errors");
            if (errorsNode == null || !errorsNode.isArray()) {
                return Collections.emptyList();
            }
            List<SmplException.ApiError> result = new ArrayList<>();
            for (JsonNode errorNode : errorsNode) {
                String status = textOrNull(errorNode, "status");
                String title = textOrNull(errorNode, "title");
                String detail = textOrNull(errorNode, "detail");
                SmplException.ApiErrorSource source = null;

                JsonNode sourceNode = errorNode.get("source");
                if (sourceNode != null && sourceNode.isObject()) {
                    String pointer = textOrNull(sourceNode, "pointer");
                    source = new SmplException.ApiErrorSource(pointer);
                }

                result.add(new SmplException.ApiError(status, title, detail, source));
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
