package de.fahrerabrechnung.data.card

/** Calculation identifiers only. Persisted receipt kinds and fingerprint formats do not change. */
enum class CardSignatureFamily { CORE, IDENTITY, EVENT_FAULT }
enum class CardSignedEf(val fileId: Int, val family: CardSignatureFamily) {
    ACTIVITY(0x0504, CardSignatureFamily.CORE), VEHICLES(0x0505, CardSignatureFamily.CORE),
    APPLICATION(0x0501, CardSignatureFamily.IDENTITY), IDENTIFICATION(0x0520, CardSignatureFamily.IDENTITY),
    EVENTS(0x0502, CardSignatureFamily.EVENT_FAULT), FAULTS(0x0503, CardSignatureFamily.EVENT_FAULT),
    APPLICATION_V2(0x0525, CardSignatureFamily.IDENTITY);

    fun supports(generation: CardGeneration) = generation in
        setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2) &&
        (this != APPLICATION_V2 || generation == CardGeneration.GEN2V2)
}

fun CardCoreEf.signedEf(): CardSignedEf = when (this) {
    CardCoreEf.ACTIVITY -> CardSignedEf.ACTIVITY
    CardCoreEf.VEHICLES -> CardSignedEf.VEHICLES
}
fun CardIdentitySourceKind.signedEf(): CardSignedEf = when (this) {
    CardIdentitySourceKind.APPLICATION -> CardSignedEf.APPLICATION
    CardIdentitySourceKind.IDENTIFICATION -> CardSignedEf.IDENTIFICATION
    CardIdentitySourceKind.APPLICATION_V2 -> CardSignedEf.APPLICATION_V2
}
fun CardEventFaultKind.signedEf(): CardSignedEf = when (this) {
    CardEventFaultKind.EVENTS -> CardSignedEf.EVENTS
    CardEventFaultKind.FAULTS -> CardSignedEf.FAULTS
}

/** Exact immutable child/receipt provenance; a shared original blob has no global verdict. */
data class CardSignatureVerificationSource(
    val driverCardId: String, val sessionId: String, val batchId: String?, val ef: CardSignedEf,
    val generation: CardGeneration?, val application: CardApplication?,
    val receiptFingerprint: String?, val originalSourceId: String?, val certificateSourceId: String?,
    val issuerSourceId: String?, val originalDigest: String?, val signatureDigest: String?,
    val certificateDigest: String?, val issuerDigest: String?,
) {
    val family: CardSignatureFamily get() = ef.family
}

data class CardSignatureVerification(
    val source: CardSignatureVerificationSource, val status: CardSignatureMathStatus,
    val reason: CardSignatureMathReason? = null, val publicKeyFingerprint: String? = null,
    val recoveryCatalogFingerprint: String? = null,
) {
    val verifierVersion: String get() = "stored-signature-math-v1"
    val certificateTrust: CardCertificateTrustStatus get() = CardCertificateTrustStatus.NOT_EVALUATED
    init { require((status == CardSignatureMathStatus.NOT_CHECKABLE) == (reason != null)) }

    fun matches(sessionId: String?, cardId: String?, kind: CardIdentitySourceKind, receipt: CardIdentitySignature?): Boolean =
        matchesReceipt(sessionId, cardId, kind.signedEf(), receipt?.mathReceipt())
    fun matches(sessionId: String?, cardId: String?, kind: CardEventFaultKind, receipt: CardEventFaultSignature?): Boolean =
        matchesReceipt(sessionId, cardId, kind.signedEf(), receipt?.mathReceipt())

    private fun matchesReceipt(sessionId: String?, cardId: String?, ef: CardSignedEf, receipt: CardMathReceipt?): Boolean {
        if (sessionId == null || cardId != source.driverCardId || sessionId != source.sessionId || ef != source.ef) return false
        if (receipt == null) return status == CardSignatureMathStatus.NOT_CHECKABLE && reason == CardSignatureMathReason.NO_SIGNATURE &&
            source.receiptFingerprint == null && source.signatureDigest == null && source.certificateDigest == null
        return receipt.ef == ef && receipt.generation == source.generation &&
            source.signatureDigest == receipt.bytes?.let(::coreMathDigest) &&
            source.originalDigest == receipt.original?.let(::coreMathDigest) &&
            source.certificateDigest == receipt.certificate?.bytes?.let(::coreMathDigest)
    }

    /** Keep the 40127 API/result contract and all its existing tests intact. */
    fun coreVerification(): CardCoreSignatureVerification? {
        val kind = when (source.ef) {
            CardSignedEf.ACTIVITY -> CardCoreEf.ACTIVITY
            CardSignedEf.VEHICLES -> CardCoreEf.VEHICLES
            else -> return null
        }
        return CardCoreSignatureVerification(CardCoreVerificationSource(source.driverCardId, source.sessionId,
            source.batchId, kind, source.generation, source.application, source.receiptFingerprint,
            source.originalSourceId, source.certificateSourceId, source.issuerSourceId, source.originalDigest,
            source.signatureDigest, source.certificateDigest, source.issuerDigest), status, reason,
            publicKeyFingerprint, recoveryCatalogFingerprint)
    }
}

/** Frozen mathematical inputs from an already validated typed receipt; no decoder reconstruction. */
internal class CardMathReceipt(val ef: CardSignedEf, val generation: CardGeneration,
    val status: CardSignatureStatus, val policy: CardSignaturePolicy?, original: ByteArray?,
    certificate: CardCertificateFile?, bytes: ByteArray?) {
    private val raw = original?.copyOf()
    private val signature = bytes?.copyOf()
    val original: ByteArray? get() = raw?.copyOf()
    val bytes: ByteArray? get() = signature?.copyOf()
    val certificate = certificate?.let { CardCertificateFile(it.kind, it.generation, it.bytes) }
}
internal fun CardCoreSignature.mathReceipt() = CardMathReceipt(kind.signedEf(), generation, status, policy,
    original?.bytes, certificate, bytes)
internal fun CardIdentitySignature.mathReceipt() = CardMathReceipt(kind.signedEf(), generation, status, policy,
    original?.bytes, certificate, bytes)
internal fun CardEventFaultSignature.mathReceipt() = CardMathReceipt(kind.signedEf(), generation, status, policy,
    original?.bytes, certificate, bytes)

internal class SavedSignatureVerificationInput(val source: CardSignatureVerificationSource,
    val receipt: CardMathReceipt?, issuer: CardCertificateFile?) {
    val issuer = issuer?.let { CardCertificateFile(it.kind, it.generation, it.bytes) }
}
internal fun SavedCoreVerificationInput.signatureInput() = SavedSignatureVerificationInput(
    CardSignatureVerificationSource(source.driverCardId, source.sessionId, source.batchId, source.kind.signedEf(),
        source.generation, source.application, source.receiptFingerprint, source.originalSourceId,
        source.certificateSourceId, source.issuerSourceId, source.originalDigest, source.signatureDigest,
        source.certificateDigest, source.issuerDigest), signature?.mathReceipt(), issuer)

