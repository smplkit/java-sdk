package com.smplkit.audit;

import java.util.Map;

/**
 * Input for {@link AuditForwarders#create(CreateForwarderInput)} and
 * {@link AuditForwarders#update(java.util.UUID, CreateForwarderInput)}.
 */
public final class CreateForwarderInput {
    public String name;
    public ForwarderType forwarderType;
    public ForwarderHttp http;
    public boolean enabled = true;
    public Map<String, Object> filter; // nullable, JSON Logic
    public String transform; // nullable, JSONata

    public CreateForwarderInput() {}

    public CreateForwarderInput(String name, ForwarderType forwarderType, ForwarderHttp http) {
        this.name = name;
        this.forwarderType = forwarderType;
        this.http = http;
    }
}
