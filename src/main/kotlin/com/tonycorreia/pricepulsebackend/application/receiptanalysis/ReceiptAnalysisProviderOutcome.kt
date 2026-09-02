package com.tonycorreia.pricepulsebackend.application.receiptanalysis

/**
 * What [ReceiptAnalysisProviderPort] can report -- carries certainty of invocation, never a
 * credit decision. See receiptanalysis-slice-report.md 6.10.13.3 for the full rationale and the
 * corresponding orchestration/ledger transitions (6.6/6.7/6.10.3).
 */
sealed interface ReceiptAnalysisProviderOutcome {

    /** Document v1 already validated against the canonical schema -- call completed successfully. */
    data class Completed(val document: ValidatedReceiptAnalysisResultV1) : ReceiptAnalysisProviderOutcome

    /** Certainty that the provider was NEVER called. Orchestration may apply FAILED_NO_PROVIDER. */
    data class NotInvoked(val reason: ProviderCallFailureReason) : ReceiptAnalysisProviderOutcome

    /**
     * The call may have reached the provider, but there is no usable result. Always carries the
     * same [attemptId] the request was made with -- never null, never invented by the port.
     * Gives the orchestration evidence to mark RECONCILING; never decides the outcome itself.
     */
    data class InvocationUncertain(val attemptId: ReceiptAnalysisAttemptId) : ReceiptAnalysisProviderOutcome

    /**
     * The provider returned a response -- CONFIRMED invocation, never "not called" -- but the
     * response does not validate against the canonical contract. Product policy: orchestration
     * applies FAILED with a debit (6.6 step 4b), never SUCCEEDED, never a release. Carries no
     * claim about what happened inside the provider to produce that response.
     */
    data class InvocationConfirmedInvalidResponse(val attemptId: ReceiptAnalysisAttemptId) : ReceiptAnalysisProviderOutcome

    /**
     * The application received a definitive HTTP rejection from the provider -- CONFIRMED
     * invocation, distinct from both [NotInvoked] (never left this backend) and
     * [InvocationConfirmedInvalidResponse] (the provider returned a response, just not one that
     * validates). Product policy determines FAILED_NO_PROVIDER + RELEASED for this outcome
     * (6.10.31); this type carries no claim about what processing or cost the provider incurred to
     * produce the rejection. Deliberately never named "...BeforeProcessing" or similar: no
     * provider's public API documents whether any internal cost or processing was incurred for a
     * rejection like this, only that the request was refused. Always carries the same [attemptId]
     * the request was made with, checked against a mismatch exactly like [InvocationUncertain]/
     * [InvocationConfirmedInvalidResponse]. See receiptanalysis-slice-report.md 6.10.32.
     */
    data class InvocationRejected(
        val attemptId: ReceiptAnalysisAttemptId,
        val reason: ProviderRejectionReason
    ) : ReceiptAnalysisProviderOutcome

    /**
     * A response was received -- CONFIRMED invocation -- and conclusively has no usable
     * structured result: retries were attempted and exhausted inside this single port call
     * (never a second invocation from the orchestration), a billing/quota error, an unrecognized
     * response, or the provider's own `failed`/`cancelled` status. Product policy determines
     * FAILED_NO_PROVIDER + RELEASED for every reason in [ProviderTerminalFailureReason]
     * (6.10.36); this type carries no claim about what processing the provider performed beyond
     * what [reason] states. Always carries the same [attemptId] the request was made with,
     * checked against a mismatch exactly like the other confirmed outcomes. See
     * receiptanalysis-slice-report.md 6.10.37.
     */
    data class InvocationConfirmedTerminalFailure(
        val attemptId: ReceiptAnalysisAttemptId,
        val reason: ProviderTerminalFailureReason
    ) : ReceiptAnalysisProviderOutcome

