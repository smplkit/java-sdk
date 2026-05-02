/*
 * Demonstrates the smplkit runtime SDK for Smpl Flags.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsRuntimeShowcase
 */
//
// Note: this showcase calls client.setContext(...) inline to demonstrate
// context-driven flag evaluation.  In a real app (Spring, Dropwizard,
// Micronaut, etc.), setContext is called once per request from middleware
// — not scattered through your handlers.
//
package com.smplkit.examples;

import com.smplkit.Context;
import com.smplkit.Rule;
import com.smplkit.SmplClient;
import com.smplkit.examples.setup.FlagsRuntimeSetup;
import com.smplkit.flags.Flag;
import com.smplkit.flags.FlagChangeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FlagsRuntimeShowcase {

    private static final Map<String, Object> ALICE = Map.of(
            "beta_tester", true,
            "email", "alice.adams@acme.com",
            "first_name", "Alice",
            "last_name", "Adams",
            "plan", "enterprise");

    private static final Map<String, Object> BOB = Map.of(
            "beta_tester", false,
            "email", "bob.jones@acme.com",
            "first_name", "Bob",
            "last_name", "Jones",
            "plan", "free");

    private static final Map<String, Object> LARGE_TECHNOLOGY_ACCOUNT = Map.of(
            "employee_count", 500,
            "id", 1234,
            "industry", "technology",
            "region", "us");

    private static final Map<String, Object> SMALL_RETAIL_ACCOUNT = Map.of(
            "employee_count", 10,
            "id", 5678,
            "industry", "retail",
            "region", "eu");

    /** Create context within which flags will be evaluated. */
    private static List<Context> createContext(Map<String, Object> user, Map<String, Object> account) {
        return List.of(
                Context.builder("user", (String) user.get("email"))
                        .attr("beta_tester", user.get("beta_tester"))
                        .attr("first_name", user.get("first_name"))
                        .attr("last_name", user.get("last_name"))
                        .attr("plan", user.get("plan"))
                        .build(),
                Context.builder("account", String.valueOf(account.get("id")))
                        .attr("industry", account.get("industry"))
                        .attr("region", account.get("region"))
                        .attr("employee_count", account.get("employee_count"))
                        .build());
    }

    public static void main(String[] args) throws Exception {
        // create the client (use SmplClient for synchronous use)
        try (SmplClient client = SmplClient.builder()
                .environment("staging").service("showcase-service").build()) {
            FlagsRuntimeSetup.setup(client.manage());

            // declare flags - default values will be used if the flag does not
            // exist or smplkit is unreachable
            Flag<Boolean> checkoutV2 = client.flags().booleanFlag("checkout-v2", false);
            Flag<String> bannerColor = client.flags().stringFlag("banner-color", "red");
            Flag<Number> maxRetries = client.flags().numberFlag("max-retries", 3);

            List<Map<String, String>> allChanges = new ArrayList<>();
            List<FlagChangeEvent> bannerChanges = new ArrayList<>();

            // global listener — fires when ANY flag definition changes
            client.flags().onChange(event -> {
                allChanges.add(Map.of("id", event.id(), "source", event.source()));
                System.out.println("    Global flag listener: '" + event.id()
                        + "' updated via " + event.source());
            });

            // flag listener — fires only when a specific flag changes
            client.flags().onChange("banner-color", event -> {
                bannerChanges.add(event);
                System.out.println("    banner-color flag changed!");
            });

            // request 1 — Alice from a large tech account
            client.setContext(createContext(ALICE, LARGE_TECHNOLOGY_ACCOUNT));
            boolean checkoutResult = checkoutV2.get();
            System.out.println("checkout-v2 = " + checkoutResult);
            assert checkoutResult : "Expected true, got " + checkoutResult;

            String bannerResult = bannerColor.get();
            System.out.println("banner-color = " + bannerResult);
            assert "blue".equals(bannerResult) : "Expected 'blue', got " + bannerResult;

            Number retriesResult = maxRetries.get();
            System.out.println("max-retries = " + retriesResult);
            assert retriesResult.intValue() == 5 : "Expected 5, got " + retriesResult;

            // request 2 — Bob from a small retail account
            client.setContext(createContext(BOB, SMALL_RETAIL_ACCOUNT));
            boolean checkoutResult2 = checkoutV2.get();
            System.out.println("checkout-v2 = " + checkoutResult2);
            assert !checkoutResult2;

            String bannerResult2 = bannerColor.get();
            System.out.println("banner-color = " + bannerResult2);
            assert "red".equals(bannerResult2);

            Number retriesResult2 = maxRetries.get();
            System.out.println("max-retries = " + retriesResult2);
            assert retriesResult2.intValue() == 3;

            // get a flag's value (explicitly pass context)
            boolean explicitResult = checkoutV2.get(List.of(
                    Context.builder("user", "john.smith@acme.com")
                            .attr("plan", "free")
                            .attr("beta_tester", false).build(),
                    Context.builder("account", "1111").attr("region", "jp").build()));
            System.out.println("checkout-v2 (free, JP) = " + explicitResult);
            assert !explicitResult;

            // simulate someone making changes to a flag to trigger listeners
            updateRules(client);

            // wait a moment for the event to be delivered
            Thread.sleep(200);

            // verify both listeners fired
            assert !allChanges.isEmpty() : "Expected at least one global change";
            assert !bannerChanges.isEmpty() : "Expected at least one banner change";

            FlagsRuntimeSetup.cleanup(client.manage());
            System.out.println("Done!");
        }
    }

    private static void updateRules(SmplClient client) {
        Flag<?> currentBanner = client.manage().flags.get("banner-color");
        currentBanner.addRule(new Rule("Red for small companies")
                .environment("staging")
                .when("account.employee_count", "<", 50)
                .serve("red").build());
        currentBanner.save();
    }
}
