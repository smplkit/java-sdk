package com.smplkit.examples.setup;

import com.smplkit.internal.generated.jobs.ApiException;
import com.smplkit.jobs.JobsClient;

import java.util.List;

/** Setup / cleanup helpers for {@code JobsShowcase}. */
public final class JobsSetup {

    // Every job the jobs showcase creates. Start-of-run cleanup removes residue
    // from a prior run; the matching finally cleanup tears the showcase's jobs
    // down even when it fails mid-way, so a failed run never leaves orphans
    // behind.
    private static final List<String> DEMO_JOB_IDS =
            List.of("showcase-recurring", "showcase-manual", "showcase-oneoff");

    private JobsSetup() {}

    public static void setup(JobsClient jobs) throws ApiException {
        cleanup(jobs);
    }

    public static void cleanup(JobsClient jobs) throws ApiException {
        for (String jobId : DEMO_JOB_IDS) {
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
