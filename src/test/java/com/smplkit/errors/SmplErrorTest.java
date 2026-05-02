package com.smplkit.errors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the exception hierarchy.
 */
class SmplErrorTest {

    @Test
    void smplExceptionCarriesStatusCodeAndBody() {
        SmplError ex = new SmplError("something failed", 500, "{\"error\":\"oops\"}");
        assertEquals("something failed", ex.getMessage());
        assertEquals(500, ex.statusCode());
        assertEquals("{\"error\":\"oops\"}", ex.responseBody());
    }

    @Test
    void smplExceptionWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        SmplError ex = new SmplError("wrapped", 0, null, cause);
        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertEquals(0, ex.statusCode());
        assertNull(ex.responseBody());
    }

    @Test
    void notFoundExceptionIsSmplError() {
        NotFoundError ex = new NotFoundError("not found", "body");
        assertInstanceOf(SmplError.class, ex);
        assertInstanceOf(RuntimeException.class, ex);
        assertEquals(404, ex.statusCode());
        assertEquals("body", ex.responseBody());
        assertEquals("not found", ex.getMessage());
    }

    @Test
    void conflictExceptionIsSmplError() {
        ConflictError ex = new ConflictError("conflict", "body");
        assertInstanceOf(SmplError.class, ex);
        assertEquals(409, ex.statusCode());
        assertEquals("body", ex.responseBody());
    }

    @Test
    void validationExceptionIsSmplError() {
        ValidationError ex = new ValidationError("invalid", "body");
        assertInstanceOf(SmplError.class, ex);
        assertEquals(422, ex.statusCode());
        assertEquals("body", ex.responseBody());
    }

    @Test
    void connectionExceptionIsSmplError() {
        Exception cause = new Exception("conn refused");
        ConnectionError ex = new ConnectionError("connection failed", cause);
        assertInstanceOf(SmplError.class, ex);
        assertEquals(0, ex.statusCode());
        assertNull(ex.responseBody());
        assertSame(cause, ex.getCause());
    }

    @Test
    void timeoutExceptionIsSmplError() {
        Exception cause = new Exception("timed out");
        TimeoutError ex = new TimeoutError("timeout", cause);
        assertInstanceOf(SmplError.class, ex);
        assertEquals(0, ex.statusCode());
        assertNull(ex.responseBody());
        assertSame(cause, ex.getCause());
    }

    @Test
    void allExceptionsCatchableAsSmplError() {
        // Verify all subtypes can be caught as SmplError
        SmplError[] exceptions = {
                new NotFoundError("a", "b"),
                new ConflictError("a", "b"),
                new ValidationError("a", "b"),
                new ConnectionError("a", new Exception()),
                new TimeoutError("a", new Exception()),
        };
        for (SmplError ex : exceptions) {
            assertInstanceOf(SmplError.class, ex);
            assertInstanceOf(RuntimeException.class, ex);
        }
    }

    @Test
    void allExceptionsCatchableAsRuntimeException() {
        NotFoundError ex = new NotFoundError("test", "body");
        try {
            throw ex;
        } catch (RuntimeException caught) {
            assertSame(ex, caught);
        }
    }
}
