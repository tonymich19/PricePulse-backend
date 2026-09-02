package com.tonycorreia.pricepulsebackend.infrastructure

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderInvocationFailureObserver
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationId
import org.slf4j.LoggerFactory

/**
 * The single production destination of "the invocation's outcome is unknown, and this is why".
 *
 * Logs the exception's **type only** -- never its message and never the object. The failure
 * reaching here originated in the provider adapter, the component that holds the credential, and an
 * HTTP client's message routinely quotes the offending header or request material: Ktor's
 * IllegalHeaderValueException embeds the whole header value, which would print the API key in plain
 * text. That is not hypothetical -- it happened, and cost a key rotation. The type plus the
 * operation id is what an operator actually needs to identify and locate the fault. The operation id is included because it is the only
 * identifier that lets an operator join this line to the stuck row; it is a server-generated UUID,
 * not a user identifier or anything the caller supplied.
 *
 * [emit] is injectable purely so a test can assert the composed line without capturing an appender,
 * mirroring [com.tonycorreia.pricepulsebackend.infrastructure.openai.LoggingOpenAiInvocationTelemetrySink].
 */
class LoggingProviderInvocationFailureObserver(
    private val emit: (String) -> Unit = { line -> LoggerFactory.getLogger(LOGGER_NAME).warn(line) }
) : ProviderInvocationFailureObserver {

    override fun invocationFailedUnexpectedly(operationId: ReceiptAnalysisOperationId, failure: Throwable) {
        emit(
            "receipt analysis invocation failed unexpectedly; outcome unknown, operation left RECONCILING " +
                "operationId=${operationId.value} " +
                "failureType=${failure::class.qualifiedName ?: failure::class.java.name}"
        )
    }

    private companion object {
        private const val LOGGER_NAME = "pricepulse.receiptanalysis.failure"
    }
}
