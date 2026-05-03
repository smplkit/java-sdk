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
import com.smplkit.examples.setup.FlagsManagementSetup;
import com.smplkit.flags.Flag;
import com.smplkit.management.SmplManagementClient;

import java.util.List;
import java.util.Map;

public final class FlagsManagementShowcase {

    public static void main(String[] args) {
        // create the client (use SmplManagementClient for synchronous use)
        try (SmplManagementClient manage = SmplManagementClient.create()) {
            FlagsManagementSetup.setup(manage);

            // create a boolean flag
            Flag<Boolean> checkoutFlag = manage.flags.newBooleanFlag(
                    "checkout-v2", false, null,
                    "Controls rollout of the new checkout experience.");
            checkoutFlag.save();
            System.out.println("Created flag: " + checkoutFlag.getId());

            // create a string flag (constrained)
            Flag<String> bannerFlag = manage.flags.newStringFlag(
                    "banner-color", "red", "Banner Color",
                    "Controls the banner color shown to users.",
                    List.of(
                            Map.of("name", "Red", "value", "red"),
                            Map.of("name", "Green", "value", "green"),
                            Map.of("name", "Blue", "value", "blue")));
            bannerFlag.save();
            System.out.println("Created flag: " + bannerFlag.getId());

            // create a numeric flag (unconstrained)
            Flag<Number> retryFlag = manage.flags.newNumberFlag(
                    "max-retries", 3, null,
                    "Maximum number of API retries before failing.");
            retryFlag.save();
            System.out.println("Created flag: " + retryFlag.getId());

            // create a JSON flag (constrained)
            Flag<Object> themeFlag = manage.flags.newJsonFlag(
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

            // checkoutFlag (serve true in staging to enterprise US users)
            checkoutFlag.enableRules("staging");
            checkoutFlag.addRule(new Rule("Enable for enterprise users in US region")
                    .environment("staging")
                    .when("user.plan", "==", "enterprise")
                    .when("account.region", "==", "us")
                    .serve(true).build());

            // checkoutFlag (serve true in staging for beta testers)
            checkoutFlag.addRule(new Rule("Enable for beta testers")
                    .environment("staging")
                    .when("user.beta_tester", "==", true)
                    .serve(true).build());

            // checkoutFlag (disabled rules; serve false in production)
            checkoutFlag.disableRules("production");
            checkoutFlag.setDefault(false, "production");
            checkoutFlag.save();
            System.out.println("Updated flag: " + checkoutFlag.getId());

            // list flags
            List<Flag<?>> flags = manage.flags.list();
            System.out.println("Total flags: " + flags.size());
            for (Flag<?> f : flags) {
                var envs = f.environments().keySet();
                System.out.println("  " + f.getId() + " (" + f.getType() + ") — default="
                        + f.getDefault() + ", environments=" + envs);
            }

            // get a flag
            Flag<?> fetched = manage.flags.get("checkout-v2");
            System.out.println("\nFetched by id: " + fetched.getId());
            int stagingRules = fetched.environments().get("staging").rules().size();
            boolean prodEnabled = fetched.environments().get("production").enabled();
            System.out.println("  staging rules: " + stagingRules);
            System.out.println("  production enabled: " + prodEnabled);

            // update a flag
            bannerFlag.addValue("Purple", "purple");
            bannerFlag.setDefault("blue", null);
            bannerFlag.setDescription("Controls the banner color — updated");
            bannerFlag.addRule(new Rule("Purple for enterprise users")
                    .environment("production")
                    .when("user.plan", "==", "enterprise")
                    .serve("purple").build());
            bannerFlag.save();
            System.out.println("Updated flag: " + bannerFlag.getId() + "'");

            // delete all the rules of a flag
            checkoutFlag.clearRules("staging");
            checkoutFlag.save();

            // revert production's default value back to the flag default
            checkoutFlag.clearDefault("production");
            checkoutFlag.save();
            System.out.println("Updated flag: " + checkoutFlag.getId() + "'");

            // clear values (flag becomes unconstrained)
            bannerFlag.clearValues();
            bannerFlag.save();
            System.out.println("Updated flag: " + bannerFlag.getId() + "'");

            // delete flags
            manage.flags.delete("checkout-v2");
            manage.flags.delete(bannerFlag.getId());
            System.out.println("Deleted flags");

            // cleanup
            FlagsManagementSetup.cleanup(manage);
            System.out.println("Done!");
        }
    }
}
