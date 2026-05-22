package com.smplkit.examples.setup;

import com.smplkit.Rule;
import com.smplkit.errors.NotFoundError;
import com.smplkit.flags.Flag;
import com.smplkit.management.SmplManagementClient;

import java.util.List;
import java.util.Map;

/** Setup / cleanup helpers for {@code FlagsRuntimeShowcase}. */
public final class FlagsRuntimeSetup {

    private static final List<String> DEMO_FLAG_IDS =
            List.of("checkout-v2", "banner-color", "max-retries");

    private FlagsRuntimeSetup() {}

    public static void setup(SmplManagementClient manage) {
        cleanup(manage);

        Flag<Boolean> checkout = manage.flags.newBooleanFlag(
                "checkout-v2", false, null,
                "Controls rollout of the new checkout experience.");
        checkout.setEnvironmentEnabled("production", true);
        checkout.addRule(new Rule("Enable for enterprise users in US region")
                .environment("production")
                .when("user.plan", "==", "enterprise")
                .when("account.region", "==", "us")
                .serve(true)
                .build());
        checkout.addRule(new Rule("Enable for beta testers")
                .environment("production")
                .when("user.beta_tester", "==", true)
                .serve(true)
                .build());
        checkout.save();

        Flag<String> banner = manage.flags.newStringFlag(
                "banner-color", "red", "Banner Color",
                "Controls the banner color shown to users.",
                List.of(
                        Map.of("name", "Red", "value", "red"),
                        Map.of("name", "Green", "value", "green"),
                        Map.of("name", "Blue", "value", "blue")));
        banner.setEnvironmentEnabled("production", true);
        banner.addRule(new Rule("Blue for enterprise users")
                .environment("production")
                .when("user.plan", "==", "enterprise")
                .serve("blue").build());
        banner.addRule(new Rule("Green for technology companies")
                .environment("production")
                .when("account.industry", "==", "technology")
                .serve("green").build());
        banner.save();

        Flag<Number> retries = manage.flags.newNumberFlag(
                "max-retries", 3, null,
                "Maximum number of API retries before failing.");
        retries.setEnvironmentEnabled("production", true);
        retries.addRule(new Rule("High retries for large accounts")
                .environment("production")
                .when("account.employee_count", ">", 100)
                .serve(5).build());
        retries.save();
    }

    public static void cleanup(SmplManagementClient manage) {
        for (String flagId : DEMO_FLAG_IDS) {
            try { manage.flags.delete(flagId); } catch (NotFoundError ignored) {}
        }
    }
}
