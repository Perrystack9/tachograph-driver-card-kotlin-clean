package de.fahrerabrechnung.data.card

import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner

internal open class CoreSignatureCrypto {
    fun rsa(key: Gen1RsaPublicKey, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 128) return false
        val s = BigInteger(1, signature)
        if (s.signum() <= 0 || s >= key.modulus) return false
        val prefix = "3021300906052b0e03021a05000414".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val digestInfo = prefix + MessageDigest.getInstance("SHA-1").digest(data)
        val expected = byteArrayOf(0, 1) + ByteArray(128 - digestInfo.size - 3) { -1 } + byteArrayOf(0) + digestInfo
        return MessageDigest.isEqual(expected, rsaPublicBlock(key, signature))
    }

    fun ecKey(policy: CardSignaturePolicy, bytes: ByteArray): ECPublicKeyParameters? {
        val parameters = policy.oid?.let { ECNamedCurveTable.getByOID(ASN1ObjectIdentifier(hexOid(it))) } ?: return null
        val coordinateWidth = (parameters.curve.fieldSize + 7) / 8
        if (bytes.size != 1 + 2 * coordinateWidth || bytes[0] != 4.toByte()) return null
        return try {
            val x = BigInteger(1, bytes.copyOfRange(1, 1 + coordinateWidth))
            val y = BigInteger(1, bytes.copyOfRange(1 + coordinateWidth, bytes.size))
            val curve = parameters.curve
            if (!curve.isValidFieldElement(x) || !curve.isValidFieldElement(y)) return null
            val point = curve.validatePoint(x, y)
            if (point.isInfinity || !point.isValid || !point.multiply(parameters.n).isInfinity) return null
            ECPublicKeyParameters(point, ECDomainParameters(parameters))
        } catch (_: IllegalArgumentException) { null }
    }

    open fun ec(policy: CardSignaturePolicy, key: ECPublicKeyParameters, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != policy.length) return false
        val width = policy.length / 2
        val r = BigInteger(1, signature.copyOfRange(0, width))
        val s = BigInteger(1, signature.copyOfRange(width, signature.size))
        if (r.signum() <= 0 || s.signum() <= 0 || r >= key.parameters.n || s >= key.parameters.n) return false
        val verifier = ECDSASigner()
        verifier.init(false, key)
        return verifier.verifySignature(MessageDigest.getInstance(policy.hash).digest(data), r, s)
    }

    // Only the six existing Card_Sign domains are accepted; no arbitrary parameters.
    private fun hexOid(hex: String): String = when (hex) {
        "2A8648CE3D030107" -> "1.2.840.10045.3.1.7"
        "2B2403030208010107" -> "1.3.36.3.3.2.8.1.1.7"
        "2B81040022" -> "1.3.132.0.34"
        "2B240303020801010B" -> "1.3.36.3.3.2.8.1.1.11"
        "2B240303020801010D" -> "1.3.36.3.3.2.8.1.1.13"
        "2B81040023" -> "1.3.132.0.35"
        else -> error("Unsupported Card_Sign domain")
    }
}

internal class CardCoreSignatureVerifier(
    recoveryKeys: Gen1RsaRecoveryKeyCatalog = Gen1RsaRecoveryKeyCatalog.DEFAULT,
    crypto: CoreSignatureCrypto = CoreSignatureCrypto(),
) {
    private val verifier = CardSignatureVerifier(recoveryKeys, crypto)
    fun verify(input: SavedCoreVerificationInput): CardCoreSignatureVerification =
        checkNotNull(verifier.verify(input.signatureInput()).coreVerification())
}

