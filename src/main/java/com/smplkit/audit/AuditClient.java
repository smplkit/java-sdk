package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.api.EventsApi;
import com.smplkit.internal.generated.audit.api.ForwardersApi;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Audit-product entry point — accessed via {@code SmplClient.audit()}.
 *
 * <p>Sub-clients: {@link #events()} for event recording / listing /
 * retrieval, {@link #forwarders()} for SIEM streaming destinations and
 * the delivery log (Pro tier only — lower tiers get a wrapped 402),
 * {@link #functions()} for server-side proxy actions like
 * {@code test_forwarder/execute}.</p>
 */
public final class AuditClient implements AutoCloseable {

    private final AuditEvents events;
    private final AuditForwarders forwarders;
    private final AuditFunctions functions;

    public AuditClient(HttpClient httpClient, String apiKey, Duration timeout, String baseUrl) {
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(baseUrl);
        apiClient.setRequestInterceptor(builder -> builder.header("Authorization", "Bearer " + apiKey));
        apiClient.setReadTimeout(timeout);
        EventsApi eventsApi = new EventsApi(apiClient);
        ForwardersApi forwardersApi = new ForwardersApi(apiClient);
        this.events = new AuditEvents(eventsApi);
        this.forwarders = new AuditForwarders(forwardersApi);
        this.functions = new AuditFunctions(forwardersApi);
    }

    /** Returns the events sub-client. */
    public AuditEvents events() {
        return events;
    }

    /** Returns the SIEM forwarders sub-client. Pro tier only. */
    public AuditForwarders forwarders() {
        return forwarders;
    }

    /** Returns the server-side functions sub-client. */
    public AuditFunctions functions() {
        return functions;
    }

    @Override
    public void close() {
        events.close();
    }
}
