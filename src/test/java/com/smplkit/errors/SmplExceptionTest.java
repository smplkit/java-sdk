package com.smplkit.errors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the exception hierarchy.
 */
class SmplExceptionTest {

    @Test
    void smplExceptionCarriesStatusCodeAndBody() {
        SmplException ex = new SmplException("something failed", 500, "{\"error\":\"oops\"}");
        assertEquals("something failed", ex.getMessage());
        assertEquals(500, ex.statusCode());
        assertEquals("{\"error\":\"oops\"}", ex.responseBody());
    }

    @Test
    void smplExceptionWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        SmplException ex = new SmplException("wrapped", 0, null, cause);
        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertEquals(0, ex.statusCode());
        assertNull(ex.responseBody());
    }

    @Test
    void notFoundExceptionIsSmplException() {
        SmplNotFoundException ex = new SmplNotFoundException("not found", "body");
        assertInstanceOf(SmplException.class, ex);
        assertInstanceOf(RuntimeException.class, ex);
        assertEquals(404, ex.statusCode());
        assertEquals("body", ex.responseBody());
        assertEquals("not found", ex.getMessage());
    }

    @Test
    void conflictExceptionIsSmplException() {
        SmplConflictException ex = new SmplConflictException("conflict", "body");
        assertInstanceOf(SmplException.class, ex);
        assertEquals(409, ex.statusCode());
        assertEquals("body", ex.responseBody());
    }

    @Test
    void validationExceptionIsSmplException() {
        SmplValidationException ex = new SmplValidationException("invalid", "body");
        assertInstanceOf(SmplException.class, ex);
        assertEquals(422, ex.statusCode());
        assertEquals("body", ex.responseBody());
    }

    @Test
    void connectionExceptionIsSmplException() {
        Exception cause = new Exception("conn refused");
        SmplConnectionException ex = new SmplConnectionException("connection failed", cause);
        assertInstanceOf(SmplException.class, ex);
        assertEquals(0, ex.statusCode());
        assertNull(ex.responseBody());
        assertSame(cause, ex.getCause());
    }

    @Test
    void timeoutExceptionIsSmplException() {
        Exception cause = new Exception("timed out");
        SmplTimeoutException ex = new SmplTimeoutException("timeout", cause);
        assertInstanceOf(SmplException.class, ex);
        assertEquals(0, ex.statusCode());
        assertNull(ex.responseBody());
        assertSame(cause, ex.getCause());
    }

    @Test
    void notConnectedExceptionDefaultMessage() {
        SmplNotConnectedException ex = new SmplNotConnectedException();
        assertInstanceOf(SmplException.class, ex);
        assertEquals("SmplClient is not connected. Call connect() first.", ex.getMessage());
        assertEquals(0, ex.statusCode());
        assertNull(ex.responseBody());
    }

    @Test
    void notConnectedExceptionCustomMessage() {
        SmplNotConnectedException ex = new SmplNotConnectedException("custom message");
        assertEquals("custom message", ex.getMessage());
        assertEquals(0, ex.statusCode());
        assertNull(ex.responseBody());
    }

    @Test
    void allExceptionsCatchableAsSmplException() {
        // Verify all subtypes can be caught as SmplException
        SmplException[] exceptions = {
                new SmplNotFoundException("a", "b"),
                new SmplConflictException("a", "b"),
                new SmplValidationException("a", "b"),
                new SmplConnectionException("a", new Exception()),
                new SmplTimeoutException("a", new Exception()),
        };
        for (SmplException ex : exceptions) {
            assertInstanceOf(SmplException.class, ex);
            assertInstanceOf(RuntimeException.class, ex);
        }
    }

    @Test
    void allExceptionsCatchableAsRuntimeException() {
        SmplNotFoundException ex = new SmplNotFoundException("test", "body");
        try {
            throw ex;
        } catch (RuntimeException caught) {
            assertSame(ex, caught);
        }
    }
}
