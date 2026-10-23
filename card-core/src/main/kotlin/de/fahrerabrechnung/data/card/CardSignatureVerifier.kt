package de.fahrerabrechnung.data.card

import java.util.concurrent.CancellationException

internal class CardSignatureVerifier(
    private val recoveryKeys: Gen1RsaRecoveryKeyCatalog = Gen1RsaRecoveryKeyCatalog.DEFAULT,
    private val crypto: CoreSignatureCrypto = CoreSignatureCrypto(),
) {
    fun verify(input: SavedSignatureVerificationInput): CardSignatureVerification {
        val source = input.source
        fun unavailable(reason: CardSignatureMathReason) = CardSignatureVerification(source, CardSignatureMathStatus.NOT_CHECKABLE, reason)
        val signature = input.receipt ?: return unavailable(CardSignatureMathReason.NO_SIGNATURE)
        if (source.ef != signature.ef || !signature.ef.supports(signature.generation) || source.generation != signature.generation ||
            (signature.certificate != null && signature.certificate.generation != signature.generation) ||
            source.application != (if (signature.generation == CardGeneration.GEN1) CardApplication.GEN1 else CardApplication.GEN2) ||
            source.signatureDigest != signature.bytes?.let(::coreMathDigest) ||
            source.originalDigest != signature.original?.let(::coreMathDigest) ||
            source.certificateDigest != signature.certificate?.bytes?.let(::coreMathDigest) ||
            source.issuerDigest != input.issuer?.bytes?.let(::coreMathDigest)) return unavailable(CardSignatureMathReason.SOURCE_MISMATCH)
        if (signature.status != CardSignatureStatus.CAPTURED_UNVERIFIED) return unavailable(
            if (signature.status == CardSignatureStatus.FAILED) CardSignatureMathReason.CAPTURE_FAILED else CardSignatureMathReason.NOT_ATTEMPTED)
        val original = signature.original ?: return unavailable(CardSignatureMathReason.NO_ORIGINAL)
        val cert = signature.certificate ?: return unavailable(CardSignatureMathReason.NO_SIGNING_CERTIFICATE)
        val policy = signature.policy ?: return unavailable(CardSignatureMathReason.UNSUPPORTED_POLICY)
        val bytes = signature.bytes ?: return unavailable(CardSignatureMathReason.NO_SIGNATURE)
        return try {
            val valid: Boolean
            val keyFingerprint: String
            var catalogFingerprint: String? = null
            if (signature.generation == CardGeneration.GEN1) {
                if (policy != CardSignaturePolicy.RSA_SHA1 || cert.kind != CardCertificateKind.CARD) return unavailable(CardSignatureMathReason.UNSUPPORTED_POLICY)
                val issuerFile = input.issuer ?: return unavailable(CardSignatureMathReason.NO_GEN1_ISSUER)
                if (issuerFile.kind != CardCertificateKind.CA || issuerFile.generation != CardGeneration.GEN1) return unavailable(CardSignatureMathReason.SOURCE_MISMATCH)
                val root = recoveryKeys.find(issuerFile.bytes.copyOfRange(186, 194)) ?: return unavailable(CardSignatureMathReason.UNKNOWN_GEN1_RECOVERY_KEY)
                val issuer = Gen1RsaCertificateRecovery.recover(issuerFile.bytes, root, 0) ?: return unavailable(CardSignatureMathReason.CERTIFICATE_RECOVERY_FAILED)
                val key = Gen1RsaCertificateRecovery.recover(cert.bytes, issuer, 1) ?: return unavailable(CardSignatureMathReason.CERTIFICATE_RECOVERY_FAILED)
                valid = crypto.rsa(key, original, bytes)
                keyFingerprint = key.fingerprint
                catalogFingerprint = recoveryKeys.fingerprint
            } else {
                if (cert.kind != CardCertificateKind.SIGNING || policy == CardSignaturePolicy.RSA_SHA1) return unavailable(CardSignatureMathReason.UNSUPPORTED_POLICY)
                val parsed = CardCertificateParser.parse(cert) as? CardEccCertificateRecord ?: return unavailable(CardSignatureMathReason.NO_SIGNING_CERTIFICATE)
                if (parsed.equipmentTypeRaw != 17 || CardSignaturePolicy.from(signature.generation, cert) != policy) return unavailable(CardSignatureMathReason.UNSUPPORTED_POLICY)
                val point = parsed.publicPointBytes.map(Int::toByte).toByteArray()
                val key = crypto.ecKey(policy, point) ?: return unavailable(CardSignatureMathReason.INVALID_PUBLIC_KEY)
                valid = crypto.ec(policy, key, original, bytes)
                keyFingerprint = coreMathDigest(policy.name.toByteArray(Charsets.US_ASCII) + point)
            }
            CardSignatureVerification(source, if (valid) CardSignatureMathStatus.VALID else CardSignatureMathStatus.INVALID,
                publicKeyFingerprint = keyFingerprint, recoveryCatalogFingerprint = catalogFingerprint)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { unavailable(CardSignatureMathReason.ENGINE_ERROR) }
    }
}

