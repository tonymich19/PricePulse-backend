package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [ReceiptAnalysisOperationLifecycle.Terminal] directly -- not through
 * [InMemoryReceiptAnalysisOperationStore] -- because [Terminal]'s only public constructor
 * parameter is [ReceiptAnalysisOperationResult.Succeeded]/[ReceiptAnalysisOperationResult.Failed]/
 * [ReceiptAnalysisOperationResult.FailedNoProvider]; ledgerEffect is always derived from it, never
 * suppliable separately, so no store implementation -- fake or real -- can ever construct an
 * incompatible pairing. See receiptanalysis-slice-report.md 6.10.20.
 */
class ReceiptAnalysisOperationLifecycleTest {

    private fun sampleDocument(): ValidatedReceiptAnalysisResultV1 {
        val projectRoot = File(System.getProperty("user.dir"))
        val fixture = File(projectRoot, "contracts/fixtures/valid/complete-with-items.json")
        return requireNotNull(ValidatedReceiptAnalysisResultV1.from(fixture.readBytes()))
    }

    @Test
    fun `Terminal(Succeeded) derives DEBITED and preserves the document`() {
        val document = sampleDocument()

        val terminal = ReceiptAnalysisOperationLifecycle.Terminal(ReceiptAnalysisOperationResult.Succeeded(document))

        assertEquals(ReceiptAnalysisLedgerEffect.DEBITED, terminal.ledgerEffect)
        val result = terminal.result as ReceiptAnalysisOperationResult.Succeeded
        assertEquals(document.serialize().toList(), result.document.serialize().toList())
    }

    @Test
    fun `Terminal(Failed) derives DEBITED`() {
        val terminal = ReceiptAnalysisOperationLifecycle.Terminal(ReceiptAnalysisOperationResult.Failed)

        assertEquals(ReceiptAnalysisLedgerEffect.DEBITED, terminal.ledgerEffect)
        assertEquals(ReceiptAnalysisOperationResult.Failed, terminal.result)
    }

    @Test
    fun `Terminal(FailedNoProvider) derives RELEASED and preserves the reason`() {
        val terminal = ReceiptAnalysisOperationLifecycle.Terminal(
            ReceiptAnalysisOperationResult.FailedNoProvider(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)
        )

        assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)
        val result = terminal.result as ReceiptAnalysisOperationResult.FailedNoProvider
        assertEquals(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE, result.reason)
    }
}
