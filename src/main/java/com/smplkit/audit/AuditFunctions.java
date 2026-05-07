package com.smplkit.audit;

import com.smplkit.internal.generated.audit.ApiException;
import com.smplkit.internal.generated.audit.api.ForwardersApi;
import com.smplkit.internal.generated.audit.model.HttpHeader;
import com.smplkit.internal.generated.audit.model.TestForwarderRequest;
import com.smplkit.internal.generated.audit.model.TestForwarderResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side audit functions surface — accessed via
 * {@link AuditClient#functions()}.
 *
 * <p>Today the only function is the {@code test_forwarder} proxy that
 * the console uses to preview a destination from inside the audit
 * service (bypassing browser CORS). The same SSRF guard that gates the
 * in-line forwarder loop is applied: private/loopback/link-local IPs
 * (incl. the EC2 IMDS at 169.254.169.254) and disallowed ports are
 * rejected without making the request.</p>
 */
public final class AuditFunctions {

    private final ForwardersApi api;

    AuditFunctions(ForwardersApi api) {
        this.api = api;
    }

    public TestForwarderResult executeTestForwarder(TestForwarderInput input) throws ApiException {
        TestForwarderRequest body = new TestForwarderRequest();
        body.setUrl(input.url);
        if (input.method != null) body.setMethod(input.method);
        if (input.successStatus != null) body.setSuccessStatus(input.successStatus);
        if (input.body != null) body.setBody(input.body);
        if (input.timeoutMs != null) body.setTimeoutMs(input.timeoutMs);
        if (input.headers != null) {
            List<HttpHeader> hh = new ArrayList<>();
            for (com.smplkit.audit.HttpHeader h : input.headers) {
                HttpHeader g = new HttpHeader();
                g.setName(h.name);
                g.setValue(h.value);
                hh.add(g);
            }
            body.setHeaders(hh);
        }
        TestForwarderResponse resp = api.executeTestForwarder(body);
        Map<String, String> headers = resp.getResponseHeaders() != null
                ? new HashMap<>(resp.getResponseHeaders())
                : new HashMap<>();
        String responseBody = resp.getResponseBody() != null ? resp.getResponseBody() : "";
        return new TestForwarderResult(
                resp.getSucceeded() != null && resp.getSucceeded(),
                resp.getResponseStatus(),
                headers,
                responseBody,
                resp.getLatencyMs(),
                resp.getError());
    }
}
