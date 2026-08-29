package com.tonycorreia.pricepulsebackend.infrastructure

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingProviderInvocationFailureObserverTest {

    private val operationId = ReceiptAnalysisOperationId("11111111-2222-3333-4444-555555555555")

    private fun observe(failure: Throwable): String {
        val lines = mutableListOf<String>()
        LoggingProviderInvocationFailureObserver { lines += it }
            .invocationFailedUnexpectedly(operationId, failure)
        assertEquals(1, lines.size, "exactly one line per failure")
        return lines.single()
    }

    @Test
    fun `names the operation and the exception type`() {
        val line = observe(IllegalStateException("connection refused"))

        assertContains(line, "operationId=11111111-2222-3333-4444-555555555555")
        assertContains(line, "failureType=java.lang.IllegalStateException")
    }

    @Test
    fun `never logs the exception message, which can quote a credential`() {
        // Regression guard for a real incident: Ktor's IllegalHeaderValueException embeds the whole
        // offending header, so logging the message printed the OpenAI API key in plain text.
        val leaky = IllegalStateException("Header value 'Bearer sk-proj-SECRET' contains illegal character")
        val line = observe(leaky)

        assertFalse(line.contains("sk-proj-SECRET"), "the credential must never reach a log line")
        assertFalse(line.contains("Bearer"), "no part of the exception message is rendered")
        assertFalse(line.contains(leaky.message!!))
    }

    @Test
    fun `never renders the exception object, so no stack frame can reach the log`() {
        val failure = IllegalStateException("boom")
        val line = observe(failure)

        assertFalse(line.contains("\tat "), "a stack trace could carry a destination or a header")
        assertFalse(line.contains(failure.toString()), "the exception object itself is never rendered")
        assertTrue(line.lines().size == 1, "one failure is one line")
    }
}
