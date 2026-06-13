package com.smplkit.account;

import java.util.Map;

/**
 * The Smpl Account client (sync).
 *
 * <p>Exposes the authenticated account's own configuration, reachable as
 * {@code client.account} ({@link com.smplkit.SmplClient}) or constructed
 * directly:</p>
 *
 * <pre>{@code
 * import com.smplkit.account.AccountClient;
 *
 * try (AccountClient account = AccountClient.create("sk_...")) {
 *     AccountSettings settings = account.settings.get();
 *     settings.setEnvironmentOrder(java.util.List.of("production", "staging"));
 *     settings.save();
 * }
 * }</pre>
 *
 * <p>Sub-client: {@code settings} (get/save). Pure CRUD — no {@code install()}
 * required.</p>
 *
 * <p>The settings endpoint isn't JSON:API — its body is a raw JSON object — so
 * the settings sub-client uses HTTP directly rather than going through a
 * generated client.</p>
 *
 * <p>The client supports two construction shapes:</p>
 * <ul>
 *   <li><strong>Wired</strong> into {@link com.smplkit.SmplClient} — built from
 *     the app base URL and api key the top-level client has already resolved.
 *     This is the common path.</li>
 *   <li><strong>Standalone</strong> — {@link #create()} / {@link #create(String)}
 *     / {@link #builder()} resolve the app base URL themselves. There are no
 *     pooled transports to tear down (each settings call opens and closes its
 *     own short-lived HTTP client), so {@link #close()} is a no-op kept for
 *     interface symmetry.</li>
 * </ul>
 */
public final class AccountClient implements AutoCloseable {

    /** Sync account-settings get/save ({@code client.account.settings}). */
    public final SettingsClient settings;

    /**
     * Wired constructor used by {@link com.smplkit.SmplClient}. Mirrors python's
     * {@code AccountClient(api_key=..., base_url=app_url, extra_headers=...)}.
     *
     * <p>Public because {@link com.smplkit.SmplClient} lives in a different
     * package; it is the wired entry point and not intended for direct use —
     * standalone callers use {@link #create()} / {@link #builder()}.</p>
     *
     * @param apiKey resolved API key
     * @param appBaseUrl full app-service base URL the top-level client already computed
     * @param extraHeaders extra headers attached to every request (may be {@code null})
     */
    public AccountClient(String apiKey, String appBaseUrl, Map<String, String> extraHeaders) {
        this.settings = new SettingsClient(appBaseUrl, apiKey, extraHeaders);
    }

    /**
     * Construct an {@link AccountClient} resolving credentials from the standard
     * sources (env vars, {@code ~/.smplkit}). Each settings call opens its own
     * short-lived HTTP client.
     */
    public static AccountClient create() {
        return builder().build();
    }

    /** Construct an {@link AccountClient} with the given API key. */
    public static AccountClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /** Returns a builder for {@link AccountClient}. */
    public static AccountClientBuilder builder() {
        return new AccountClientBuilder();
    }

    /** No-op — the settings client opens a short-lived HTTP client per call. */
    @Override
    public void close() {
        // No persistent resources to tear down.
    }
}
