package com.smplkit.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared helper that parses JSON:API error responses and throws the appropriate
 * SDK exception with full error details.
 *
 * <p>Used by all wrapper clients (ConfigClient, FlagsClient) to replace
 * generic error messages with structured server-provided details.</p>
 */
public final class ApiExceptionHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiExceptionHandler() {}

    /**
     * Parses a JSON:API error response body and throws the appropriate SDK exception.
     *
     * @param statusCode   HTTP status code
     * @param responseBody raw response body (may be null)
     * @return the appropriate SmplException (never returns normally if errors are present)
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
     * Attempts to parse JSON:API errors from a response body.
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
