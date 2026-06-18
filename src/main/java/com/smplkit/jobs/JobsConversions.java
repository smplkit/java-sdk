package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.model.JobHttpConfiguration;
import com.smplkit.internal.generated.jobs.model.Run;
import com.smplkit.internal.generated.jobs.model.RunResource;
import com.smplkit.internal.generated.jobs.model.Usage;
import com.smplkit.internal.generated.jobs.model.UsageResource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * Convert the wrapper {@code environments} map to the generated model.
     * Per-environment {@code configuration} overrides are sent as full
     * {@link HttpConfig} payloads (plaintext headers in), mirroring the base
     * configuration's round-trip semantics; an entry without an override sends
     * only {@code enabled} (inherit the base configuration).
     */
    static Map<String, com.smplkit.internal.generated.jobs.model.JobEnvironment> environmentsToGen(
            Map<String, JobEnvironment> environments) {
        Map<String, com.smplkit.internal.generated.jobs.model.JobEnvironment> out = new HashMap<>();
        for (Map.Entry<String, JobEnvironment> e : environments.entrySet()) {
            JobEnvironment env = e.getValue();
            com.smplkit.internal.generated.jobs.model.JobEnvironment gen =
                    new com.smplkit.internal.generated.jobs.model.JobEnvironment();
            gen.setEnabled(env.enabled);
            if (env.configuration != null) {
                gen.setConfiguration(configurationToGen(env.configuration));
            }
            out.put(e.getKey(), gen);
        }
        return out;
    }

    /** Convert the generated {@code environments} map to wrapper instances. */
    static Map<String, JobEnvironment> environmentsFromGen(
            Map<String, com.smplkit.internal.generated.jobs.model.JobEnvironment> environments) {
        Map<String, JobEnvironment> out = new HashMap<>();
        if (environments == null) return out;
        for (Map.Entry<String, com.smplkit.internal.generated.jobs.model.JobEnvironment> e
                : environments.entrySet()) {
            com.smplkit.internal.generated.jobs.model.JobEnvironment gen = e.getValue();
            JobEnvironment env = new JobEnvironment();
            if (gen != null) {
                env.enabled = gen.getEnabled() != null ? gen.getEnabled() : false;
                env.configuration = gen.getConfiguration() != null
                        ? configurationFromGen(gen.getConfiguration()) : null;
            }
            out.put(e.getKey(), env);
        }
        return out;
    }

    /**
     * Join environment keys into the comma-separated string the generated
     * {@code filter[environment]} parameter expects. Returns {@code null} when
     * the list is {@code null} or contains no non-blank entries, leaving the
     * filter unset. Blank entries are dropped.
     */
    static String joinEnvironments(List<String> environments) {
        if (environments == null || environments.isEmpty()) {
            return null;
        }
        String joined = environments.stream()
                .filter(e -> e != null && !e.isBlank())
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    /**
     * Resolve the {@code filter[environment]} value for the runs read surface:
     * an explicit {@code environments} list (comma-joined) wins, otherwise the
     * client's configured {@code defaultEnvironment}, otherwise {@code null} so
     * the filter is omitted and the credential's own scoping applies.
     */
    static String resolveEnvironmentFilter(List<String> environments, String defaultEnvironment) {
        String explicit = joinEnvironments(environments);
        return explicit != null ? explicit : defaultEnvironment;
    }

    static com.smplkit.jobs.Run runFromResource(RunResource r, RunsClient runs) {
        Run a = r.getAttributes();
        return new com.smplkit.jobs.Run(
                r.getId(),
                a.getJob(),
                a.getJobVersion(),
                a.getEnvironment(),
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
                a.getCreatedAt(),
                runs);
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
