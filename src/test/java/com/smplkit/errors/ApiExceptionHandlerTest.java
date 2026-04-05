package com.smplkit.errors;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ApiExceptionHandler} — JSON:API error parsing and exception mapping.
 */
class ApiExceptionHandlerTest {

    // -----------------------------------------------------------------------
    // Single-error 400 response
    // -----------------------------------------------------------------------

    @Test
    void singleError400_throwsValidationException_withDetailMessage() {
        String body = """
                {
                  "errors": [
                    {
                      "status": "400",
                      "title": "Validation Error",
                      "detail": "The 'name' field is required.",
                      "source": {"pointer": "/data/attributes/name"}
                    }
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(400, body);

        assertInstanceOf(SmplValidationException.class, ex);
        assertEquals("The 'name' field is required.", ex.getMessage());
        assertEquals(400, ex.statusCode());
        assertEquals(Integer.valueOf(400), ex.getStatusCode());
        assertEquals(body, ex.responseBody());

        List<SmplException.ApiError> errors = ex.getErrors();
        assertEquals(1, errors.size());

        SmplException.ApiError error = errors.get(0);
        assertEquals("400", error.getStatus());
        assertEquals("Validation Error", error.getTitle());
        assertEquals("The 'name' field is required.", error.getDetail());
        assertNotNull(error.getSource());
        assertEquals("/data/attributes/name", error.getSource().getPointer());

        // toString includes JSON
        String str = ex.toString();
        assertTrue(str.contains("SmplValidationException"));
        assertTrue(str.contains("The 'name' field is required."));
        assertTrue(str.contains("\"status\": \"400\""));
        assertTrue(str.contains("\"pointer\": \"/data/attributes/name\""));
    }

    // -----------------------------------------------------------------------
    // Multi-error 400 response
    // -----------------------------------------------------------------------

    @Test
    void multiError400_throwsValidationException_withCountSuffix() {
        String body = """
                {
                  "errors": [
                    {
                      "status": "400",
                      "title": "Validation Error",
                      "detail": "The 'name' field is required.",
                      "source": {"pointer": "/data/attributes/name"}
                    },
                    {
                      "status": "400",
                      "title": "Validation Error",
                      "detail": "The 'id' field is required.",
                      "source": {"pointer": "/data/id"}
                    }
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(400, body);

        assertInstanceOf(SmplValidationException.class, ex);
        assertEquals("The 'name' field is required. (and 1 more error)", ex.getMessage());

        List<SmplException.ApiError> errors = ex.getErrors();
        assertEquals(2, errors.size());

        assertEquals("The 'name' field is required.", errors.get(0).getDetail());
        assertEquals("/data/attributes/name", errors.get(0).getSource().getPointer());
        assertEquals("The 'id' field is required.", errors.get(1).getDetail());
        assertEquals("/data/id", errors.get(1).getSource().getPointer());

        // toString shows all errors
        String str = ex.toString();
        assertTrue(str.contains("[0]"));
        assertTrue(str.contains("[1]"));
        assertTrue(str.contains("Errors:"));
    }

    // -----------------------------------------------------------------------
    // 404 response
    // -----------------------------------------------------------------------

    @Test
    void error404_throwsNotFoundException_withServerDetail() {
        String body = """
                {
                  "errors": [
                    {
                      "status": "404",
                      "title": "Not Found",
                      "detail": "Config with id '123' does not exist."
                    }
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(404, body);

        assertInstanceOf(SmplNotFoundException.class, ex);
        assertEquals("Config with id '123' does not exist.", ex.getMessage());
        assertEquals(404, ex.statusCode());

        List<SmplException.ApiError> errors = ex.getErrors();
        assertEquals(1, errors.size());
        assertEquals("Not Found", errors.get(0).getTitle());
        assertNull(errors.get(0).getSource());
    }

    // -----------------------------------------------------------------------
    // 409 response
    // -----------------------------------------------------------------------

    @Test
    void error409_throwsConflictException_withServerDetail() {
        String body = """
                {
                  "errors": [
                    {
                      "status": "409",
                      "title": "Conflict",
                      "detail": "Cannot delete config with children."
                    }
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(409, body);

        assertInstanceOf(SmplConflictException.class, ex);
        assertEquals("Cannot delete config with children.", ex.getMessage());
        assertEquals(409, ex.statusCode());

        List<SmplException.ApiError> errors = ex.getErrors();
        assertEquals(1, errors.size());
        assertEquals("Conflict", errors.get(0).getTitle());
    }

    // -----------------------------------------------------------------------
    // Non-JSON 502 response
    // -----------------------------------------------------------------------

    @Test
    void nonJson502_throwsSmplException_withHttpStatus() {
        String body = "<html>Bad Gateway</html>";

        SmplException ex = ApiExceptionHandler.mapApiException(502, body);

        assertInstanceOf(SmplException.class, ex);
        assertFalse(ex instanceof SmplValidationException);
        assertFalse(ex instanceof SmplNotFoundException);
        assertFalse(ex instanceof SmplConflictException);
        assertEquals("HTTP 502", ex.getMessage());
        assertEquals(502, ex.statusCode());
        assertTrue(ex.getErrors().isEmpty());

        // toString should not show error JSON
        String str = ex.toString();
        assertTrue(str.contains("SmplException"));
        assertTrue(str.contains("HTTP 502"));
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void nullBody_fallsBackToHttpStatus() {
        SmplException ex = ApiExceptionHandler.mapApiException(500, null);

        assertEquals("HTTP 500", ex.getMessage());
        assertTrue(ex.getErrors().isEmpty());
        assertEquals(500, ex.statusCode());
    }

    @Test
    void emptyBody_fallsBackToHttpStatus() {
        SmplException ex = ApiExceptionHandler.mapApiException(500, "");

        assertEquals("HTTP 500", ex.getMessage());
        assertTrue(ex.getErrors().isEmpty());
    }

    @Test
    void jsonWithoutErrors_fallsBackToHttpStatus() {
        String body = """
                {"message": "Something went wrong"}
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(500, body);

        assertEquals("HTTP 500", ex.getMessage());
        assertTrue(ex.getErrors().isEmpty());
    }

    @Test
    void errorWithNoDetailUsesTitle() {
        String body = """
                {
                  "errors": [
                    {
                      "status": "400",
                      "title": "Bad Request"
                    }
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(400, body);

        assertEquals("Bad Request", ex.getMessage());
    }

    @Test
    void errorWithNoDetailOrTitleUsesStatus() {
        String body = """
                {
                  "errors": [
                    {
                      "status": "400"
                    }
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(400, body);

        assertEquals("HTTP 400", ex.getMessage());
    }

    @Test
    void errorWithNoFields_usesDefaultMessage() {
        String body = """
                {
                  "errors": [
                    {}
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(400, body);

        assertEquals("An API error occurred", ex.getMessage());
    }

    @Test
    void error422_throwsValidationException() {
        String body = """
                {
                  "errors": [
                    {
                      "status": "422",
                      "detail": "Invalid value"
                    }
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(422, body);

        assertInstanceOf(SmplValidationException.class, ex);
        assertEquals(422, ex.statusCode());
    }

    @Test
    void threeErrors_showsAndNMoreErrors() {
        String body = """
                {
                  "errors": [
                    {"detail": "Error one"},
                    {"detail": "Error two"},
                    {"detail": "Error three"}
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(400, body);

        assertEquals("Error one (and 2 more errors)", ex.getMessage());
        assertEquals(3, ex.getErrors().size());
    }

    @Test
    void sourceWithNullPointer() {
        String body = """
                {
                  "errors": [
                    {
                      "detail": "Some error",
                      "source": {}
                    }
                  ]
                }
                """;

        SmplException ex = ApiExceptionHandler.mapApiException(400, body);

        SmplException.ApiError error = ex.getErrors().get(0);
        assertNotNull(error.getSource());
        assertNull(error.getSource().getPointer());
        // Source with null pointer should still produce valid JSON
        assertTrue(error.toJson().contains("\"source\": {}"));
    }

    @Test
    void apiErrorToJson_allFields() {
        SmplException.ApiErrorSource source = new SmplException.ApiErrorSource("/data/id");
        SmplException.ApiError error = new SmplException.ApiError("400", "Bad Request", "Missing field", source);

        String json = error.toJson();

        assertTrue(json.contains("\"status\": \"400\""));
        assertTrue(json.contains("\"title\": \"Bad Request\""));
        assertTrue(json.contains("\"detail\": \"Missing field\""));
        assertTrue(json.contains("\"pointer\": \"/data/id\""));
    }

    @Test
    void apiErrorToJson_minimalFields() {
        SmplException.ApiError error = new SmplException.ApiError(null, null, "Only detail", null);

        String json = error.toJson();

        assertEquals("{\"detail\": \"Only detail\"}", json);
    }

    @Test
    void getStatusCode_returnsNullForZero() {
        SmplException ex = new SmplException("test", 0, null);
        assertNull(ex.getStatusCode());
    }

    @Test
    void getStatusCode_returnsValueForNonZero() {
        SmplException ex = new SmplException("test", 500, null);
        assertEquals(Integer.valueOf(500), ex.getStatusCode());
    }

    @Test
    void constructorWithNullErrors_defaultsToEmptyList() {
        SmplException ex = new SmplException("test", 500, null, (List<SmplException.ApiError>) null);
        assertTrue(ex.getErrors().isEmpty());
        assertEquals("test", ex.getMessage());
    }

    @Test
    void deriveMessage_nullList_returnsDefault() {
        assertEquals("An API error occurred", SmplException.deriveMessage(null));
    }

    @Test
    void deriveMessage_emptyList_returnsDefault() {
        assertEquals("An API error occurred", SmplException.deriveMessage(List.of()));
    }

    @Test
    void escapeJson_null_returnsEmptyString() {
        assertEquals("", SmplException.escapeJson(null));
    }

    @Test
    void escapeJson_escapesQuotesAndBackslashes() {
        assertEquals("a\\\\b\\\"c", SmplException.escapeJson("a\\b\"c"));
    }

    @Test
    void toString_noErrors_showsClassAndMessage() {
        SmplException ex = new SmplException("test message", 500, null);
        assertEquals("SmplException: test message", ex.toString());
    }
}