    /**
     * A response was received -- CONFIRMED invocation -- but is itself non-terminal (`queued` or
     * `in_progress`): the provider may still be working on it. Never retried or resent from here
     * or from the orchestration -- retained for a future manual resolution (6.10.36), never a
     * caller-invented outcome. Always carries the same [attemptId] the request was made with,
     * checked against a mismatch exactly like the other confirmed outcomes. [correlationReference]
     * is the only durable way a future manual reconciliation could look up this specific pending
     * invocation -- without it, the reference would be irretrievably discarded the moment this
     * outcome is mapped (6.10.39). See receiptanalysis-slice-report.md 6.10.37/6.10.39.
     */
    data class InvocationConfirmedPendingResponse(
        val attemptId: ReceiptAnalysisAttemptId,
        val correlationReference: ProviderCorrelationReference
    ) : ReceiptAnalysisProviderOutcome
}

/**
 * Any of the 3 categories a terminal FAILED_NO_PROVIDER outcome can carry -- never mixed, never
 * confused: [ReceiptAnalysisProviderOutcome.NotInvoked] only ever accepts
 * [ProviderCallFailureReason]; [ReceiptAnalysisProviderOutcome.InvocationRejected] only ever
 * accepts [ProviderRejectionReason]; [ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure]
 * only ever accepts [ProviderTerminalFailureReason] (6.10.37). This is the common supertype
 * `OutcomeApplication.FailedNoProvider`/`ReceiptAnalysisOperationResult.FailedNoProvider` carry, so
 * the persisted terminal result always preserves exactly which one actually happened.
 * `FAILED_NO_PROVIDER` + `RELEASED` is a PricePulse product policy applied uniformly across all 3
 * categories -- never a claim about whether the provider performed model inference or incurred
 * cost in any of them. See receiptanalysis-slice-report.md 6.10.32/6.10.37.
 */
sealed interface FailedNoProviderReason

/** Technical categories for a failure before any chance the call could have gone out. */
enum class ProviderCallFailureReason : FailedNoProviderReason {
    REQUEST_CONSTRUCTION_FAILED,
    PROVIDER_CREDENTIAL_UNAVAILABLE
}

/**
 * A definitive HTTP rejection the provider returned -- the request left this backend and reached
 * the provider, but was refused outright without producing a usable result: a malformed request,
 * rejected authentication, or a forbidden call. Product policy determines FAILED_NO_PROVIDER +
 * RELEASED for these categories (6.10.31); this type carries no claim about what internal
 * processing or cost the provider incurred. See receiptanalysis-slice-report.md 6.10.32.
 */
enum class ProviderRejectionReason : FailedNoProviderReason {
    MALFORMED_REQUEST,
    AUTHENTICATION_REJECTED,
    ACCESS_FORBIDDEN
}

/**
 * Why a confirmed provider response conclusively produced no usable result -- distinct from
 * [ProviderRejectionReason] (a synchronous rejection before any response) and from
 * [ProviderCallFailureReason] (the call never left this backend). Provider-neutral: none of these
 * values name OpenAI, an HTTP status, or an SDK type. [RETRY_EXHAUSTED] is only ever produced by
 * an adapter's own internal retry attempts inside a single port call -- never a signal that this
 * orchestration retries anything itself; [RATE_LIMITED] and [TRANSIENT_PROVIDER_FAILURE] describe
 * only a confirmed provider response under a single, zero-retry outbound attempt -- neither is
 * used once an adapter actually performs and exhausts internal retries (that stays
 * [RETRY_EXHAUSTED]), and neither claims that model inference or billing occurred. See
 * receiptanalysis-slice-report.md 6.10.36/6.10.37/6.10.41.
 */
enum class ProviderTerminalFailureReason : FailedNoProviderReason {
    RETRY_EXHAUSTED,
    BILLING_OR_QUOTA_EXHAUSTED,
    UNRECOGNIZED_RESPONSE,
    RESPONSE_FAILED,
    RESPONSE_CANCELLED,
    RATE_LIMITED,
    TRANSIENT_PROVIDER_FAILURE
}
