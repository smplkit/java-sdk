package com.smplkit.jobs;

import com.smplkit.internal.generated.jobs.ApiException;
import com.smplkit.internal.generated.jobs.api.RetryPoliciesApi;
import com.smplkit.internal.generated.jobs.model.RetryPolicyCreateRequest;
import com.smplkit.internal.generated.jobs.model.RetryPolicyCreateResource;
import com.smplkit.internal.generated.jobs.model.RetryPolicyListResponse;
import com.smplkit.internal.generated.jobs.model.RetryPolicyRequest;
import com.smplkit.internal.generated.jobs.model.RetryPolicyResource;
import com.smplkit.internal.generated.jobs.model.RetryPolicyResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Manage reusable retry policies ({@code jobs.retryPolicies}).
 *
 * <p>A {@link RetryPolicy} is an active record: build one with
 * {@link #new_}, set fields, and call {@code save()}; then reference it from a
 * job's {@code retryPolicy} (see {@link Job#setRetryPolicy(RetryPolicy, String)}).
 * Retry policies are account-global — never environment-scoped.</p>
 */
public final class RetryPoliciesClient {

    private final RetryPoliciesApi api;

    RetryPoliciesClient(RetryPoliciesApi api) {
        this.api = api;
    }

    /**
     * Return an unsaved {@link RetryPolicy} with an empty {@link RetryOn}
     * (retries nothing) and no {@code maxDelaySeconds}. Call {@code .save()} to
     * create it.
     *
     * @param id caller-supplied unique identifier for the policy. Unique within
     *     the account and immutable; the service returns 409 if another live
     *     policy already uses this id.
     * @param name human-readable name for the policy
     * @param maxRetries how many times a failed run is retried after the
     *     initial attempt — {@code 3} means up to 4 attempts total. {@code 0}
     *     disables retries. Maximum 10.
     * @param backoff how the wait between retries grows (see {@link Backoff})
     * @param delaySeconds the wait before a retry, in seconds — the constant
     *     wait for {@code FIXED} backoff, or the base that doubles each retry
     *     for {@code EXPONENTIAL}
     * @return an unsaved {@link RetryPolicy} bound to this client
     */
    public RetryPolicy new_(String id, String name, int maxRetries, Backoff backoff, int delaySeconds) {
        return new RetryPolicy(this, id, name, maxRetries, backoff, delaySeconds);
    }

    /**
     * Return an unsaved {@link RetryPolicy}, setting every field at
     * construction. Call {@code .save()} to create it.
     *
     * @param id caller-supplied unique identifier for the policy. Unique within
     *     the account and immutable; the service returns 409 if another live
     *     policy already uses this id.
     * @param name human-readable name for the policy
     * @param maxRetries how many times a failed run is retried after the
     *     initial attempt — {@code 3} means up to 4 attempts total. {@code 0}
     *     disables retries. Maximum 10.
     * @param backoff how the wait between retries grows (see {@link Backoff})
     * @param delaySeconds the wait before a retry, in seconds — the constant
     *     wait for {@code FIXED} backoff, or the base that doubles each retry
     *     for {@code EXPONENTIAL}
     * @param maxDelaySeconds ceiling on the wait between retries, for
     *     {@code EXPONENTIAL} backoff only. {@code null} leaves it uncapped;
     *     omit it for {@code FIXED} backoff
     * @param retryOn which failures to retry (see {@link RetryOn}). {@code null}
     *     retries nothing
     * @return an unsaved {@link RetryPolicy} bound to this client
     */
    public RetryPolicy new_(String id, String name, int maxRetries, Backoff backoff, int delaySeconds,
                            Integer maxDelaySeconds, RetryOn retryOn) {
        RetryPolicy policy = new RetryPolicy(this, id, name, maxRetries, backoff, delaySeconds);
        policy.maxDelaySeconds = maxDelaySeconds;
        if (retryOn != null) {
            policy.retryOn = retryOn;
        }
        return policy;
    }

    /**
     * List retry policies in the account using default paging.
     *
     * @return the policies in the first page, as a list of {@link RetryPolicy}
     * @throws ApiException if the request fails
     */
    public List<RetryPolicy> list() throws ApiException {
        return list(new ListRetryPoliciesInput());
    }

    /**
     * List retry policies in the account.
     *
     * @param input filters and paging for the listing. {@code name} returns only
     *     policies whose name contains that text ({@code null} lists all);
     *     {@code pageNumber} is the 1-based page ({@code null} returns the first
     *     page); {@code pageSize} is the max policies per page ({@code null} uses
     *     the server default)
     * @return the policies in this page, as a list of {@link RetryPolicy}
     * @throws ApiException if the request fails
     */
    public List<RetryPolicy> list(ListRetryPoliciesInput input) throws ApiException {
        RetryPolicyListResponse resp =
                api.listRetryPolicies(input.name, null, input.pageNumber, input.pageSize, null);
        List<RetryPolicy> out = new ArrayList<>();
        if (resp.getData() != null) {
            for (RetryPolicyResource r : resp.getData()) out.add(fromResource(r));
        }
        return out;
    }

    /**
     * Fetch a single retry policy by its id; the returned instance is bound to
     * this client so {@code policy.save()} and {@code policy.delete()}
     * round-trip back here.
     *
     * @param id identifier of the policy to fetch
     * @return the matching {@link RetryPolicy}
     * @throws ApiException if no policy with that id exists, or if the request
     *     otherwise fails
     */
    public RetryPolicy get(String id) throws ApiException {
        RetryPolicyResponse resp = api.getRetryPolicy(id);
        return fromResource(resp.getData());
    }

    /**
     * Delete a retry policy by its id.
     *
     * @param id identifier of the policy to delete
     * @throws ApiException if no policy with that id exists, or if the request
     *     otherwise fails
     */
    public void delete(String id) throws ApiException {
        api.deleteRetryPolicy(id);
    }

    // ------------------------------------------------------------------
    // Active-record helpers (called by RetryPolicy.save)
    // ------------------------------------------------------------------

    RetryPolicy create(RetryPolicy policy) throws ApiException {
        if (policy.id == null) {
            throw new IllegalStateException(
                    "cannot create a RetryPolicy with no id (caller must supply a stable key)");
        }
        RetryPolicyResponse resp = api.createRetryPolicy(wrapCreateRequest(policy));
        return fromResource(resp.getData());
    }

    RetryPolicy update(RetryPolicy policy) throws ApiException {
        if (policy.id == null) {
            throw new IllegalStateException("cannot update a RetryPolicy with no id");
        }
        RetryPolicyResponse resp = api.updateRetryPolicy(policy.id, wrapRequest(policy.id, policy));
        return fromResource(resp.getData());
    }

    // ------------------------------------------------------------------
    // Wire <-> wrapper conversions
    // ------------------------------------------------------------------

    private static com.smplkit.internal.generated.jobs.model.RetryPolicy genAttrs(RetryPolicy policy) {
        com.smplkit.internal.generated.jobs.model.RetryPolicy attrs =
                new com.smplkit.internal.generated.jobs.model.RetryPolicy();
        attrs.setName(policy.name);
        attrs.setMaxRetries(policy.maxRetries);
        attrs.setBackoff(com.smplkit.internal.generated.jobs.model.RetryPolicy.BackoffEnum
                .fromValue(policy.backoff.getValue()));
        attrs.setDelaySeconds(policy.delaySeconds);
        // retry_on is always sent (an empty RetryOn retries nothing).
        attrs.setRetryOn(JobsConversions.retryOnToGen(
                policy.retryOn != null ? policy.retryOn : new RetryOn()));
        // max_delay_seconds is exponential-only; omit it on the wire when unset.
        if (policy.maxDelaySeconds != null) {
            attrs.setMaxDelaySeconds(policy.maxDelaySeconds);
        }
        return attrs;
    }

    private static RetryPolicyRequest wrapRequest(String id, RetryPolicy policy) {
        RetryPolicyResource r = new RetryPolicyResource();
        r.setId(id);
        r.setType("retry_policy");
        r.setAttributes(genAttrs(policy));
        RetryPolicyRequest body = new RetryPolicyRequest();
        body.setData(r);
        return body;
    }

    private static RetryPolicyCreateRequest wrapCreateRequest(RetryPolicy policy) {
        // Create uses a dedicated envelope where the caller-supplied id is required.
        RetryPolicyCreateResource r = new RetryPolicyCreateResource();
        r.setId(policy.id);
        r.setType(RetryPolicyCreateResource.TypeEnum.RETRY_POLICY);
        r.setAttributes(genAttrs(policy));
        RetryPolicyCreateRequest body = new RetryPolicyCreateRequest();
        body.setData(r);
        return body;
    }

    private RetryPolicy fromResource(RetryPolicyResource r) {
        com.smplkit.internal.generated.jobs.model.RetryPolicy a = r.getAttributes();
        RetryPolicy policy = new RetryPolicy(
                this, r.getId(), a.getName(), a.getMaxRetries(),
                Backoff.fromValue(a.getBackoff().getValue()), a.getDelaySeconds());
        policy.maxDelaySeconds = a.getMaxDelaySeconds();
        policy.retryOn = JobsConversions.retryOnFromGen(a.getRetryOn());
        policy.createdAt = a.getCreatedAt();
        policy.updatedAt = a.getUpdatedAt();
        policy.deletedAt = a.getDeletedAt();
        policy.version = a.getVersion();
        return policy;
    }
}
