/*
 * Demonstrates the smplkit runtime SDK for Smpl Config.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *     - The smplkit Config service running and reachable
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigRuntimeShowcase
 */
package com.smplkit.examples;

import com.smplkit.SmplClient;
import com.smplkit.config.Config;
import com.smplkit.config.ConfigChangeEvent;
import com.smplkit.config.LiveConfigProxy;
import com.smplkit.examples.setup.ConfigRuntimeSetup;

import java.util.ArrayList;
import java.util.List;

public final class ConfigRuntimeShowcase {

    public static void main(String[] args) throws Exception {
        // create the client (use SmplClient for synchronous use)
        try (SmplClient client = SmplClient.builder()
                .environment("production").service("showcase-service").build()) {
            ConfigRuntimeSetup.setup(client.manage());

            // get a config as a plain dict
            LiveConfigProxy userSvcConfigDict = client.config().get("showcase-user-service");
            System.out.println("Total resolved keys: " + userSvcConfigDict.size());
            System.out.println("database.host = " + userSvcConfigDict.get("database.host"));
            System.out.println("max_retries = " + userSvcConfigDict.get("max_retries"));
            System.out.println("cache_ttl_seconds = " + userSvcConfigDict.get("cache_ttl_seconds"));
            System.out.println("pagination_default_page_size = "
                    + userSvcConfigDict.get("pagination_default_page_size"));
            System.out.println("enable_signup = " + userSvcConfigDict.get("enable_signup"));
            System.out.println("nonexistent_key = " + userSvcConfigDict.get("nonexistent_key"));

            // production overrides resolve through the inheritance chain
            assert "prod-users-rds.internal.acme.dev".equals(userSvcConfigDict.get("database.host"));
            assert userSvcConfigDict.get("nonexistent_key") == null;

            List<ConfigChangeEvent> changes = new ArrayList<>();
            List<ConfigChangeEvent> retriesChanges = new ArrayList<>();

            // global listener — fires when ANY config item changes
            client.config().onChange(event -> {
                changes.add(event);
                System.out.println("    [CHANGE] " + event.configId() + "."
                        + event.itemKey() + ": " + event.oldValue()
                        + " -> " + event.newValue());
            });

            // item-scoped listener via the live-proxy handle
            LiveConfigProxy commonCfg = client.config().get("showcase-common");
            commonCfg.onChange("max_retries", retriesChanges::add);

            // simulate someone making a change to trigger listeners
            updateMaxRetries(client, 7);

            // wait a moment for the event to be delivered
            Thread.sleep(200);

            // userSvcConfigDict always reflects the latest values
            System.out.println("max_retries after update = " + userSvcConfigDict.get("max_retries"));
            System.out.println("Global changes received: " + changes.size());
            System.out.println("Retries-specific changes received: " + retriesChanges.size());

            assert ((Number) userSvcConfigDict.get("max_retries")).intValue() == 7;
            assert !changes.isEmpty();
            assert !retriesChanges.isEmpty();

            ConfigRuntimeSetup.cleanup(client.manage());
            System.out.println("Done!");
        }
    }

    private static void updateMaxRetries(SmplClient client, int maxRetries) {
        Config commonCfg = client.manage().config.get("showcase-common");
        commonCfg.setNumber("max_retries", maxRetries, "production");
        commonCfg.save();
    }
}
