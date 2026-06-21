/*
 * Demonstrates the smplkit management SDK for Smpl Jobs.
 *
 * Prerequisites:
 *     - smplkit-sdk on the classpath
 *     - A valid smplkit API key, provided via one of:
 *         - SMPLKIT_API_KEY environment variable
 *         - ~/.smplkit configuration file (see SDK docs)
 *
 * Usage:
 *     ./gradlew :examples:run -PmainClass=com.smplkit.examples.JobsShowcase
 */
package com.smplkit.examples;

import com.smplkit.examples.setup.JobsSetup;
import com.smplkit.internal.generated.jobs.ApiException;
import com.smplkit.jobs.Backoff;
import com.smplkit.jobs.HttpConfig;
import com.smplkit.jobs.HttpHeader;
import com.smplkit.jobs.HttpMethod;
import com.smplkit.jobs.Job;
import com.smplkit.jobs.JobKind;
import com.smplkit.jobs.JobsClient;
import com.smplkit.jobs.ListJobsInput;
import com.smplkit.jobs.RetryPolicy;
import com.smplkit.jobs.Run;
import com.smplkit.jobs.RunTrigger;

import java.time.OffsetDateTime;
import java.util.List;

public final class JobsShowcase {

    private static final String RECURRING_JOB_ID = "showcase-recurring";
    private static final String MANUAL_JOB_ID = "showcase-manual";
    private static final String ONEOFF_JOB_ID = "showcase-oneoff";
    private static final String RETRY_POLICY_ID = "showcase-retry";

