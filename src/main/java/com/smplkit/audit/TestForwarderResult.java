package com.smplkit.audit;

import java.util.Map;

/**
 * Plain-JSON response from
 * {@link AuditFunctions#executeTestForwarder(TestForwarderInput)}.
 */
public final class TestForwarderResult {
    public final boolean succeeded;
    public final Integer responseStatus; // nullable
    public final Map<String, String> responseHeaders;
    public final String responseBody;
    public final Integer latencyMs; // nullable
    public final String error; // nullable

    public TestForwarderResult(boolean succeeded, Integer responseStatus,
                               Map<String, String> responseHeaders, String responseBody,
                               Integer latencyMs, String error) {
        this.succeeded = succeeded;
        this.responseStatus = responseStatus;
        this.responseHeaders = responseHeaders;
        this.responseBody = responseBody;
        this.latencyMs = latencyMs;
        this.error = error;
    }
}
