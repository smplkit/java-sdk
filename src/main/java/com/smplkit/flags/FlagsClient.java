package com.smplkit.flags;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smplkit.internal.ConfigResolver;
import com.smplkit.internal.ConfigResolver.ResolvedClientConfig;
import com.smplkit.Context;
import com.smplkit.Helpers;
import com.smplkit.SharedWebSocket;
import com.smplkit.internal.ApiExceptionHandler;
import com.smplkit.errors.SmplError;
import com.smplkit.flags.types.FlagDeclaration;
import com.smplkit.internal.ContextRegistrationBuffer;
import com.smplkit.internal.generated.app.api.ContextsApi;
import com.smplkit.internal.generated.app.model.ContextBulkItem;
import com.smplkit.internal.generated.app.model.ContextBulkRegister;
import com.smplkit.internal.generated.flags.ApiException;
import com.smplkit.internal.generated.flags.api.FlagsApi;
import com.smplkit.internal.generated.flags.model.FlagBulkItem;
import com.smplkit.internal.generated.flags.model.FlagBulkRequest;
import com.smplkit.internal.generated.flags.model.FlagCreateRequest;
import com.smplkit.internal.generated.flags.model.FlagCreateResource;
import com.smplkit.internal.generated.flags.model.FlagEnvironment;
import com.smplkit.internal.generated.flags.model.FlagListResponse;
import com.smplkit.internal.generated.flags.model.FlagRequest;
import com.smplkit.internal.generated.flags.model.FlagResource;
import com.smplkit.internal.generated.flags.model.FlagResponse;
import com.smplkit.internal.generated.flags.model.FlagRule;
import com.smplkit.internal.generated.flags.model.FlagValue;
import io.github.jamsesso.jsonlogic.JsonLogic;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.openapitools.jackson.nullable.JsonNullableModule;

