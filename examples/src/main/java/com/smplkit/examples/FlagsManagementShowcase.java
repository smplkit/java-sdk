/*
 * Demonstrates the smplkit management SDK for Smpl Flags.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsManagementShowcase
 */
package com.smplkit.examples;

import com.smplkit.Rule;
import com.smplkit.SmplClient;
import com.smplkit.examples.setup.FlagsManagementSetup;
import com.smplkit.flags.Flag;
import com.smplkit.flags.types.Op;

import java.util.List;
import java.util.Map;

public final class FlagsManagementShowcase {

    public static void main(String[] args) {

        // or AsyncSmplClient for asynchronous use
        try (SmplClient client = SmplClient.create()) {
            FlagsManagementSetup.setup(client);

            // create a boolean flag
            Flag<Boolean> checkoutFlag = client.flags.newBooleanFlag(
                    "checkout-v2", false, null,
                    "Controls rollout of the new checkout experience.");
            checkoutFlag.save();
            System.out.println("Created flag: " + checkoutFlag.getId());

            // create a string flag (constrained)
            Flag<String> bannerFlag = client.flags.newStringFlag(
                    "banner-color", "red", "Banner Color",
                    "Controls the banner color shown to users.",
                    List.of(
                            Map.of("name", "Red", "value", "red"),
                            Map.of("name", "Green", "value", "green"),
                            Map.of("name", "Blue", "value", "blue")));
            bannerFlag.save();
            System.out.println("Created flag: " + bannerFlag.getId());

            // create a numeric flag (unconstrained)
            Flag<Number> retryFlag = client.flags.newNumberFlag(
                    "max-retries", 3, null,
                    "Maximum number of API retries before failing.");
            retryFlag.save();
            System.out.println("Created flag: " + retryFlag.getId());

            // create a JSON flag (constrained)
            Flag<Object> themeFlag = client.flags.newJsonFlag(
                    "ui-theme", Map.of("mode", "light", "accent", "#0066cc"),
                    null, "Controls the UI theme configuration.",
                    List.of(
                            Map.of("name", "Light",
                                    "value", Map.of("mode", "light", "accent", "#0066cc")),
                            Map.of("name", "Dark",
                                    "value", Map.of("mode", "dark", "accent", "#66ccff")),
                            Map.of("name", "High Contrast",
                                    "value", Map.of("mode", "dark", "accent", "#ffffff"))));
            themeFlag.save();
            System.out.println("Created flag: " + themeFlag.getId());

            // checkoutFlag (serve true in production to enterprise US users)
            checkoutFlag.enableRules("production");
            checkoutFlag.addRule(new Rule("Enable for enterprise users in US region", "production")
                    .when("user.plan", Op.EQ, "enterprise")
                    .when("account.region", Op.EQ, "us")
                    .serve(true).build());

            // checkoutFlag (serve true in production for beta testers)
            checkoutFlag.addRule(new Rule("Enable for beta testers", "production")
                    .when("user.beta_tester", Op.EQ, true)
                    .serve(true).build());

            checkoutFlag.save();
            System.out.println("Updated flag: " + checkoutFlag.getId());

            // list flags
            List<Flag<?>> flags = client.flags.list();
            System.out.println("Total flags: " + flags.size());
            for (Flag<?> f : flags) {
                var envs = f.environments().keySet();
                System.out.println("  " + f.getId() + " (" + f.getType() + ") — default="
                        + f.getDefault() + ", environments=" + envs);
            }

            // get a flag
            Flag<?> fetched = client.flags.get("checkout-v2");
            System.out.println("\nFetched by id: " + fetched.getId());
            int prodRules = fetched.environments().get("production").rules().size();
            boolean prodEnabled = fetched.environments().get("production").enabled();
            System.out.println("  production rules: " + prodRules);
            System.out.println("  production enabled: " + prodEnabled);

            // update a flag
            bannerFlag.addValue("Purple", "purple");
            bannerFlag.setDefault("blue", null);
            bannerFlag.setDescription("Controls the banner color — updated");
            bannerFlag.addRule(new Rule("Purple for enterprise users", "production")
                    .when("user.plan", Op.EQ, "enterprise")
                    .serve("purple").build());
            bannerFlag.save();
            System.out.println("Updated flag: " + bannerFlag.getId() + "'");

            // delete all the rules of a flag
            checkoutFlag.clearRules("production");
            checkoutFlag.save();
            System.out.println("Updated flag: " + checkoutFlag.getId() + "'");

            // clear values (flag becomes unconstrained)
            bannerFlag.clearValues();
            bannerFlag.save();
            System.out.println("Updated flag: " + bannerFlag.getId() + "'");

            // delete flags
            client.flags.delete("checkout-v2");
            bannerFlag.delete();
            System.out.println("Deleted flags");

            // cleanup
            FlagsManagementSetup.cleanup(client);
            System.out.println("Done!");
        }
    }
}
