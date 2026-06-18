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
import com.smplkit.jobs.HttpConfig;
import com.smplkit.jobs.HttpHeader;
import com.smplkit.jobs.HttpMethod;
import com.smplkit.jobs.Job;
import com.smplkit.jobs.JobsClient;
import com.smplkit.jobs.Run;

import java.util.List;

public final class JobsShowcase {

    private static final String RECURRING_JOB_ID = "showcase-recurring";
    private static final String ONEOFF_JOB_ID = "showcase-oneoff";

    public static void main(String[] args) throws Exception {

        // the standalone JobsClient (or AsyncJobsClient for asynchronous use);
        // the same surface is also reachable as ``client.jobs`` on a SmplClient.
        JobsClient jobs = JobsClient.create();
        JobsSetup.setup(jobs);
        try {
            // create a recurring job, enabled in production with a development override
            HttpConfig config = new HttpConfig(
                    HttpMethod.POST,
                    "https://httpbin.org/post",
                    List.of(new HttpHeader("Authorization", "Bearer s3cr3t")));
            config.body = "{\"scope\": \"all\"}";
            config.timeout = 30;
            Job job = jobs.new_(
                    RECURRING_JOB_ID,
                    "Nightly cache warm",
                    "0 2 * * *",
                    config);
            job.description = "Warms the product cache every night at 02:00 UTC.";
            HttpConfig developmentConfig = new HttpConfig(
                    HttpMethod.POST,
                    "https://development.example.com/cache/warm",
                    List.of(new HttpHeader("Authorization", "Bearer development-s3cr3t")));
            developmentConfig.body = "{\"scope\": \"all\"}";
            job.setConfiguration(developmentConfig, "development");
            job.setEnabled(false, "development");
            job.setEnabled(true, "production");
            job.save();
            assert job.version == 1;
            assert !job.isEnabled("development");
            assert job.isEnabled("production");
            System.out.println("Created recurring job " + job.id + " (v" + job.version + ")");

            // get a job
            Job fetched = jobs.get(RECURRING_JOB_ID);
            assert !fetched.isEnabled("development");
            assert fetched.isEnabled("production");
            assert fetched.getConfiguration("development").url.equals("https://development.example.com/cache/warm");
            System.out.println("Fetched job " + RECURRING_JOB_ID);

            // list jobs
            List<Job> listing = jobs.list();
            assert listing.stream().anyMatch(j -> j.id.equals(RECURRING_JOB_ID));
            System.out.println("Found job " + RECURRING_JOB_ID + " in the listing");

            // update a job (the schedule is environment-agnostic)
            job.name = "Nightly cache warm (v2)";
            job.setSchedule("30 2 * * *");
            job.setEnabled(true, "development");
            job.save();
            assert job.version == 2 && job.isEnabled("development");
            System.out.println("Updated job to v" + job.version + ": now enabled in production and development");

            // trigger an immediate run
            Run run = job.trigger("production");
            assert run.trigger.equals("MANUAL") && run.environment.equals("production");
            System.out.println("Triggered run " + run.id + " (trigger=" + run.trigger + ", env=" + run.environment + ")");

            // get this job's runs
            List<Run> runs = job.listRuns("production");
            assert runs.stream().anyMatch(r -> r.id.equals(run.id));
            System.out.println("Listed " + runs.size() + " production run(s)");

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

            // create a one-off job, born in a single environment
            Job oneoff = jobs.new_(
                    ONEOFF_JOB_ID,
                    "One-shot reindex",
                    "now",
                    new HttpConfig(HttpMethod.POST, "https://httpbin.org/post", List.of()),
                    "development");
            oneoff.save();
            assert oneoff.version == 1 && oneoff.isEnabled("development");
            System.out.println("Created one-off job " + oneoff.id + " born in development");

            // delete a job
            job.delete();
            assert jobs.list().stream().noneMatch(j -> j.id.equals(RECURRING_JOB_ID));
            System.out.println("Deleted job " + RECURRING_JOB_ID + " — jobs showcase complete.");
        } finally {
            JobsSetup.cleanup(jobs);
            jobs.close();
        }
    }
}
