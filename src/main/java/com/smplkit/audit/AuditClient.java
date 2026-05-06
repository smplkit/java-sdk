package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.api.DefaultApi;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Audit-product entry point — accessed via {@code SmplClient.audit()}.
 *
 * <p>Today the audit namespace is exclusively {@link #events()}; future
 * iterations may add SIEM exports as additional sub-clients (ADR-047
 * §2.7 lists SIEM streaming as a Pro-tier capability).</p>
 */
public final class AuditClient implements AutoCloseable {

    private final AuditEvents events;

    public AuditClient(HttpClient httpClient, String apiKey, Duration timeout, String baseUrl) {
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(baseUrl);
        apiClient.setRequestInterceptor(builder -> builder.header("Authorization", "Bearer " + apiKey));
        apiClient.setReadTimeout(timeout);
        DefaultApi api = new DefaultApi(apiClient);
        this.events = new AuditEvents(api);
    }

    /** Returns the events sub-client. */
    public AuditEvents events() {
        return events;
    }

    @Override
    public void close() {
        events.close();
    }
}
