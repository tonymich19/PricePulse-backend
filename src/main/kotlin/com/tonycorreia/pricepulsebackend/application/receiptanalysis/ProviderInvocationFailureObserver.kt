package com.tonycorreia.pricepulsebackend.application.receiptanalysis

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationId

/**
 * Receives the exception that made an invocation's outcome unknown, at the one place the
 * orchestration deliberately stops propagating it.
 *
 * Exists because that decision, correct on its own, produced a backend that could turn any failure
 * into `RECONCILING` leaving no trace at all: six operations were lost that way before anyone could
 * say what had thrown. Swallowing the exception is still right -- the orchestration must never
 * guess whether the provider ran -- but discarding the evidence is not.
 *
 * A port rather than a logger call so `application` keeps depending on nothing outside the JDK and
 * its own types; the logging implementation lives in `infrastructure`, exactly as
 * [ReceiptAnalysisProviderPort] and its OpenAI adapter do.
 *
 * Never called for a [kotlinx.coroutines.CancellationException] -- cancellation is control flow,
 * not a provider failure. An implementation that throws must never change the caller's outcome.
 */
fun interface ProviderInvocationFailureObserver {
    fun invocationFailedUnexpectedly(operationId: ReceiptAnalysisOperationId, failure: Throwable)
}
