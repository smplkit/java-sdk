package com.smplkit.audit;

import java.util.Map;

/**
 * Input for {@link AuditForwarders#create(CreateForwarderInput)} and
 * {@link AuditForwarders#update(java.util.UUID, CreateForwarderInput)}.
 *
 * <p>{@code configuration} is the transport-specific destination shape
 * (HTTP request for HTTP-family forwarders). {@code transform} carries
 * either a JSONata expression string (when {@code transformType} is
 * {@code "JSONATA"}) or a structured template for future engines.</p>
 */
public final class CreateForwarderInput {
    public String name;
    public String description; // nullable
    public ForwarderType forwarderType;
    public HttpConfiguration configuration;
    public boolean enabled = true;
    public Map<String, Object> filter; // nullable, JSON Logic
    public String transformType; // nullable, e.g. "JSONATA"
    public Object transform; // nullable

    public CreateForwarderInput() {}

    public CreateForwarderInput(String name, ForwarderType forwarderType, HttpConfiguration configuration) {
        this.name = name;
        this.forwarderType = forwarderType;
        this.configuration = configuration;
    }
}