    public static void main(String[] args) throws Exception {

        // the standalone JobsClient (or AsyncJobsClient for asynchronous use);
        // the same surface is also reachable as ``client.jobs`` on a SmplClient.
        JobsClient jobs = JobsClient.create();
        JobsSetup.setup(jobs);
        try {
            // create a retry policy
            RetryPolicy retryPolicy = jobs.retryPolicies.new_(
                    RETRY_POLICY_ID,
                    "Retry on server errors",
                    5,
                    Backoff.EXPONENTIAL,
                    2,
                    60,
                    true,
                    true,
                    List.of("429", "5xx"),
                    List.of("501"));
            retryPolicy.save();
            assert jobs.retryPolicies.list().stream().anyMatch(p -> p.id.equals(RETRY_POLICY_ID));
            System.out.println("Created retry policy " + retryPolicy.id);

            // create a recurring job
            HttpConfig config = new HttpConfig(
                    HttpMethod.POST,
                    "https://httpbin.org/post",
                    List.of(new HttpHeader("Authorization", "Bearer s3cr3t")));
            config.body = "{\"scope\": \"all\"}";
            config.timeout = 30;
            Job job = jobs.newRecurringJob(
                    RECURRING_JOB_ID,
                    "Nightly cache warm",
                    "0 2 * * *",
                    config);
            job.description = "Warms the product cache nightly.";
            job.setEnabled(true, "development");
            job.setEnabled(true, "production");
            job.setSchedule("0 */6 * * *", "America/New_York", "development");
            HttpConfig developmentConfig = new HttpConfig(
                    HttpMethod.POST,
                    "https://development.example.com/cache/warm",
                    List.of(new HttpHeader("Authorization", "Bearer development-s3cr3t")));
            developmentConfig.body = "{\"scope\": \"all\"}";
            job.setConfiguration(developmentConfig, "development");
            job.save();
            assert job.isRecurring();
            assert job.isEnabled("development");
            assert job.isEnabled("production");
            assert job.environments.get("development").timezone.equals("America/New_York");
            assert job.getConfiguration("development").url.equals("https://development.example.com/cache/warm");
            System.out.println("Created recurring job " + job.id + " (v" + job.version + ")");

            // get a job
            Job fetched = jobs.get(RECURRING_JOB_ID);
            assert fetched.environments.get("development").schedule.equals("0 */6 * * *");
            System.out.println("Fetched job " + RECURRING_JOB_ID);

            // list jobs, filtered to recurring jobs
            ListJobsInput filter = new ListJobsInput();
            filter.kind = JobKind.RECURRING;
            List<Job> listing = jobs.list(filter);
            assert listing.stream().anyMatch(j -> j.id.equals(RECURRING_JOB_ID));
            System.out.println("Found job " + RECURRING_JOB_ID + " in the listing");

            // update a job
            job.name = "Nightly cache warm (v2)";
            job.setRetryPolicy(retryPolicy, "production");
            job.setSchedule("30 2 * * *", "America/Los_Angeles", "production");
            job.save();
            assert job.version == 2;
            System.out.println("Updated job to v" + job.version);

            // trigger an immediate run
            Run run = job.trigger("production");
            assert run.trigger.equals("MANUAL") && run.environment.equals("production");
            System.out.println("Triggered run " + run.id + " (trigger=" + run.trigger + ", env=" + run.environment + ")");

            // get this job's runs
            List<Run> runs = job.listRuns("production");
            assert runs.stream().anyMatch(r -> r.id.equals(run.id));
            System.out.println("Listed " + runs.size() + " production run(s)");

            // get the last completed run in production
            List<Run> recent = job.listRuns("production", true);
            System.out.println("Last completed production run(s): " + recent.size());

            // get a run
            Run fetchedRun = jobs.runs.get(run.id);
            assert fetchedRun.environment.equals("production");
            System.out.println("Fetched run " + fetchedRun.id + " (env=" + fetchedRun.environment + ")");

            // re-run a prior run (inherits its environment)
            Run rerun = fetchedRun.rerun();
            assert rerun.trigger.equals("RERUN") && rerun.environment.equals(fetchedRun.environment);
            System.out.println("Re-ran " + fetchedRun.id + " -> " + rerun.id + " (env=" + rerun.environment + ")");

            // cancel a run (best-effort: a finished run can no longer be canceled)
            try {
                Run canceled = rerun.cancel();
                System.out.println("Canceled run " + canceled.id + " -> " + canceled.status);
            } catch (ApiException err) {
                if (err.getCode() == 409) {
                    System.out.println("Run " + rerun.id + " already finished before it could be canceled");
                } else {
                    throw err;
                }
            }

            // create a manual job (no schedule, runs only when triggered)
            Job manual = jobs.newManualJob(
                    MANUAL_JOB_ID,
                    "On-demand reindex",
                    new HttpConfig(HttpMethod.POST, "https://httpbin.org/post", List.of()));
            manual.setEnabled(true, "production");
            manual.save();
            assert manual.isManual();
            Run manualRun = manual.trigger("production");
            assert manualRun.trigger.equals(RunTrigger.MANUAL.getValue());
            System.out.println("Created manual job " + manual.id + " and triggered it on demand");

            // schedule a one-off job to run tomorrow
            OffsetDateTime tomorrow = OffsetDateTime.now().plusDays(1);
            Job oneoff = jobs.schedule(
                    ONEOFF_JOB_ID,
                    "One-shot reindex",
                    tomorrow,
                    new HttpConfig(HttpMethod.POST, "https://httpbin.org/post", List.of()),
                    "development");
            oneoff.save();
            assert oneoff.isOneOff();
            assert oneoff.isEnabled("development");
            assert oneoff.environments.get("development").nextRunAt != null;
            System.out.println("Created one-off job " + oneoff.id + " to run in development");

            // delete a job
            job.delete();
            assert jobs.list().stream().noneMatch(j -> j.id.equals(RECURRING_JOB_ID));

            // delete the retry policy
            retryPolicy.delete();
            System.out.println("Deleted job " + RECURRING_JOB_ID + " and retry policy — jobs showcase complete.");
        } finally {
            JobsSetup.cleanup(jobs);
            jobs.close();
        }
    }
}
