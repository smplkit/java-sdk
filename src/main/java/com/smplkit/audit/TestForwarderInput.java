package com.smplkit.audit;

import java.util.ArrayList;
import java.util.List;

/** Input for {@link AuditFunctions#executeTestForwarder(TestForwarderInput)}. */
public final class TestForwarderInput {
    public String url;
    public String method = "POST";
    public List<HttpHeader> headers = new ArrayList<>();
    public String body; // nullable
    public String successStatus = "2xx";
    /** Capped at 30s server-side. */
    public Integer timeoutMs;

    public TestForwarderInput() {}

    public TestForwarderInput(String url) {
        this.url = url;
    }
}
