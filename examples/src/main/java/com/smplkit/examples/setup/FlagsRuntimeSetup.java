package com.smplkit.examples.setup;

import com.smplkit.Rule;
import com.smplkit.SmplClient;
import com.smplkit.errors.NotFoundError;
import com.smplkit.flags.Flag;
import com.smplkit.flags.types.Op;

import java.util.List;
import java.util.Map;

/** Setup / cleanup helpers for {@code FlagsRuntimeShowcase}. */
public final class FlagsRuntimeSetup {

    private static final List<String> DEMO_FLAG_IDS =
            List.of("checkout-v2", "banner-color", "max-retries");

    private FlagsRuntimeSetup() {}

    public static void setup(SmplClient client) {
        cleanup(client);

        Flag<Boolean> checkout = client.flags.newBooleanFlag(
                "checkout-v2", false, null,
                "Controls rollout of the new checkout experience.");
        checkout.enableRules("production");
        checkout.addRule(new Rule("Enable for enterprise users in US region", "production")
                .when("user.plan", Op.EQ, "enterprise")
                .when("account.region", Op.EQ, "us")
                .serve(true)
                .build());
        checkout.addRule(new Rule("Enable for beta testers", "production")
                .when("user.beta_tester", Op.EQ, true)
                .serve(true)
                .build());
        checkout.save();

        Flag<String> banner = client.flags.newStringFlag(
                "banner-color", "red", "Banner Color",
                "Controls the banner color shown to users.",
                List.of(
                        Map.of("name", "Red", "value", "red"),
                        Map.of("name", "Green", "value", "green"),
                        Map.of("name", "Blue", "value", "blue")));
        banner.enableRules("production");
        banner.addRule(new Rule("Blue for enterprise users", "production")
                .when("user.plan", Op.EQ, "enterprise")
                .serve("blue").build());
        banner.addRule(new Rule("Green for technology companies", "production")
                .when("account.industry", Op.EQ, "technology")
                .serve("green").build());
        banner.save();

        Flag<Number> retries = client.flags.newNumberFlag(
                "max-retries", 3, null,
                "Maximum number of API retries before failing.");
        retries.enableRules("production");
        retries.addRule(new Rule("High retries for large accounts", "production")
                .when("account.employee_count", Op.GT, 100)
                .serve(5).build());
        retries.save();
    }

    public static void cleanup(SmplClient client) {
        for (String flagId : DEMO_FLAG_IDS) {
            try { client.flags.delete(flagId); } catch (NotFoundError ignored) {}
        }
    }
}
