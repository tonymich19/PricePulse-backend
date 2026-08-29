package com.tonycorreia.pricepulsebackend.application.receiptanalysis

/**
 * Backend-owned, genuinely immutable image ready to send to a [ReceiptAnalysisProviderPort]
 * implementation. Never accepts or exposes an Android type (ReceiptImageReference/Uri/Context).
 *
 * [mimeType] is metadata only -- not proof of content; real content validation already happened
 * upstream, during ingestion. [sizeBytes] is always derived from the actual stored bytes, never a
 * separately-passed field that could diverge from them.
 */
class PreparedReceiptImage private constructor(
    private val bytesCopy: ByteArray,
    val mimeType: String
) {
    val sizeBytes: Long get() = bytesCopy.size.toLong()

    /** Always a fresh copy -- mutating the returned array never affects this instance. */
    fun bytes(): ByteArray = bytesCopy.copyOf()

    companion object {
        /** Defensively copies [bytes] on construction -- mutating the original afterwards has no effect. */
        operator fun invoke(bytes: ByteArray, mimeType: String): PreparedReceiptImage =
            PreparedReceiptImage(bytes.copyOf(), mimeType)
    }
}
