package com.smplkit.audit;

import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;
import com.smplkit.internal.HttpClients;
import com.smplkit.internal.generated.audit.ApiClient;
import com.smplkit.internal.generated.audit.api.CategoriesApi;
import com.smplkit.internal.generated.audit.api.EventTypesApi;
import com.smplkit.internal.generated.audit.api.EventsApi;
import com.smplkit.internal.generated.audit.api.ForwardersApi;
import com.smplkit.internal.generated.audit.api.ResourceTypesApi;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * The Smpl Audit client (sync).
 *
 * <p>Audit installs no in-process machinery, so it has no runtime/management
 * split: one client exposes the full surface — event recording and reads,
 * distinct-value discovery, and SIEM forwarder CRUD — reachable as
 * {@code client.audit} ({@link com.smplkit.SmplClient}) or constructed
 * directly:</p>
 *
 * <pre>{@code
 * try (AuditClient audit = AuditClient.builder().environment("production").build()) {
 *     audit.events().record(new CreateEventInput("invoice.created", "invoice", "inv-1"));
 *     audit.events().flush(5_000);
 *     for (Forwarder f : audit.forwarders().list()) {
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>Namespaces: {@link #events()} (record/flush/list/get),
 * {@link #resourceTypes()}, {@link #eventTypes()}, {@link #categories()}
 * (discovery), and {@link #forwarders()} (CRUD).</p>
 */
public final class AuditClient implements AutoCloseable {

    private final AuditEvents events;
    private final AuditResourceTypesClient resourceTypes;
    private final AuditEventTypesClient eventTypes;
    private final AuditCategoriesClient categories;
    private final AuditForwarders forwarders;
    private final boolean ownsTransport;

    /**
     * Wired constructor (no environment) — invoked by {@link com.smplkit.SmplClient}
     * so the audit surface shares the parent client's connection pool. The
     * resulting client does NOT own its transport; {@link #close()} tears down
     * only the event buffer.
     */
    public AuditClient(HttpClient httpClient, String apiKey, Map<String, String> extraHeaders,
                       Duration timeout, String baseUrl) {
        this(httpClient, apiKey, extraHeaders, timeout, baseUrl, null);
    }

    /**
     * Wired constructor — invoked by {@link com.smplkit.SmplClient} so the audit
     * surface shares the parent client's connection pool.
     *
     * <p>Runtime audit ops scope to the SDK's configured {@code environment}
     * without a request header (the dead {@code X-Smplkit-Environment} header
     * is no longer read server-side, ADR-055): {@code record} stamps it on the
     * event request body, and {@code list} / discovery default
     * {@code filter[environment]} to it (overridable per call). {@code get} by
     * id and forwarder CRUD are environment-agnostic and carry no scope.</p>
     *
     * <p>This is the wired constructor {@link com.smplkit.SmplClient} calls:
     * {@code AuditClient(java.net.http.HttpClient, apiKey, extraHeaders, timeout,
     * auditBaseUrl, environment)}. The resulting client does NOT own its
     * transport; {@link #close()} tears down only the event buffer.</p>
     *
     * @param environment the SDK's configured runtime environment, or
     *     {@code null} to leave recording and reads unscoped (a
     *     single-environment credential resolves the environment server-side)
     */
    public AuditClient(HttpClient httpClient, String apiKey, Map<String, String> extraHeaders,
                       Duration timeout, String baseUrl, String environment) {
        ApiClient apiClient = buildApiClient(baseUrl, apiKey, extraHeaders, timeout);
        this.events = new AuditEvents(new EventsApi(apiClient), environment);
        this.resourceTypes = new AuditResourceTypesClient(new ResourceTypesApi(apiClient), environment);
        this.eventTypes = new AuditEventTypesClient(new EventTypesApi(apiClient), environment);
        this.categories = new AuditCategoriesClient(new CategoriesApi(apiClient), environment);
        this.forwarders = new AuditForwarders(new ForwardersApi(apiClient));
        this.ownsTransport = false;
    }

    /**
     * Standalone constructor — builds and OWNS its own transport. Used by
     * {@link AuditClientBuilder#build()}.
     */
    private AuditClient(ResolvedClientConfig cfg, String environment, Duration timeout,
                        Map<String, String> extraHeaders) {
        String baseUrl = ConfigResolver.serviceUrl(cfg.scheme, "audit", cfg.baseDomain);
        ApiClient apiClient = buildApiClient(baseUrl, cfg.apiKey, extraHeaders, timeout);
        this.events = new AuditEvents(new EventsApi(apiClient), environment);
        this.resourceTypes = new AuditResourceTypesClient(new ResourceTypesApi(apiClient), environment);
        this.eventTypes = new AuditEventTypesClient(new EventTypesApi(apiClient), environment);
        this.categories = new AuditCategoriesClient(new CategoriesApi(apiClient), environment);
        this.forwarders = new AuditForwarders(new ForwardersApi(apiClient));
        this.ownsTransport = true;
    }

    private static ApiClient buildApiClient(String baseUrl, String apiKey,
                                            Map<String, String> headers, Duration timeout) {
        ApiClient apiClient = new ApiClient();
        apiClient.setHttpClientBuilder(HttpClients.builder());
        apiClient.updateBaseUri(baseUrl);
        apiClient.setRequestInterceptor(HttpClients.compositeInterceptor(apiKey, headers));
        apiClient.setReadTimeout(timeout);
        return apiClient;
    }

    /** Returns the events sub-client (record, flush, list, get). */
    public AuditEvents events() {
        return events;
    }

    /** Returns the resource-types sub-client (list). */
    public AuditResourceTypesClient resourceTypes() {
        return resourceTypes;
    }

    /** Returns the event-types sub-client (list). */
    public AuditEventTypesClient eventTypes() {
        return eventTypes;
    }

    /** Returns the categories sub-client (list). */
    public AuditCategoriesClient categories() {
        return categories;
    }

    /** Returns the forwarders sub-client (SIEM forwarder CRUD). */
    public AuditForwarders forwarders() {
        return forwarders;
    }

    /**
     * Release HTTP resources — drains and stops the event buffer's worker,
     * then (only when this client owns its transport) tears down the transport.
     *
     * <p>An audit client wired by a top-level client shares that client's
     * transport and must not close it here; the owning client's
     * {@code close()} handles teardown. A standalone audit client owns its
     * transport, but the JDK {@code HttpClient} backing it has no persistent
     * resources beyond a daemon selector thread the JVM reclaims on GC, so
     * there is nothing to tear down explicitly today.</p>
     */
    @Override
    public void close() {
        try {
            events.close();
        } finally {
            if (ownsTransport) {
                // No persistent resources beyond the owned HttpClient (managed by JDK).
            }
        }
    }

    // ------------------------------------------------------------------
    // Standalone construction (mirrors SmplClient.create / builder())
    // ------------------------------------------------------------------

    /**
     * Construct a standalone {@link AuditClient}, resolving credentials from the
     * standard sources (env vars, {@code ~/.smplkit}). Owns its own transport.
     * No environment is configured — recording falls back to the server-side
     * default environment.
     */
    public static AuditClient create() {
        return builder().build();
    }

    /** Construct a standalone {@link AuditClient} with the given API key. */
    public static AuditClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** Returns a builder for a standalone {@link AuditClient}. */
    public static AuditClientBuilder builder() {
        return new AuditClientBuilder();
    }

    /** Internal: build a standalone client from already-resolved config. */
    static AuditClient fromResolved(ResolvedClientConfig cfg, String environment,
                                    Duration timeout, Map<String, String> extraHeaders) {
        return new AuditClient(cfg, environment, timeout, extraHeaders);
    }
}