import com.smplkit.internal.Debug;
import com.smplkit.internal.HttpClients;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Smpl Flags client (sync).
 *
 * <p>Smpl Flags has two surfaces on a single client, mirroring how the config,
 * audit, and jobs clients expose their full surface from one class:</p>
 *
 * <ul>
 *   <li><b>CRUD surface</b> — pure CRUD, no live connection:
 *       {@link #newBooleanFlag} / {@link #newStringFlag} / {@link #newNumberFlag} /
 *       {@link #newJsonFlag} constructors, {@link #get} / {@link #list} / {@link #delete}
 *       CRUD, and the flag-declaration discovery buffer ({@link #register} /
 *       {@link #flush} / {@link #flushSync} / {@link #pendingCount}). The client
 *       owns the discovery buffer directly.</li>
 *   <li><b>Live surface</b> — lazily connects to your running service on first use:
 *       the typed handle declarations ({@link #booleanFlag} / {@link #stringFlag} /
 *       {@link #numberFlag} / {@link #jsonFlag}) whose {@code .get()} evaluates
 *       against the cached definitions, plus {@link #refresh} / {@link #stats} /
 *       {@link #onChange}. The first live call transparently flushes discovery,
 *       fetches all flag definitions into the local cache, and opens the
 *       live-updates WebSocket — no explicit install step.</li>
 * </ul>
 *
 * <p>One client exposes the full surface, reachable as {@code client.flags()}
 * ({@link com.smplkit.SmplClient}) or constructed directly:</p>
 *
 * <pre>{@code
 * try (FlagsClient flags = FlagsClient.builder().environment("production").build()) {
 *     Flag<Boolean> newFlag = flags.newBooleanFlag("beta", false);
 *     newFlag.save();
 *     Flag<Boolean> beta = flags.booleanFlag("beta", false);
 *     if (beta.get()) {
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>The CRUD surface ({@code new*} / {@link #get} / {@link #list} /
 * {@link #delete} and discovery) is pure CRUD. The live surface
 * ({@link #booleanFlag} / {@link #stringFlag} / {@link #numberFlag} /
 * {@link #jsonFlag} / {@link #refresh} / {@link #stats} / {@link #onChange})
 * connects lazily on first use — the first call flushes discovery, fetches all
 * flag definitions into the local cache, and opens the live-updates WebSocket.
 * No explicit install step is required.</p>
 *
 * <p>The client supports two construction shapes:</p>
 *
 * <ul>
 *   <li><b>Wired</b> into {@link com.smplkit.SmplClient} — borrows the parent's
 *       flags transport for both runtime fetch and CRUD, the parent's shared
 *       WebSocket for the live channel, and {@code client.platform.contexts} for
 *       evaluation-context registration. This is the common path.</li>
 *   <li><b>Standalone</b> — {@code FlagsClient.builder().apiKey(...).baseDomain(...)...build()}
 *       builds and owns its own flags transport and a contexts client (against its
 *       own app transport), and on first live use opens and owns its own WebSocket.
 *       {@link #close()} tears down only the owned transports and owned WebSocket.</li>
 * </ul>
 */
public final class FlagsClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("smplkit.flags");
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final JsonLogic JSON_LOGIC = new JsonLogic();
    private static final int CACHE_MAX_SIZE = 10_000;
    private static final int CONTEXT_BUFFER_MAX_SIZE = 10_000;
    private static final int CONTEXT_BATCH_FLUSH_SIZE = 100;
    private static final int FLAG_BATCH_FLUSH_SIZE = 50;

    final FlagsApi flagsApi;
    private final ContextsApi contextsApi;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String flagsBaseUrl;
    private final String appBaseUrl;
    private final Duration timeout;
    private final boolean ownsTransport;

    // --- Runtime state ---
    private volatile boolean connected = false;
    private volatile String environment;
    private final Map<String, Map<String, Object>> flagStore = new ConcurrentHashMap<>();
    private final Map<String, Flag<?>> handles = new ConcurrentHashMap<>();

    // Resolution cache — synchronized LRU
    private final Map<String, Object> resolutionCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            }
    );
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private static final Object CACHE_NULL_SENTINEL = new Object();

    // Shared context registration buffer (set via setContextBuffer; falls back to internal)
    private volatile ContextRegistrationBuffer sharedContextBuffer;

    // Internal fallback context buffer (used when no shared buffer is set)
    private final Map<String, Map<String, Object>> contextBuffer = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > CONTEXT_BUFFER_MAX_SIZE;
                }
            }
    );
    private final ConcurrentLinkedQueue<Map<String, Object>> pendingContexts = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService contextFlushExecutor;
    private ScheduledFuture<?> contextFlushFuture;

    // Flag registration buffer (package-private for test inspection)
    final FlagRegistrationBuffer flagBuffer = new FlagRegistrationBuffer();
    private ScheduledFuture<?> flagFlushFuture;

    // Connect retry state
    private final Object connectLock = new Object();
    private volatile long backoffSeconds = 1;
    private volatile boolean retryScheduled = false;
    private volatile boolean wsHandlersRegistered = false;
    private volatile ScheduledFuture<?> retryFuture;

    // Change listeners
    private final List<ListenerEntry> listeners = Collections.synchronizedList(new ArrayList<>());

    // Context provider
    private volatile Supplier<List<Context>> contextProvider;

    // Metrics reporter (optional)
    private volatile com.smplkit.MetricsReporter metrics;

    // Service name from parent SmplClient (for auto-injection)
    private volatile String parentService;

    // Shared WebSocket reference (set by SmplClient; lazily created when standalone)
    private volatile SharedWebSocket sharedWs;
    private volatile boolean ownsWs = false;
    private final Consumer<Map<String, Object>> flagChangedHandler;
    private final Consumer<Map<String, Object>> flagDeletedHandler;
    private final Consumer<Map<String, Object>> flagsChangedHandler;

    /**
     * Wired constructor — invoked by {@link com.smplkit.SmplClient} so the flags
     * surface shares the parent client's connection pool and shared WebSocket.
     * The resulting client does NOT own its transport, so {@link #close()} tears
     * down nothing.
     *
     * @param flagsApi     pre-built generated flags API bound to the parent's transport
     * @param contextsApi  pre-built generated app contexts API for evaluation-context registration
     * @param httpClient   the parent's shared JDK HttpClient
     * @param apiKey       resolved API key
     * @param flagsBaseUrl fully-qualified flags service base URL
     * @param appBaseUrl   fully-qualified app service base URL (event gateway)
     * @param timeout      per-request read timeout
     */
    public FlagsClient(FlagsApi flagsApi, ContextsApi contextsApi,
                       HttpClient httpClient, String apiKey,
                       String flagsBaseUrl, String appBaseUrl, Duration timeout) {
        this(flagsApi, contextsApi, httpClient, apiKey, flagsBaseUrl, appBaseUrl, timeout, false);
    }

    private FlagsClient(FlagsApi flagsApi, ContextsApi contextsApi,
                        HttpClient httpClient, String apiKey,
                        String flagsBaseUrl, String appBaseUrl, Duration timeout,
                        boolean ownsTransport) {
        this.flagsApi = flagsApi;
        this.contextsApi = contextsApi;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.flagsBaseUrl = flagsBaseUrl;
        this.appBaseUrl = appBaseUrl;
        this.timeout = timeout;
        this.ownsTransport = ownsTransport;

        this.contextFlushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smplkit-flags-ctx-flush");
            t.setDaemon(true);
            return t;
        });

        this.flagChangedHandler = this::handleFlagChanged;
        this.flagDeletedHandler = this::handleFlagDeleted;
        this.flagsChangedHandler = this::handleFlagsChanged;
    }

    /** Package-private test constructor. */
    FlagsClient() {
        this.flagsApi = null;
        this.contextsApi = null;
        this.httpClient = null;
        this.apiKey = null;
        this.flagsBaseUrl = null;
        this.appBaseUrl = null;
        this.timeout = null;
        this.ownsTransport = false;
        this.contextFlushExecutor = null;
        this.flagChangedHandler = this::handleFlagChanged;
        this.flagDeletedHandler = this::handleFlagDeleted;
        this.flagsChangedHandler = this::handleFlagsChanged;
    }

    // -----------------------------------------------------------------------
    // Standalone construction (mirrors SmplClient.create / builder())
    // -----------------------------------------------------------------------

    /**
     * Construct a standalone {@link FlagsClient}, resolving credentials from the
     * standard sources (env vars, {@code ~/.smplkit}). Owns its own transport.
     */
    public static FlagsClient create() {
        return builder().build();
    }

    /** Construct a standalone {@link FlagsClient} with the given API key. */
    public static FlagsClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** Returns a builder for a standalone {@link FlagsClient}. */
    public static FlagsClientBuilder builder() {
        return new FlagsClientBuilder();
    }

    /** Internal: build a standalone client from already-resolved config. */
    static FlagsClient fromResolved(ResolvedClientConfig cfg, String environment, String baseUrl,
                                    Duration timeout, Map<String, String> extraHeaders) {
        // base_url is used directly when supplied (the path a top-level client
        // takes after it has already resolved it); otherwise it is derived from
        // the resolved scheme/base-domain.
        String flagsBaseUrl = baseUrl != null ? baseUrl
                : ConfigResolver.serviceUrl(cfg.scheme, "flags", cfg.baseDomain);
        String appBaseUrl = ConfigResolver.serviceUrl(cfg.scheme, "app", cfg.baseDomain);

        com.smplkit.internal.generated.flags.ApiClient flagsApiClient =
                new com.smplkit.internal.generated.flags.ApiClient();
        flagsApiClient.setHttpClientBuilder(HttpClients.builder());
        flagsApiClient.updateBaseUri(flagsBaseUrl);
        flagsApiClient.setRequestInterceptor(HttpClients.compositeInterceptor(cfg.apiKey, extraHeaders));
        flagsApiClient.setReadTimeout(timeout);
        FlagsApi flagsApi = new FlagsApi(flagsApiClient);

        com.smplkit.internal.generated.app.ApiClient appApiClient =
                new com.smplkit.internal.generated.app.ApiClient();
        appApiClient.setHttpClientBuilder(HttpClients.builder());
        appApiClient.updateBaseUri(appBaseUrl);
        appApiClient.setRequestInterceptor(HttpClients.compositeInterceptor(cfg.apiKey, extraHeaders));
        appApiClient.setReadTimeout(timeout);
        ContextsApi contextsApi = new ContextsApi(appApiClient);

        HttpClient httpClient = HttpClients.http11(timeout);
        FlagsClient client = new FlagsClient(flagsApi, contextsApi, httpClient, cfg.apiKey,
                flagsBaseUrl, appBaseUrl, timeout, true);
        client.environment = environment;
        return client;
    }

    // -----------------------------------------------------------------------
    // Wiring setters (used by SmplClient)
    // -----------------------------------------------------------------------

    /** Internal — wires the parent's metrics reporter. Used by {@link com.smplkit.SmplClient}; not for direct use. */
    public void setMetrics(com.smplkit.MetricsReporter metrics) {
        this.metrics = metrics;
    }

    /** Internal — wires the parent's shared WebSocket for the live channel. Used by {@link com.smplkit.SmplClient}; not for direct use. */
    public void setSharedWs(SharedWebSocket ws) {
        this.sharedWs = ws;
    }

    /** Internal — wires the shared evaluation-context registration buffer. Used by {@link com.smplkit.SmplClient}; not for direct use. */
    public void setContextBuffer(ContextRegistrationBuffer buffer) {
        this.sharedContextBuffer = buffer;
    }

    /** Internal — wires the parent's service name for context auto-injection. Used by {@link com.smplkit.SmplClient}; not for direct use. */
    public void setParentService(String service) {
        this.parentService = service;
    }

    /** Internal — wires the deployment environment used to resolve runtime flag values. Used by {@link com.smplkit.SmplClient}; not for direct use. */
    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /** Internal — wires the ambient evaluation-context provider. Used by {@link com.smplkit.SmplClient}; not for direct use. */
    public void setContextProvider(Supplier<List<Context>> provider) {
        this.contextProvider = provider;
    }

    private volatile Runnable ensureStartedHook;

    /** Sets the parent's deferred-start hook, run once when the live surface first connects (wired path). */
    public void setEnsureStarted(Runnable hook) {
        this.ensureStartedHook = hook;
    }

    // -----------------------------------------------------------------------
    // Management surface: CRUD (no live connection)
    // -----------------------------------------------------------------------

    /**
     * Return a new unsaved boolean {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<Boolean> newBooleanFlag(String id, boolean defaultValue) {
        return newBooleanFlag(id, defaultValue, null, null);
    }

    /**
     * Return a new unsaved boolean {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<Boolean> newBooleanFlag(String id, boolean defaultValue, String name, String description) {
        return new Flag<>(this, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                "BOOLEAN", defaultValue,
                List.of(Map.of("name", "True", "value", true), Map.of("name", "False", "value", false)),
                description, null, null, null, Boolean.class);
    }

    /**
     * Return a new unsaved string {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<String> newStringFlag(String id, String defaultValue) {
        return newStringFlag(id, defaultValue, null, null);
    }

    /**
     * Return a new unsaved string {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<String> newStringFlag(String id, String defaultValue, String name, String description) {
        return newStringFlag(id, defaultValue, name, description, null);
    }

    /**
     * Return a new unsaved string {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @param values       optional list of allowed values constraining what the flag may
     *                     serve; when omitted, the flag is unconstrained
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<String> newStringFlag(String id, String defaultValue, String name, String description,
                                      List<Map<String, Object>> values) {
        return new Flag<>(this, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                "STRING", defaultValue, values, description, null, null, null, String.class);
    }

    /**
     * Return a new unsaved numeric {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<Number> newNumberFlag(String id, Number defaultValue) {
        return newNumberFlag(id, defaultValue, null, null);
    }

    /**
     * Return a new unsaved numeric {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<Number> newNumberFlag(String id, Number defaultValue, String name, String description) {
        return newNumberFlag(id, defaultValue, name, description, null);
    }

    /**
     * Return a new unsaved numeric {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @param values       optional list of allowed values constraining what the flag may
     *                     serve; when omitted, the flag is unconstrained
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<Number> newNumberFlag(String id, Number defaultValue, String name, String description,
                                      List<Map<String, Object>> values) {
        return new Flag<>(this, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                "NUMERIC", defaultValue, values, description, null, null, null, Number.class);
    }

    /**
     * Return a new unsaved JSON {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<Object> newJsonFlag(String id, Object defaultValue) {
        return newJsonFlag(id, defaultValue, null, null);
    }

    /**
     * Return a new unsaved JSON {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<Object> newJsonFlag(String id, Object defaultValue, String name, String description) {
        return newJsonFlag(id, defaultValue, name, description, null);
    }

    /**
     * Return a new unsaved JSON {@link Flag}. Call {@code save()} to persist.
     *
     * @param id           stable flag identifier, unique per account
     * @param defaultValue value served when no environment override or rule applies
     * @param name         human-readable display name; when {@code null}, defaults to a
     *                     title-cased form of {@code id}
     * @param description  optional free-text description of the flag
     * @param values       optional list of allowed values constraining what the flag may
     *                     serve; when omitted, the flag is unconstrained
     * @return an unsaved {@link Flag}; call {@code save()} to persist it
     */
    public Flag<Object> newJsonFlag(String id, Object defaultValue, String name, String description,
                                    List<Map<String, Object>> values) {
        return new Flag<>(this, id,
                name != null ? name : Helpers.keyToDisplayName(id),
                "JSON", defaultValue, values, description, null, null, null, Object.class);
    }

    /**
     * Fetch the editable {@link Flag} resource by id.
     *
     * @param id identifier of the flag to fetch
     * @return the {@link Flag}, ready to mutate and {@code save()}
     * @throws com.smplkit.errors.NotFoundError no flag with that id exists for the account
     */
    public Flag<?> get(String id) {
        try {
            FlagResponse response = flagsApi.getFlag(id);
            return parseSingleResponse(response);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * List flags for the authenticated account.
     *
     * @return the flags on the first server-default page as a list of {@link Flag}
     */
    public List<Flag<?>> list() {
        return list(null, null);
    }

    /**
     * List a single page of flags. Pass {@code null} for either argument to use the
     * server default ({@code page[number]=1}, {@code page[size]=1000}). The wrapper
     * does not loop — customers paginate by calling this method with successive
     * {@code pageNumber} values.
     *
     * @param pageNumber 1-based page index to fetch; when {@code null}, the server
     *                   default applies
     * @param pageSize   number of flags per page; when {@code null}, the server default
     *                   applies
     * @return the flags on the requested page as a list of {@link Flag}
     */
    public List<Flag<?>> list(Integer pageNumber, Integer pageSize) {
        try {
            // Positional args: filterType, filterManaged, filterReferencesContext,
            // filterReferencesContextType, filterSearch, sort, pageNumber,
            // pageSize, metaTotal.
            FlagListResponse response = flagsApi.listFlags(
                    null, null, null, null, null, null, pageNumber, pageSize, null);
            return parseListResponse(response);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /**
     * Delete a flag by id.
     *
     * @param id identifier of the flag to delete
     * @throws com.smplkit.errors.NotFoundError no flag with that id exists for the account
     */
    public void delete(String id) {
        try {
            flagsApi.deleteFlag(id);
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Management surface: create/update flags (called by Flag.save())
    // -----------------------------------------------------------------------

    /** Creates a new flag on the server. Called by {@link Flag#save()}. */
    @SuppressWarnings("unchecked")
    <T> Flag<T> _createFlag(Flag<T> flag) {
        try {
            var attrs = new com.smplkit.internal.generated.flags.model.Flag();
            attrs.setName(flag.getName());
            attrs.setType(com.smplkit.internal.generated.flags.model.Flag.TypeEnum.fromValue(flag.getType()));
            attrs.setDefault(flag.getDefault());
            if (flag.getDescription() != null) {
                attrs.setDescription(flag.getDescription());
            }
            if (flag.getValues() != null) {
                List<FlagValue> fvs = new ArrayList<>();
                for (Map<String, Object> v : flag.getValues()) {
                    FlagValue fv = new FlagValue();
                    fv.setName((String) v.get("name"));
                    fv.setValue(v.get("value"));
                    fvs.add(fv);
                }
                attrs.setValues(fvs);
            } else {
                attrs.setValues(null);
            }
            if (flag.getEnvironments() != null && !flag.getEnvironments().isEmpty()) {
                attrs.setEnvironments(buildEnvironments(flag.getEnvironments()));
            }

            // Create uses a dedicated envelope where the caller-supplied id is required.
            FlagCreateResource data = new FlagCreateResource()
                    .id(flag.getId())
                    .type(FlagCreateResource.TypeEnum.FLAG)
                    .attributes(attrs);
            FlagCreateRequest body = new FlagCreateRequest().data(data);
            FlagResponse response = flagsApi.createFlag(body);
            Flag<?> result = parseSingleResponse(response);
            return (Flag<T>) result;
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    /** Updates an existing flag on the server. Called by {@link Flag#save()}. */
    @SuppressWarnings("unchecked")
    <T> Flag<T> _updateFlag(Flag<T> flag) {
        try {
            var attrs = new com.smplkit.internal.generated.flags.model.Flag();
            attrs.setName(flag.getName());
            attrs.setType(com.smplkit.internal.generated.flags.model.Flag.TypeEnum.fromValue(flag.getType()));
            attrs.setDefault(flag.getDefault());
            if (flag.getDescription() != null) {
                attrs.setDescription(flag.getDescription());
            }
            if (flag.getValues() != null) {
                List<FlagValue> fvs = new ArrayList<>();
                for (Map<String, Object> v : flag.getValues()) {
                    FlagValue fv = new FlagValue();
                    fv.setName((String) v.get("name"));
                    fv.setValue(v.get("value"));
                    fvs.add(fv);
                }
                attrs.setValues(fvs);
            } else {
                attrs.setValues(null);
            }
            if (flag.getEnvironments() != null) {
                attrs.setEnvironments(buildEnvironments(flag.getEnvironments()));
            }

            FlagResource data = new FlagResource().id(flag.getId()).type(FlagResource.TypeEnum.FLAG).attributes(attrs);
            FlagRequest body = new FlagRequest().data(data);
            FlagResponse response = flagsApi.updateFlag(flag.getId(), body);
            Flag<?> result = parseSingleResponse(response);
            return (Flag<T>) result;
        } catch (ApiException e) {
            throw mapException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Management surface: discovery buffer (owned directly)
    // -----------------------------------------------------------------------

    /**
     * Buffer a flag declaration for bulk-discovery upload.
     *
     * <p>The declaration stays buffered and is sent on the next flush — automatic once
     * the buffer reaches its batch size, or on the first live call.</p>
     *
     * @param item the {@link FlagDeclaration} to queue
     */
    public void register(FlagDeclaration item) {
        register(List.of(item), false);
    }

    /**
     * Buffer a flag declaration for bulk-discovery upload; optionally flush now.
     *
     * @param item  the {@link FlagDeclaration} to queue
     * @param flush when {@code true}, send the buffered declarations immediately via
     *              {@link #flush()} before returning; when {@code false}, they stay
     *              buffered and are sent on the next flush — automatic once the buffer
     *              reaches its batch size, or on the first live call
     */
    public void register(FlagDeclaration item, boolean flush) {
        register(List.of(item), flush);
    }

    /**
     * Buffer flag declarations for bulk-discovery upload.
     *
     * <p>The declarations stay buffered and are sent on the next flush — automatic once
     * the buffer reaches its batch size, or on the first live call.</p>
     *
     * @param items the {@link FlagDeclaration} list to queue
     */
    public void register(List<FlagDeclaration> items) {
        register(items, false);
    }

    /**
     * Buffer flag declarations for bulk-discovery upload; optionally flush now.
     *
     * @param items the {@link FlagDeclaration} list to queue
     * @param flush when {@code true}, send the buffered declarations immediately via
     *              {@link #flush()} before returning; when {@code false}, they stay
     *              buffered and are sent on the next flush — automatic once the buffer
     *              reaches its batch size, or on the first live call
     */
    public void register(List<FlagDeclaration> items, boolean flush) {
        for (FlagDeclaration d : items) {
            flagBuffer.add(d.id(), d.type(), d.defaultValue(), d.service(), d.environment());
        }
        if (flush) {
            flush();
            return;
        }
        if (flagBuffer.pendingCount() >= FLAG_BATCH_FLUSH_SIZE) {
            Thread t = new Thread(this::thresholdFlush, "smplkit-flag-flush-eager");
            t.setDaemon(true);
            t.start();
        }
    }

    private void thresholdFlush() {
        try {
            flushOrThrow();
        } catch (Exception exc) {
            LOG.warning("Flag registration flush failed: " + exc);
        }
    }

    /**
     * POST pending declarations to the flags bulk endpoint.
     *
     * <p>Items remain in the buffer until the request succeeds, so a flush
     * against an unhealthy {@code flags} service is automatically retried by
     * the next {@code flush()} call (periodic background flush, install retry,
     * or final flush on close).</p>
     */
    public void flush() {
        flushOrThrow();
    }

    /** Synchronous flush — alias of {@link #flush} for the periodic-flush path. */
    public void flushSync() {
        flush();
    }

    /**
     * Number of pending flag declarations awaiting flush.
     *
     * @return the count of buffered flag declarations not yet sent
     */
    public int pendingCount() {
        return flagBuffer.pendingCount();
    }

    /** Queue a declared flag with the owned discovery buffer. */
    private void observeDeclaration(String flagId, String flagType, Object defaultValue) {
        register(new FlagDeclaration(flagId, flagType, defaultValue, parentService, environment));
    }

    // -----------------------------------------------------------------------
    // Live surface: typed flag handles
    // -----------------------------------------------------------------------

    /**
     * Declare a boolean flag handle for live evaluation. Connects lazily on first use.
     *
     * @param id           identifier of the flag to evaluate
     * @param defaultValue value returned by {@code handle.get()} when the flag is unknown
     *                     or no environment override or rule applies
     * @return a {@link Flag} handle whose {@code get()} evaluates against the live cache
     */
    public Flag<Boolean> booleanFlag(String id, boolean defaultValue) {
        ensureConnected();
        Flag<Boolean> handle = new Flag<>(this, id, id, "BOOLEAN", defaultValue,
                null, null, null, null, null, Boolean.class);
        handles.put(id, handle);
        observeDeclaration(id, "BOOLEAN", defaultValue);
        return handle;
    }

    /**
     * Declare a string flag handle for live evaluation. Connects lazily on first use.
     *
     * @param id           identifier of the flag to evaluate
     * @param defaultValue value returned by {@code handle.get()} when the flag is unknown
     *                     or no environment override or rule applies
     * @return a {@link Flag} handle whose {@code get()} evaluates against the live cache
     */
    public Flag<String> stringFlag(String id, String defaultValue) {
        ensureConnected();
        Flag<String> handle = new Flag<>(this, id, id, "STRING", defaultValue,
                null, null, null, null, null, String.class);
        handles.put(id, handle);
        observeDeclaration(id, "STRING", defaultValue);
        return handle;
    }

    /**
     * Declare a numeric flag handle for live evaluation. Connects lazily on first use.
     *
     * @param id           identifier of the flag to evaluate
     * @param defaultValue value returned by {@code handle.get()} when the flag is unknown
     *                     or no environment override or rule applies
     * @return a {@link Flag} handle whose {@code get()} evaluates against the live cache
     */
    public Flag<Number> numberFlag(String id, Number defaultValue) {
        ensureConnected();
        Flag<Number> handle = new Flag<>(this, id, id, "NUMERIC", defaultValue,
                null, null, null, null, null, Number.class);
        handles.put(id, handle);
        observeDeclaration(id, "NUMERIC", defaultValue);
        return handle;
    }

    /**
     * Declare a JSON flag handle for live evaluation. Connects lazily on first use.
     *
     * @param id           identifier of the flag to evaluate
     * @param defaultValue value returned by {@code handle.get()} when the flag is unknown
     *                     or no environment override or rule applies
     * @return a {@link Flag} handle whose {@code get()} evaluates against the live cache
     */
    public Flag<Object> jsonFlag(String id, Object defaultValue) {
        ensureConnected();
        Flag<Object> handle = new Flag<>(this, id, id, "JSON", defaultValue,
                null, null, null, null, null, Object.class);
        handles.put(id, handle);
        observeDeclaration(id, "JSON", defaultValue);
        return handle;
    }

    // -----------------------------------------------------------------------
    // Live surface: lazy connect
    // -----------------------------------------------------------------------

    /**
     * Open the live connection to the running Smpl Flags service.
     *
     * <p>Flushes any buffered discovery declarations, fetches all flag
     * definitions into the local cache, opens the shared WebSocket, and
     * subscribes to {@code flag_changed} / {@code flag_deleted} / {@code flags_changed}
     * events.</p>
     *
     * <p>Idempotent and internal — every live method calls it on first use, so
     * the live surface auto-connects with no explicit step.</p>
     */
    void ensureConnected() {
        Runnable h = this.ensureStartedHook;
        if (h != null) {
            h.run();
        }
        if (connected) return;
        synchronized (connectLock) {
            if (connected) return;
            if (retryScheduled) return;
            Debug.log("websocket", "flags runtime initializing");

            // Flush discovered flags BEFORE fetching definitions so the fetch
            // reflects them. Items stay in the buffer until the POST succeeds.
            // Flush + refresh are a transaction: only mark connected after both succeed.
            if (!flushFlags()) {
                scheduleConnectRetry();
                return;
            }

            try {
                fetchAllFlags();
            } catch (Exception e) {
                Debug.log("websocket", "flags runtime fetchAllFlags failed: " + e);
                scheduleConnectRetry();
                return;
            }
            resolutionCache.clear();

            SharedWebSocket ws = ensureWs();
            if (ws != null && !wsHandlersRegistered) {
                Debug.log("registration", "registering flag_changed, flag_deleted, and flags_changed handlers");
                ws.on("flag_changed", flagChangedHandler);
                ws.on("flag_deleted", flagDeletedHandler);
                ws.on("flags_changed", flagsChangedHandler);
                ws.ensureConnected(Duration.ofSeconds(10));
                wsHandlersRegistered = true;
            }

            connected = true;
            backoffSeconds = 1;
            Debug.log("websocket", "flags runtime connected");

            if (contextFlushExecutor != null && flagFlushFuture == null) {
                flagFlushFuture = contextFlushExecutor.scheduleAtFixedRate(
                        this::flushFlagsSafe, 30, 30, TimeUnit.SECONDS);
            }

            if (contextFlushExecutor != null && contextFlushFuture == null) {
                contextFlushFuture = contextFlushExecutor.scheduleAtFixedRate(
                        this::flushContextsSafe, 30, 30, TimeUnit.SECONDS);
            }
        }
    }

    /** Return the shared WebSocket — the parent's when wired, else our own. */
    private SharedWebSocket ensureWs() {
        SharedWebSocket ws = this.sharedWs;
        if (ws != null) return ws;
        if (appBaseUrl == null || httpClient == null) return null;
        synchronized (connectLock) {
            if (this.sharedWs == null) {
                SharedWebSocket created = new SharedWebSocket(httpClient, appBaseUrl, apiKey, metrics);
                created.start();
                this.sharedWs = created;
                this.ownsWs = true;
            }
            return this.sharedWs;
        }
    }

    private void scheduleConnectRetry() {
        long delay = backoffSeconds;
        backoffSeconds = Math.min(backoffSeconds * 2, 60);
        retryScheduled = true;
        LOG.warning("Flags runtime init failed, retrying in " + delay + "s");
        if (contextFlushExecutor != null) {
            retryFuture = contextFlushExecutor.schedule(() -> {
                retryScheduled = false;
                retryFuture = null;
                ensureConnected();
            }, delay, TimeUnit.SECONDS);
        }
    }

    // -----------------------------------------------------------------------
    // Live surface: refresh / stats / change listeners
    // -----------------------------------------------------------------------

    /**
     * Re-fetch all flag definitions and clear cache.
     *
     * <p>Connects lazily on first use — no explicit install step.</p>
     */
    public void refresh() {
        ensureConnected();
        Map<String, Map<String, Object>> preStore = new HashMap<>(flagStore);
        fetchAllFlags();
        resolutionCache.clear();

        // Fire per-key listeners for keys whose content changed
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(preStore.keySet());
        allKeys.addAll(flagStore.keySet());
        boolean anyChanged = false;
        for (String key : allKeys) {
            if (!Objects.equals(preStore.get(key), flagStore.get(key))) {
                anyChanged = true;
                fireListenersForKey(key, new FlagChangeEvent(key, "manual"));
            }
        }
        if (anyChanged) {
            String firstKey = allKeys.stream()
                    .filter(k -> !Objects.equals(preStore.get(k), flagStore.get(k)))
                    .findFirst().orElse("");
            fireGlobalListeners(new FlagChangeEvent(firstKey, "manual"));
        }
    }

    /** Return evaluation statistics. Connects lazily on first use. */
    public FlagStats stats() {
        ensureConnected();
        return new FlagStats(cacheHits.get(), cacheMisses.get());
    }

    /**
     * Register a change listener that fires when any flag changes (global listener).
     *
     * <p>Connects lazily on first use — no explicit install step.</p>
     *
     * @param listener the callback invoked with a {@link FlagChangeEvent} on every change
     */
    public void onChange(Consumer<FlagChangeEvent> listener) {
        ensureConnected();
        listeners.add(new ListenerEntry(null, listener));
    }

    /**
     * Register a change listener that fires when the specified flag changes
     * (id-scoped listener).
     *
     * <p>Connects lazily on first use — no explicit install step.</p>
     *
     * @param id       identifier of the flag whose changes the listener is scoped to
     * @param listener the callback invoked with a {@link FlagChangeEvent} when that flag changes
     */
    public void onChange(String id, Consumer<FlagChangeEvent> listener) {
        ensureConnected();
        listeners.add(new ListenerEntry(id, listener));
    }

    // -----------------------------------------------------------------------
    // Internal: evaluation engine
    // -----------------------------------------------------------------------

    /**
     * Core evaluation used by flag handles (the {@code .get()} path).
     *
     * <p>Connects lazily on first use so {@code flag.get()} works without an
     * explicit install step.</p>
     */
    @SuppressWarnings("unchecked")
    Object _evaluateHandle(String id, Object defaultValue, List<Context> contexts) {
        if (!connected) {
            ensureConnected();
        }

        Map<String, Object> flagData = flagStore.get(id);

        List<Context> ctxList;
        if (contexts != null) {
            ctxList = contexts;
        } else if (contextProvider != null) {
            ctxList = contextProvider.get();
        } else {
            ctxList = List.of();
        }

        for (Context ctx : ctxList) {
            observeContext(ctx);
        }

        if (pendingContexts.size() >= CONTEXT_BATCH_FLUSH_SIZE) {
            Thread flushThread = new Thread(this::flushContextsSafe, "smplkit-flags-ctx-flush-eager");
            flushThread.setDaemon(true);
            flushThread.start();
        }

        if (flagData == null) return defaultValue;

        Map<String, Object> evalData = buildEvalData(ctxList);

        String cacheKey = id + ":" + hashContexts(ctxList);
        Object cached = resolutionCache.get(cacheKey);
        if (cached != null) {
            cacheHits.incrementAndGet();
            if (metrics != null) {
                Map<String, String> dims = Map.of("flag", id);
                metrics.record("flags.cache_hits", "hits");
                metrics.record("flags.evaluations", "evaluations", dims);
            }
            return cached == CACHE_NULL_SENTINEL ? defaultValue : cached;
        }
        cacheMisses.incrementAndGet();
        if (metrics != null) {
            Map<String, String> dims = Map.of("flag", id);
            metrics.record("flags.cache_misses", "misses");
            metrics.record("flags.evaluations", "evaluations", dims);
        }

        Object result = evaluateFlag(id, flagData, environment, evalData);
        if (result == null) {
            resolutionCache.put(cacheKey, CACHE_NULL_SENTINEL);
            return defaultValue;
        }
        resolutionCache.put(cacheKey, result);
        return result;
    }

    /**
     * Evaluate a flag definition against the given context.
     *
     * <ol>
     *   <li>Look up the environment. If missing, return flag-level default.</li>
     *   <li>If disabled, return env default or flag default.</li>
     *   <li>Iterate rules; first match wins.</li>
     *   <li>No match → env default or flag default.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private Object evaluateFlag(String key, Map<String, Object> flagData,
                                String env, Map<String, Object> evalData) {
        Object flagDefault = flagData.get("default");
        Map<String, Object> environments = (Map<String, Object>) flagData.get("environments");
        if (environments == null || !environments.containsKey(env)) {
            return flagDefault;
        }

        Map<String, Object> envData = (Map<String, Object>) environments.get(env);
        Object fallback = envData.get("default") != null ? envData.get("default") : flagDefault;

        Boolean enabled = (Boolean) envData.get("enabled");
        if (enabled == null || !enabled) {
            return fallback;
        }

        List<Map<String, Object>> rules = (List<Map<String, Object>>) envData.get("rules");
        if (rules != null) {
            for (Map<String, Object> rule : rules) {
                Map<String, Object> logic = (Map<String, Object>) rule.get("logic");
                if (logic == null || logic.isEmpty()) continue;
                try {
                    String logicJson = OBJECT_MAPPER.writeValueAsString(logic);
                    Object result = JSON_LOGIC.apply(logicJson, evalData);
                    if (isTruthy(result)) {
                        return rule.get("value");
                    }
                } catch (Exception e) {
                    Debug.log("flags", "JSON Logic evaluation error for rule in flag " + key + ": " + e);
                }
            }
        }

        return fallback;
    }

    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) return !s.isEmpty();
        return true;
    }

    // -----------------------------------------------------------------------
    // Internal: data helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> buildEvalData(List<Context> contexts) {
        Map<String, Object> evalData = new HashMap<>();
        if (contexts != null) {
            for (Context ctx : contexts) {
                evalData.put(ctx.type(), ctx.toEvalDict());
            }
        }
        if (parentService != null && !evalData.containsKey("service")) {
            evalData.put("service", Map.of("key", parentService));
        }
        return evalData;
    }

    private String hashContexts(List<Context> contexts) {
        if (contexts == null || contexts.isEmpty()) return "empty";
        Map<String, Object> evalData = buildEvalData(contexts);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : new java.util.TreeMap<>(evalData).entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return String.valueOf(sb.toString().hashCode());
    }

    private void observeContext(Context ctx) {
        ContextRegistrationBuffer shared = this.sharedContextBuffer;
        if (shared != null) {
            shared.observe(ctx);
            return;
        }
        // Fallback: internal buffer (used when no shared buffer is wired)
        String compositeKey = ctx.type() + ":" + ctx.key();
        if (contextBuffer.containsKey(compositeKey)) return;
        Map<String, Object> entry = new HashMap<>();
        entry.put("type", ctx.type());
        entry.put("key", ctx.key());
        entry.put("attributes", ctx.attributes());
        contextBuffer.put(compositeKey, entry);
        pendingContexts.add(entry);
    }

    private List<Map<String, Object>> drainPendingContexts() {
        ContextRegistrationBuffer shared = this.sharedContextBuffer;
        if (shared != null) {
            return shared.drain();
        }
        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> item;
        while ((item = pendingContexts.poll()) != null) {
            batch.add(item);
        }
        return batch;
    }

    void flushContexts() {
        List<Map<String, Object>> batch = drainPendingContexts();
        if (batch.isEmpty()) return;
        try {
            List<ContextBulkItem> items = new ArrayList<>();
            for (Map<String, Object> entry : batch) {
                String type = (String) entry.get("type");
                String key = (String) entry.get("key");
                @SuppressWarnings("unchecked")
                Map<String, Object> attrs = (Map<String, Object>) entry.get("attributes");
                ContextBulkItem item = new ContextBulkItem()
                        .type(type)
                        .key(key)
                        .attributes(attrs);
                items.add(item);
            }
            ContextBulkRegister reqBody = new ContextBulkRegister().contexts(items);
            contextsApi.bulkRegisterContexts(reqBody);
        } catch (Exception e) {
            Debug.log("registration", "Context flush failed: " + e);
        }
    }

    private void flushContextsSafe() {
        flushContexts();
    }

    /**
     * Peeks the buffer, POSTs to the server, and commits only on success.
     * Returns true on success (used by the lazy-connect transaction and
     * the periodic/eager background flushes which must not throw).
     */
    boolean flushFlags() {
        List<FlagRegistrationEntry> batch = flagBuffer.peek();
        if (batch.isEmpty()) return true;
        try {
            FlagBulkRequest req = buildBulkRequest(batch);
            Set<String> ids = new HashSet<>();
            for (FlagRegistrationEntry entry : batch) {
                ids.add(entry.id());
            }
            flagsApi.bulkRegisterFlags(req);
            flagBuffer.commit(ids);
            return true;
        } catch (Exception e) {
            LOG.warning("Flag registration flush failed: " + e.getMessage());
            Debug.log("registration", "Flag registration flush failed: " + e);
            return false;
        }
    }

    /** POSTs pending declarations, raising the mapped SDK exception on failure. */
    private void flushOrThrow() {
        List<FlagRegistrationEntry> batch = flagBuffer.peek();
        if (batch.isEmpty()) return;
        FlagBulkRequest req = buildBulkRequest(batch);
        Set<String> ids = new HashSet<>();
        for (FlagRegistrationEntry entry : batch) {
            ids.add(entry.id());
        }
        try {
            flagsApi.bulkRegisterFlags(req);
        } catch (ApiException e) {
            throw mapException(e);
        }
        flagBuffer.commit(ids);
    }

    private static FlagBulkRequest buildBulkRequest(List<FlagRegistrationEntry> batch) {
        FlagBulkRequest req = new FlagBulkRequest();
        for (FlagRegistrationEntry entry : batch) {
            FlagBulkItem item = new FlagBulkItem();
            item.setId(entry.id());
            item.setType(FlagBulkItem.TypeEnum.fromValue(entry.type()));
            item.setDefault(entry.defaultValue());
            if (entry.service() != null) item.setService(entry.service());
            if (entry.environment() != null) item.setEnvironment(entry.environment());
            req.addFlagsItem(item);
        }
        return req;
    }

    private void flushFlagsSafe() {
        flushFlags();
    }

    private static final int RUNTIME_PAGE_SIZE = 1000;

    private void fetchAllFlags() {
        flagStore.clear();
        int page = 1;
        while (true) {
            List<Flag<?>> rows = list(page, RUNTIME_PAGE_SIZE);
            for (Flag<?> flag : rows) {
                flagStore.put(flag.getId(), flagToStoreEntry(flag));
            }
            if (rows.size() < RUNTIME_PAGE_SIZE) break;
            page++;
        }
    }

    private Map<String, Object> flagToStoreEntry(Flag<?> flag) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", flag.getId());
        entry.put("name", flag.getName());
        entry.put("type", flag.getType());
        entry.put("default", flag.getDefault());
        entry.put("values", flag.getValues());
        entry.put("description", flag.getDescription());
        entry.put("environments", flag.getEnvironments());
        return entry;
    }

    // -----------------------------------------------------------------------
    // Internal: WebSocket handlers
    // -----------------------------------------------------------------------

    private void handleFlagChanged(Map<String, Object> data) {
        if (!connected) return;
        String flagKey = data.get("id") instanceof String s ? s : null;
        if (flagKey == null) {
            Debug.log("websocket", "flag_changed event missing id, skipping");
            return;
        }
        Debug.log("websocket", "flag_changed event received, key=" + flagKey);

        // Snapshot pre-state for this key
        Map<String, Object> preFlagData = flagStore.get(flagKey);

        // Scoped fetch: GET /flags/{key}
        Flag<?> fetched;
        try {
            fetched = get(flagKey);
        } catch (Exception e) {
            Debug.log("websocket", "flag_changed scoped fetch failed for key=" + flagKey + ": " + e);
            return;
        }

        Map<String, Object> postFlagData = flagToStoreEntry(fetched);
        flagStore.put(flagKey, postFlagData);
        resolutionCache.clear();

        // Only fire if content actually changed
        if (Objects.equals(preFlagData, postFlagData)) {
            Debug.log("websocket", "flag_changed: content unchanged for key=" + flagKey + ", no listeners fired");
            return;
        }

        fireListenersForKey(flagKey, new FlagChangeEvent(flagKey, "websocket"));
        fireGlobalListeners(new FlagChangeEvent(flagKey, "websocket"));
    }

    private void handleFlagDeleted(Map<String, Object> data) {
        if (!connected) return;
        String flagKey = data.get("id") instanceof String s ? s : null;
        if (flagKey == null) {
            Debug.log("websocket", "flag_deleted event missing id, skipping");
            return;
        }
        Debug.log("websocket", "flag_deleted event received, key=" + flagKey);

        // Remove from local store — no HTTP fetch
        boolean existed = flagStore.containsKey(flagKey);
        flagStore.remove(flagKey);
        resolutionCache.clear();

        if (!existed) return;

        FlagChangeEvent event = new FlagChangeEvent(flagKey, "websocket", true);
        fireListenersForKey(flagKey, event);
        fireGlobalListeners(event);
    }

    private void handleFlagsChanged(Map<String, Object> data) {
        if (!connected) return;
        Debug.log("websocket", "flags_changed event received");

        // Snapshot pre-state
        Map<String, Map<String, Object>> preStore = new HashMap<>(flagStore);

        // Full list fetch
        try {
            fetchAllFlags();
        } catch (Exception e) {
            Debug.log("websocket", "Failed to refresh flags after flags_changed WS event: " + e);
            return;
        }
        resolutionCache.clear();

        // Diff pre vs post — fire per-key listener for each changed key, global once
        boolean anyChanged = false;
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(preStore.keySet());
        allKeys.addAll(flagStore.keySet());

        for (String key : allKeys) {
            Map<String, Object> pre = preStore.get(key);
            Map<String, Object> post = flagStore.get(key);
            if (!Objects.equals(pre, post)) {
                anyChanged = true;
                fireListenersForKey(key, new FlagChangeEvent(key, "websocket"));
            }
        }

        if (anyChanged) {
            // Pick any changed key for the global event (or use a sentinel)
            String firstKey = allKeys.stream()
                    .filter(k -> !Objects.equals(preStore.get(k), flagStore.get(k)))
                    .findFirst().orElse("");
            fireGlobalListeners(new FlagChangeEvent(firstKey, "websocket"));
        }
    }

    /** Fires per-key scoped listeners for the given flag key. */
    private void fireListenersForKey(String flagKey, FlagChangeEvent event) {
        for (ListenerEntry entry : listeners) {
            if (entry.id == null) continue; // global, skip here
            if (!entry.id.equals(flagKey)) continue;
            try {
                entry.listener.accept(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Exception in id-scoped flags onChange listener for flag '" + flagKey + "'", e);
            }
        }
    }

    /** Fires global (null-id) listeners with the given event. */
    private void fireGlobalListeners(FlagChangeEvent event) {
        for (ListenerEntry entry : listeners) {
            if (entry.id != null) continue; // scoped, skip
            try {
                entry.listener.accept(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Exception in global flags onChange listener", e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal: response parsing
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Flag<?> parseSingleResponse(FlagResponse response) {
        Map<String, Object> resp = OBJECT_MAPPER.convertValue(response, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        return parseFlagData(data);
    }

    @SuppressWarnings("unchecked")
    List<Flag<?>> parseListResponse(FlagListResponse response) {
        Map<String, Object> resp = OBJECT_MAPPER.convertValue(response, new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("data");
        if (items == null) return List.of();
        List<Flag<?>> result = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            result.add(parseFlagData(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Flag<?> parseFlagData(Map<String, Object> data) {
        String id = (String) data.get("id");
        Map<String, Object> attrs = (Map<String, Object>) data.get("attributes");
        if (attrs == null) attrs = data;

        String name = (String) attrs.get("name");
        String description = (String) attrs.get("description");
        String type = (String) attrs.get("type");
        Object defaultValue = attrs.get("default");
        List<Map<String, Object>> values = (List<Map<String, Object>>) attrs.get("values");
        Map<String, Object> environments = (Map<String, Object>) attrs.get("environments");
        Instant createdAt = parseInstant(attrs.get("created_at"));
        Instant updatedAt = parseInstant(attrs.get("updated_at"));

        Flag<Object> flag = new Flag<>(this,
                id != null ? id : "", name != null ? name : "",
                type != null ? type : "", defaultValue,
                values, description,
                environments, createdAt, updatedAt, Object.class);
        return flag;
    }

    static Instant parseInstant(Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FlagEnvironment> buildEnvironments(Map<String, Object> envs) {
        if (envs == null) return null;
        Map<String, FlagEnvironment> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : envs.entrySet()) {
            Map<String, Object> envData = (Map<String, Object>) entry.getValue();
            FlagEnvironment fe = new FlagEnvironment();
            if (envData.containsKey("enabled")) {
                fe.setEnabled((Boolean) envData.get("enabled"));
            }
            if (envData.containsKey("default")) {
                fe.setDefault(envData.get("default"));
            }
            if (envData.containsKey("rules")) {
                List<Map<String, Object>> rules = (List<Map<String, Object>>) envData.get("rules");
                List<FlagRule> flagRules = new ArrayList<>();
                for (Map<String, Object> r : rules) {
                    FlagRule fr = new FlagRule();
                    fr.setDescription((String) r.get("description"));
                    Map<String, Object> logic = (Map<String, Object>) r.get("logic");
                    fr.setLogic(logic != null ? logic : Map.of());
                    fr.setValue(r.get("value"));
                    flagRules.add(fr);
                }
                fe.setRules(flagRules);
            }
            result.put(entry.getKey(), fe);
        }
        return result;
    }

    static SmplError mapException(ApiException e) {
        if (e.getCode() == 0) {
            return ApiExceptionHandler.mapApiException(e);
        }
        return ApiExceptionHandler.mapApiException(e.getCode(), e.getResponseBody());
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Release resources — only those this client owns.
     *
     * <p>Tears down the owned WebSocket (standalone install) and the owned
     * flags + app HTTP transports (standalone construction). A wired client
     * borrows the parent's transport, WebSocket, and contexts client and
     * closes none of them.</p>
     */
    @Override
    public void close() {
        if (contextFlushExecutor != null) {
            contextFlushExecutor.shutdownNow();
        }
        if (ownsWs && sharedWs != null) {
            sharedWs.close();
            sharedWs = null;
            ownsWs = false;
        }
        if (ownsTransport) {
            // No persistent resources beyond the owned HttpClient (managed by JDK).
        }
    }

    // -----------------------------------------------------------------------
    // Package-private test helpers
    // -----------------------------------------------------------------------

    /** Simulates a flag change event (for testing). */
    void simulateFlagChanged() {
        handleFlagChanged(Map.of());
    }

    /** Simulates a flag change event for a specific key (for testing). */
    void simulateFlagChanged(String key) {
        handleFlagChanged(Map.of("id", key));
    }

    /** Simulates a flag deletion event (for testing). */
    void simulateFlagDeleted() {
        handleFlagDeleted(Map.of());
    }

    /** Simulates a flag deletion event for a specific key (for testing). */
    void simulateFlagDeleted(String key) {
        handleFlagDeleted(Map.of("id", key));
    }

    /** Simulates a flags_changed (bulk) event (for testing). */
    void simulateFlagsChanged() {
        handleFlagsChanged(Map.of());
    }

    boolean isConnected() {
        return connected;
    }

    boolean isRetryScheduled() {
        return retryScheduled;
    }

    /** Resets runtime state (for testing). */
    void disconnect() {
        connected = false;
        retryScheduled = false;
        wsHandlersRegistered = false;
        backoffSeconds = 1;
        if (retryFuture != null) {
            retryFuture.cancel(false);
            retryFuture = null;
        }
        if (flagFlushFuture != null) {
            flagFlushFuture.cancel(false);
            flagFlushFuture = null;
        }
        if (contextFlushFuture != null) {
            contextFlushFuture.cancel(false);
            contextFlushFuture = null;
        }
        SharedWebSocket ws = this.sharedWs;
        if (ws != null) {
            ws.off("flag_changed", flagChangedHandler);
            ws.off("flag_deleted", flagDeletedHandler);
            ws.off("flags_changed", flagsChangedHandler);
        }
        flagStore.clear();
        resolutionCache.clear();
    }

    private record ListenerEntry(String id, Consumer<FlagChangeEvent> listener) {}

    // -----------------------------------------------------------------------
    // Inner: flag registration buffer
    // -----------------------------------------------------------------------

    record FlagRegistrationEntry(String id, String type, Object defaultValue,
                                         String service, String environment) {}

    static final class FlagRegistrationBuffer {
        private final Set<String> seen = new HashSet<>();
        private final List<FlagRegistrationEntry> pending = new ArrayList<>();
        private final Object lock = new Object();

        void add(String id, String type, Object defaultValue, String service, String environment) {
            synchronized (lock) {
                if (seen.add(id)) {
                    pending.add(new FlagRegistrationEntry(id, type, defaultValue, service, environment));
                }
            }
        }

        /** Returns a snapshot of pending entries without removing them. */
        List<FlagRegistrationEntry> peek() {
            synchronized (lock) {
                return new ArrayList<>(pending);
            }
        }

        /** Removes entries with the given ids after a successful POST. The seen-set is kept intact. */
        void commit(Set<String> ids) {
            synchronized (lock) {
                pending.removeIf(e -> ids.contains(e.id()));
            }
        }

        /** Unconditional drain for teardown / tests. */
        List<FlagRegistrationEntry> drain() {
            synchronized (lock) {
                List<FlagRegistrationEntry> batch = new ArrayList<>(pending);
                pending.clear();
                return batch;
            }
        }

        int pendingCount() {
            synchronized (lock) {
                return pending.size();
            }
        }
    }
}
