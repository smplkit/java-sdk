package com.smplkit.management;

import com.smplkit.internal.generated.app.ApiClient;

import java.time.Duration;

/**
 * Top-level {@code client.management} namespace combining environments, contexts,
 * context_types, and account_settings management clients.
 *
 * <p>Obtain via {@link com.smplkit.SmplClient#management()}.</p>
 */
public final class ManagementClient {

    /** Environment CRUD ({@code client.management.environments}). */
    public final EnvironmentsClient environments;

    /** Context registration and read/delete ({@code client.management.contexts}). */
    public final ContextsClient contexts;

    /** Context-type CRUD ({@code client.management.contextTypes}). */
    public final ContextTypesClient contextTypes;

    /** Account-settings get/save ({@code client.management.accountSettings}). */
    public final AccountSettingsClient accountSettings;

    public ManagementClient(String appBaseUrl, String apiKey, Duration timeout,
                            ContextRegistrationBuffer buffer) {
        ApiClient appApiClient = buildAppApiClient(appBaseUrl, apiKey, timeout);
        this.environments = new EnvironmentsClient(appApiClient);
        this.contextTypes = new ContextTypesClient(appApiClient);
        this.contexts = new ContextsClient(appApiClient, buffer);
        this.accountSettings = new AccountSettingsClient(appApiClient, appBaseUrl, apiKey);
    }

    static ApiClient buildAppApiClient(String baseUrl, String apiKey, Duration timeout) {
        ApiClient client = new ApiClient();
        client.updateBaseUri(baseUrl);
        client.setRequestInterceptor(builder -> builder.header("Authorization", "Bearer " + apiKey));
        if (timeout != null) client.setReadTimeout(timeout);
        return client;
    }
}
