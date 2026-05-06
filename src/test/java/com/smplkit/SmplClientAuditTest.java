package com.smplkit;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link SmplClient#audit()} — the package-private
 * accessor wired up in §6 of ADR-047.
 */
class SmplClientAuditTest {

    @Test
    void auditAccessor_returnsAuditClient() {
        var client = new SmplClient(HttpClient.newHttpClient(),
                "sk_api_test", "dev", "test-svc", Duration.ofSeconds(5));
        try {
            assertNotNull(client.audit());
            assertSame(client.audit(), client.audit());
        } finally {
            client.close();
        }
    }
}
