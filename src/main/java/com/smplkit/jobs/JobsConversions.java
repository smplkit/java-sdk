package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.model.JobHttpConfiguration;
import com.smplkit.internal.generated.jobs.model.Run;
import com.smplkit.internal.generated.jobs.model.RunResource;
import com.smplkit.internal.generated.jobs.model.Usage;
import com.smplkit.internal.generated.jobs.model.UsageResource;

import java.util.ArrayList;
import java.util.List;

/** Wire &lt;-&gt; wrapper conversions shared by the jobs surface. */
final class JobsConversions {

    private JobsConversions() {}

    static JobHttpConfiguration configurationToGen(HttpConfig src) {
        JobHttpConfiguration out = new JobHttpConfiguration();
        if (src.method != null) {
            out.setMethod(JobHttpConfiguration.MethodEnum.fromValue(src.method.getValue()));
        }
        out.setUrl(src.url);
        if (src.successStatus != null) out.setSuccessStatus(src.successStatus);
        out.setBody(src.body);
        out.setTimeout(src.timeout);
        out.setTlsVerify(src.tlsVerify);
        out.setCaCert(src.caCert);
        List<com.smplkit.internal.generated.jobs.model.HttpHeader> hh = new ArrayList<>();
        if (src.headers != null) {
            for (HttpHeader h : src.headers) {
                com.smplkit.internal.generated.jobs.model.HttpHeader g =
                        new com.smplkit.internal.generated.jobs.model.HttpHeader();
                g.setName(h.name);
                g.setValue(h.value);
                hh.add(g);
            }
        }
        out.setHeaders(hh);
        return out;
    }

    static HttpConfig configurationFromGen(JobHttpConfiguration src) {
        HttpConfig out = new HttpConfig();
        if (src == null) return out;
        if (src.getMethod() != null) out.method = HttpMethod.fromValue(src.getMethod().getValue());
        out.url = src.getUrl() != null ? src.getUrl() : "";
        if (src.getSuccessStatus() != null) out.successStatus = src.getSuccessStatus();
        out.body = src.getBody();
        // Absent ``timeout`` in the response means a job persisted before the field
        // landed — default to 30 so its prior behaviour is preserved.
        out.timeout = src.getTimeout() == null ? 30 : src.getTimeout();
        // Absent ``tls_verify`` means a job persisted before the field landed —
        // default to verifying so its prior secure behaviour is preserved.
        out.tlsVerify = src.getTlsVerify() == null ? true : src.getTlsVerify();
        out.caCert = src.getCaCert();
        out.headers = new ArrayList<>();
        if (src.getHeaders() != null) {
            for (com.smplkit.internal.generated.jobs.model.HttpHeader h : src.getHeaders()) {
                out.headers.add(new HttpHeader(h.getName(), h.getValue()));
            }
        }
        return out;
    }

    static com.smplkit.jobs.Run runFromResource(RunResource r) {
        Run a = r.getAttributes();
        return new com.smplkit.jobs.Run(
                r.getId(),
                a.getJob(),
                a.getJobVersion(),
                a.getTrigger() == null ? null : a.getTrigger().getValue(),
                a.getRerunOf() == null ? null : a.getRerunOf().toString(),
                a.getScheduledFor(),
                a.getStatus() == null ? null : a.getStatus().getValue(),
                a.getStartedAt(),
                a.getFinishedAt(),
                a.getPendingDurationMs(),
                a.getRunDurationMs(),
                a.getTotalDurationMs(),
                a.getFailureReason() == null ? null : a.getFailureReason().getValue(),
                a.getError(),
                a.getRequest(),
                a.getResult(),
                a.getCreatedAt());
    }

    static com.smplkit.jobs.Usage usageFromResource(UsageResource r) {
        Usage a = r.getAttributes();
        return new com.smplkit.jobs.Usage(
                a.getPeriod(),
                a.getRunsUsed(),
                a.getRunsIncluded(),
                a.getActiveJobs(),
                a.getActiveJobsLimit());
    }
}
