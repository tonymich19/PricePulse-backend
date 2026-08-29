package com.tonycorreia.pricepulsebackend.infrastructure.persistence

/**
 * Persisted state that cannot be mapped back to a valid domain value -- an unrecognized code, a
 * document that fails [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1.from],
 * or a physical row combination the V1 schema's own triggers should have prevented but a reader
 * finds anyway. Never caught and converted into a fabricated `StartOutcome`/`ApplyOutcomeResult`,
 * never a reason to re-invoke the provider or produce a new credit effect -- always propagated to
 * the caller as a safe integrity failure. See receiptanalysis-slice-report.md 6.10.27.2.
 */
class ReceiptAnalysisPersistenceIntegrityException(message: String) : RuntimeException(message)
