package de.fahrerabrechnung.data.card

import java.security.MessageDigest

enum class CardSignatureMathStatus { NOT_RUN, VALID, INVALID, NOT_CHECKABLE }
enum class CardCertificateTrustStatus { NOT_EVALUATED }
enum class CardSignatureMathReason {
    NO_SIGNATURE, CAPTURE_FAILED, NOT_ATTEMPTED, NO_ORIGINAL, NO_SIGNING_CERTIFICATE,
    NO_GEN1_ISSUER, UNKNOWN_GEN1_RECOVERY_KEY, SOURCE_MISMATCH, CERTIFICATE_RECOVERY_FAILED,
    UNSUPPORTED_POLICY, INVALID_PUBLIC_KEY, ENGINE_ERROR
}

/** A calculation belongs to an exact receipt, never globally to a deduplicated file. */
data class CardCoreVerificationSource(
    val driverCardId: String, val sessionId: String, val batchId: String?, val kind: CardCoreEf,
    val generation: CardGeneration?, val application: CardApplication?,
    val receiptFingerprint: String?, val originalSourceId: String?, val certificateSourceId: String?,
    val issuerSourceId: String?, val originalDigest: String?, val signatureDigest: String?,
    val certificateDigest: String?, val issuerDigest: String?,
)

data class CardCoreSignatureVerification(
    val source: CardCoreVerificationSource, val status: CardSignatureMathStatus,
    val reason: CardSignatureMathReason? = null, val publicKeyFingerprint: String? = null,
    val recoveryCatalogFingerprint: String? = null,
) {
    val verifierVersion: String get() = "core-signature-math-v1"
    val certificateTrust: CardCertificateTrustStatus get() = CardCertificateTrustStatus.NOT_EVALUATED
    init { require((status == CardSignatureMathStatus.NOT_CHECKABLE) == (reason != null)) }

    /** Also guards a late UI result after source/application selection changes. */
    fun matches(sessionId: String?, cardId: String?, kind: CardCoreEf, receipt: CardCoreSignature?): Boolean {
        if (sessionId == null || cardId != source.driverCardId || sessionId != source.sessionId || kind != source.kind) return false
        if (receipt == null) return status == CardSignatureMathStatus.NOT_CHECKABLE && reason == CardSignatureMathReason.NO_SIGNATURE &&
            source.receiptFingerprint == null && source.signatureDigest == null && source.certificateDigest == null
        return receipt.kind == kind && receipt.generation == source.generation &&
            source.signatureDigest == receipt.bytes?.let(::coreMathDigest) &&
            source.originalDigest == receipt.original?.bytes?.let(::coreMathDigest) &&
            source.certificateDigest == receipt.certificate?.bytes?.let(::coreMathDigest)
    }
}

internal fun coreMathDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal class SavedCoreVerificationInput(val source: CardCoreVerificationSource,
    signature: CardCoreSignature?, issuer: CardCertificateFile?) {
    val signature = signature?.freeze()
    val issuer = issuer?.let { CardCertificateFile(it.kind, it.generation, it.bytes) }
}

