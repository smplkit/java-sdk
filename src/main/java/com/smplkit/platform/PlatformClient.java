package com.smplkit.platform;

import com.smplkit.internal.ContextRegistrationBuffer;
import com.smplkit.internal.generated.app.ApiClient;

import org.jetbrains.annotations.ApiStatus;

/**
 * The Smpl Platform client (sync).
 *
 * <p>Groups the account-wide CRUD resources that aren't owned by a single
 * product, reachable as {@code client.platform} ({@link com.smplkit.SmplClient})
 * or constructed directly:</p>
 *
 * <pre>{@code
 * try (PlatformClient platform = PlatformClient.create("sk_...")) {
 *     Environment prod = platform.environments.new_("production", "Production", null, null);
 *     prod.save();
 *     for (Service svc : platform.services.list()) {
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>Sub-clients: {@code environments}, {@code services}, {@code contexts},
 * {@code contextTypes}. Pure CRUD — no {@code install()} required.</p>
 *
 * <p>Every sub-client speaks to the app service, so the client needs exactly
 * one app transport (plus the context-registration buffer that {@code contexts}
 * drains). The client supports two construction shapes:</p>
 *
 * <ul>
 *   <li><strong>Wired</strong> into {@link com.smplkit.SmplClient} — borrows the
 *   parent's app transport and an externally-supplied context buffer. This is the
 *   common path; {@code client.flags} borrows {@code client.platform.contexts} as
 *   its evaluation-context registration seam.</li>
 *   <li><strong>Standalone</strong> — {@code PlatformClient.create(apiKey)} /
 *   {@link #builder()} builds and owns its own app transport and buffer.
 *   {@link #close()} tears down only the owned transport.</li>
 * </ul>
 */
public final class PlatformClient implements AutoCloseable {

    /** Environment CRUD ({@code platform.environments}). */
    public final EnvironmentsClient environments;
    /** Service CRUD ({@code platform.services}). */
    public final ServicesClient services;
    /** Context registration + read/delete ({@code platform.contexts}). */
    public final ContextsClient contexts;
    /** Context-type CRUD ({@code platform.contextTypes}). */
    public final ContextTypesClient contextTypes;

    /** Whether this client owns its app transport (standalone) or borrows it (wired). */
    private final boolean ownsTransport;

    /**
     * Internal factory used by {@link com.smplkit.SmplClient} to wire a
     * {@code PlatformClient} into a parent client: it borrows the parent's app
     * transport and shares the parent's context-registration buffer, and closes
     * nothing of its own.
     *
     * <p>Not part of the public API — customers construct a {@code PlatformClient}
     * via {@link #create()}, {@link #create(String)}, or {@link #builder()}.</p>
     *
     * @param appApiClient the parent's app-service transport to borrow
     * @param contextBuffer the shared context-registration buffer to drain into
     * @return a wired {@code PlatformClient} that owns no transport
     * @hidden
     */
    @ApiStatus.Internal
    public static PlatformClient wired(ApiClient appApiClient, ContextRegistrationBuffer contextBuffer) {
        return new PlatformClient(appApiClient, contextBuffer, false);
    }

    private PlatformClient(ApiClient appApiClient, ContextRegistrationBuffer contextBuffer,
                           boolean ownsTransport) {
        this.ownsTransport = ownsTransport;
        this.environments = new EnvironmentsClient(appApiClient);
        this.services = new ServicesClient(appApiClient);
        this.contexts = new ContextsClient(appApiClient, contextBuffer);
        this.contextTypes = new ContextTypesClient(appApiClient);
    }

    /**
     * Internal: build a standalone client owning a freshly-built app transport and
     * a fresh {@link ContextRegistrationBuffer}.
     */
    static PlatformClient standalone(ApiClient appApiClient) {
        return new PlatformClient(appApiClient, new ContextRegistrationBuffer(), true);
    }

    /**
     * Construct a standalone {@link PlatformClient} resolving credentials from the
     * standard sources (env vars, {@code ~/.smplkit}). Owns its own app transport.
     */
    public static PlatformClient create() {
        return builder().build();
    }

    /** Construct a standalone {@link PlatformClient} with the given API key. */
    public static PlatformClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** Returns a builder for a standalone {@link PlatformClient}. */
    public static PlatformClientBuilder builder() {
        return new PlatformClientBuilder();
    }

    /**
     * Close the app transport — only when this client owns it.
     *
     * <p>A wired client borrows the parent's app transport and closes nothing.</p>
     */
    @Override
    public void close() {
        if (ownsTransport) {
            // The standalone transport is a JDK HttpClient managed by the generated
            // ApiClient; the JDK reclaims its connection pool on GC. Nothing to
            // explicitly release here today — kept for symmetry with the wired path.
        }
    }
}
