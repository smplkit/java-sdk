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

import com.smplkit.internal.generated.jobs.ApiException;
import com.smplkit.jobs.HttpConfig;
import com.smplkit.jobs.HttpHeader;
import com.smplkit.jobs.HttpMethod;
import com.smplkit.jobs.Job;
import com.smplkit.jobs.JobsClient;
import com.smplkit.jobs.ListJobsInput;
import com.smplkit.jobs.ListRunsInput;
import com.smplkit.jobs.Run;

import java.util.List;
import java.util.UUID;

public final class JobsShowcase {

    public static void main(String[] args) throws Exception {

        // Jobs has no runtime/management split — one client. Here we use the
        // standalone JobsClient (use AsyncJobsClient for asynchronous use);
        // the same surface is also reachable as ``client.jobs`` on a SmplClient.
        try (JobsClient jobs = JobsClient.create()) {
            String jobId = "showcase-mgmt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

            try {
                // create a job
                HttpConfig config = new HttpConfig(
                        HttpMethod.POST,
                        "https://api.example.com/cache/warm",
                        List.of(new HttpHeader("Authorization", "Bearer s3cr3t")));
                config.body = "{\"scope\": \"all\"}";
                config.timeout = 30;
                Job job = jobs.new_(
                        jobId,
                        "Nightly cache warm",
                        "0 2 * * *",  // 5-field cron, UTC
                        config);
                job.description = "Warms the product cache every night at 02:00 UTC.";
                job.enabled = false;
                job.save();
                assert job.version == 1;
                System.out.println("Created job " + job.id + " (v" + job.version + ")");

                // get a job
                Job fetched = jobs.get(jobId);
                assert fetched.configuration.url.equals("https://api.example.com/cache/warm");
                System.out.println("Fetched job " + jobId);

                // list jobs
                ListJobsInput listInput = new ListJobsInput();
                listInput.enabled = false;
                List<Job> listing = jobs.list(listInput);
                assert listing.stream().anyMatch(j -> j.id.equals(jobId));
                System.out.println("Found job " + jobId + " and in the listing");

                // update a job
                job.name = "Nightly cache warm (v2)";
                job.schedule = "30 2 * * *";
                job.enabled = true;
                job.save();
                assert job.version == 2 && job.enabled;
                System.out.println("Updated job to v" + job.version + ": schedule=" + job.schedule);

                // trigger an immediate run (a MANUAL run)
                Run run = jobs.run(jobId);
                assert run.trigger.equals("MANUAL") && run.job.equals(jobId);
                System.out.println("Triggered run " + run.id + " (trigger=" + run.trigger + ", status=" + run.status + ")");

                // read run history for this job, and fetch a single run
                ListRunsInput runsInput = new ListRunsInput();
                runsInput.job = jobId;
                List<Run> runs = jobs.runs.list(runsInput);
                assert runs.stream().anyMatch(r -> r.id.equals(run.id));
                Run got = jobs.runs.get(run.id);
                assert got.id.equals(run.id);
                System.out.println("Listed " + runs.size() + " run(s); fetched run " + got.id + " (status=" + got.status + ")");

                // re-run from a prior run, then cancel it while it's still pending
                Run rerun = jobs.runs.rerun(run.id);
                assert rerun.trigger.equals("RERUN") && rerun.rerunOf.equals(run.id);
                Run canceled = jobs.runs.cancel(rerun.id);
                assert canceled.status.equals("CANCELED");
                System.out.println("Re-ran (" + rerun.id + ") then canceled it -> " + canceled.status);

                // delete a job
                job.delete();
                assert jobs.list().stream().noneMatch(j -> j.id.equals(jobId));
                System.out.println("Deleted job " + jobId + " — jobs showcase complete.");
            } finally {
                // tear-down: never leave the showcase job behind, even on failure
                try {
                    jobs.delete(jobId);
                } catch (ApiException err) {
                    if (err.getCode() != 404) {
                        throw err;
                    }
                }
            }
        }
    }
}
