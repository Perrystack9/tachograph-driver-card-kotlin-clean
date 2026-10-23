package de.fahrerabrechnung.data.card

/**
 * Offline mathematical verification of captured signatures, independent of persistence.
 *
 * A VALID result establishes signature mathematics only. Certificate trust, revocation,
 * expiry policy and a complete DDD container are not evaluated.
 *
 * [recoveryKeys] supplies the Gen1 public recovery catalog; omitted, null or empty
 * means no Gen1 recovery keys. No original ERCA keys are bundled or downloaded. Each
 * entry is exactly 144 bytes: reference (8), RSA modulus (128), exponent (8), unsigned
 * big-endian. These are public keys, never private keys or a trusted-root declaration.
 * With complete Gen1 captures but no matching recovery key, verification returns
 * NOT_CHECKABLE / UNKNOWN_GEN1_RECOVERY_KEY, not INVALID. Gen2/V2 needs no Gen1 key.
 */
class CardSignatureMath(recoveryKeys: List<ByteArray>? = null) {
    private val verifier = CardSignatureVerifier(recoveryKeys?.let(::Gen1RsaRecoveryKeyCatalog)
        ?: Gen1RsaRecoveryKeyCatalog.DEFAULT)

    /** Verify an activity or vehicle receipt. [issuer] is required for Gen1. */
    fun verify(receipt: CardCoreSignature, issuer: CardCertificateFile? = null): CardSignatureVerification =
        verifyReceipt(receipt.mathReceipt(), issuer)

    /** Verify an application-identification or holder-identification receipt. */
    fun verify(receipt: CardIdentitySignature, issuer: CardCertificateFile? = null): CardSignatureVerification =
        verifyReceipt(receipt.mathReceipt(), issuer)

    /** Verify an event or fault receipt. */
    fun verify(receipt: CardEventFaultSignature, issuer: CardCertificateFile? = null): CardSignatureVerification =
        verifyReceipt(receipt.mathReceipt(), issuer)

    private fun verifyReceipt(receipt: CardMathReceipt, issuer: CardCertificateFile?): CardSignatureVerification {
        val source = CardSignatureVerificationSource(
            driverCardId = "in-memory", sessionId = "in-memory", batchId = null,
            ef = receipt.ef, generation = receipt.generation,
            application = if (receipt.generation == CardGeneration.GEN1) CardApplication.GEN1 else CardApplication.GEN2,
            receiptFingerprint = null, originalSourceId = null, certificateSourceId = null, issuerSourceId = null,
            originalDigest = receipt.original?.let(::coreMathDigest),
            signatureDigest = receipt.bytes?.let(::coreMathDigest),
            certificateDigest = receipt.certificate?.bytes?.let(::coreMathDigest),
            issuerDigest = issuer?.bytes?.let(::coreMathDigest),
        )
        return verifier.verify(SavedSignatureVerificationInput(source, receipt, issuer))
    }
}
