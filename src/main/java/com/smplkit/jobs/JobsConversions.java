package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.model.JobHttpConfiguration;
import com.smplkit.internal.generated.jobs.model.Run;
import com.smplkit.internal.generated.jobs.model.RunResource;
import com.smplkit.internal.generated.jobs.model.Usage;
import com.smplkit.internal.generated.jobs.model.UsageResource;

import java.util.LinkedHashMap;
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
        // Headers travel as a name->value object (ADR-056).
        out.setHeaders(src.headers != null ? new LinkedHashMap<>(src.headers) : new LinkedHashMap<>());
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
        out.headers = src.getHeaders() != null ? new LinkedHashMap<>(src.getHeaders()) : new LinkedHashMap<>();
        return out;
    }

    /**
     * Convert the wrapper {@code environments} map to the generated model. Each
     * value is a flat sparse leaf-path overlay (ADR-056): {@code enabled} plus
     * only the leaves the environment overrides, with each header as a
     * {@code headers.<name>} leaf. The read-only {@code nextRunAt} is never
     * written.
     */
    static Map<String, Map<String, Object>> environmentsToGen(Map<String, JobEnvironment> environments) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JobEnvironment> e : environments.entrySet()) {
            out.put(e.getKey(), e.getValue().toOverlay());
        }
        return out;
    }

    /** Convert the generated {@code environments} map (flat overlays) to wrapper instances. */
    static Map<String, JobEnvironment> environmentsFromGen(Map<String, Map<String, Object>> environments) {
        Map<String, JobEnvironment> out = new LinkedHashMap<>();
        if (environments == null) return out;
        for (Map.Entry<String, Map<String, Object>> e : environments.entrySet()) {
            out.put(e.getKey(), JobEnvironment.fromOverlay(e.getValue()));
        }
        return out;
    }

    /**
     * Join run triggers into the comma-separated string the generated
     * {@code filter[trigger]} parameter expects (any-of). Returns {@code null}
     * when the list is {@code null} or empty, leaving the filter unset.
     */
    static String joinTriggers(List<RunTrigger> triggers) {
        if (triggers == null || triggers.isEmpty()) {
            return null;
        }
        return triggers.stream().map(RunTrigger::getValue).collect(Collectors.joining(","));
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
        // ``retry`` is present only on RETRY runs; map it to the read-only
        // RunRetry record when the server includes it.
        com.smplkit.internal.generated.jobs.model.RunRetry genRetry = a.getRetry();
        RunRetry retry = genRetry == null ? null
                : new RunRetry(genRetry.getOf().toString(), genRetry.getAttempt());
        return new com.smplkit.jobs.Run(
                r.getId(),
                a.getJob(),
                a.getJobVersion(),
                a.getEnvironment(),
                a.getTrigger() == null ? null : a.getTrigger().getValue(),
                a.getRerunOf() == null ? null : a.getRerunOf().toString(),
                retry,
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
