package com.smplkit.examples.setup;

import com.smplkit.Rule;
import com.smplkit.errors.NotFoundError;
import com.smplkit.flags.Flag;
import com.smplkit.management.SmplManagementClient;

import java.util.List;
import java.util.Map;

/** Setup / cleanup helpers for {@code FlagsRuntimeShowcase}. */
public final class FlagsRuntimeSetup {

    private static final List<String> DEMO_ENVIRONMENTS = List.of("staging", "production");
    private static final List<String> DEMO_FLAG_IDS =
            List.of("checkout-v2", "banner-color", "max-retries");

    private FlagsRuntimeSetup() {}

    public static void setup(SmplManagementClient manage) {
        var existing = manage.environments.list().stream()
                .map(com.smplkit.management.Environment::getId).toList();
        for (String envId : DEMO_ENVIRONMENTS) {
            if (!existing.contains(envId)) {
                manage.environments.new_(envId, capitalize(envId), null, null).save();
            }
        }
        cleanup(manage);

        Flag<Boolean> checkout = manage.flags.newBooleanFlag(
                "checkout-v2", false, null,
                "Controls rollout of the new checkout experience.");
        checkout.setEnvironmentEnabled("staging", true);
        checkout.addRule(new Rule("Enable for enterprise users in US region")
                .environment("staging")
                .when("user.plan", "==", "enterprise")
                .when("account.region", "==", "us")
                .serve(true)
                .build());
        checkout.addRule(new Rule("Enable for beta testers")
                .environment("staging")
                .when("user.beta_tester", "==", true)
                .serve(true)
                .build());
        checkout.setEnvironmentEnabled("production", false);
        checkout.setEnvironmentDefault("production", false);
        checkout.save();

        Flag<String> banner = manage.flags.newStringFlag(
                "banner-color", "red", "Banner Color",
                "Controls the banner color shown to users.",
                List.of(
                        Map.of("name", "Red", "value", "red"),
                        Map.of("name", "Green", "value", "green"),
                        Map.of("name", "Blue", "value", "blue")));
        banner.setEnvironmentEnabled("staging", true);
        banner.addRule(new Rule("Blue for enterprise users")
                .environment("staging")
                .when("user.plan", "==", "enterprise")
                .serve("blue").build());
        banner.addRule(new Rule("Green for technology companies")
                .environment("staging")
                .when("account.industry", "==", "technology")
                .serve("green").build());
        banner.setEnvironmentEnabled("production", true);
        banner.setEnvironmentDefault("production", "blue");
        banner.save();

        Flag<Number> retries = manage.flags.newNumberFlag(
                "max-retries", 3, null,
                "Maximum number of API retries before failing.");
        retries.setEnvironmentEnabled("staging", true);
        retries.addRule(new Rule("High retries for large accounts")
                .environment("staging")
                .when("account.employee_count", ">", 100)
                .serve(5).build());
        retries.setEnvironmentEnabled("production", true);
        retries.save();
    }

    public static void cleanup(SmplManagementClient manage) {
        for (String flagId : DEMO_FLAG_IDS) {
            try { manage.flags.delete(flagId); } catch (NotFoundError ignored) {}
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
