package de.fahrerabrechnung.data.card

import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters

internal class Gen1RsaPublicKey(reference: List<Int>, val modulus: BigInteger, val exponent: BigInteger) {
    private val referenceBytes = reference.toList()
    val reference: List<Int> get() = referenceBytes.toList()
    init {
        require(reference.size == 8 && reference.all { it in 0..255 })
        require(modulus.bitLength() == 1024 && modulus.testBit(0))
        require(exponent >= BigInteger.valueOf(3) && exponent.testBit(0) && exponent < modulus)
    }
    val fingerprint: String get() = coreMathDigest(reference.map(Int::toByte).toByteArray() +
        fixedUnsigned(modulus, 128) + fixedUnsigned(exponent, 8))
}

/** Public recovery material only: no trusted-root role, online lookup or time policy. */
internal class Gen1RsaRecoveryKeyCatalog(bytes: List<ByteArray>) {
    private val encoded = bytes.map { it.copyOf().also { b -> require(b.size == 144) } }
    private val keys = encoded.map { b -> Gen1RsaPublicKey(b.copyOfRange(0, 8).unsignedList(),
        BigInteger(1, b.copyOfRange(8, 136)), BigInteger(1, b.copyOfRange(136, 144))) }
    init { require(keys.map { it.reference }.distinct().size == keys.size) }
    val fingerprint: String = coreMathDigest(encoded.sortedBy { coreMathDigest(it) }.fold(byteArrayOf()) { a, b -> a + b })
    fun find(reference: ByteArray): Gen1RsaPublicKey? = keys.singleOrNull { it.reference == reference.unsignedList() }

    companion object {
        /** Empty by design: applications supply any public Gen1 recovery material. */
        val DEFAULT: Gen1RsaRecoveryKeyCatalog = Gen1RsaRecoveryKeyCatalog(emptyList())
    }
}

internal object Gen1RsaCertificateRecovery {
    /** CSM017–019 only. Certificate framing is not a PKCS1 file signature. */
    fun recover(bytes: ByteArray, issuer: Gen1RsaPublicKey, equipmentType: Int): Gen1RsaPublicKey? {
        if (bytes.size != 194 || !bytes.copyOfRange(186, 194).unsignedList().equals(issuer.reference)) return null
        val sign = bytes.copyOfRange(0, 128)
        val representative = BigInteger(1, sign)
        if (representative.signum() <= 0 || representative >= issuer.modulus) return null
        val recovered = rsaPublicBlock(issuer, sign)
        if (recovered[0] != 0x6A.toByte() || recovered[127] != 0xBC.toByte()) return null
        val content = recovered.copyOfRange(1, 107) + bytes.copyOfRange(128, 186)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-1").digest(content), recovered.copyOfRange(107, 127))) return null
        if (content[0] != 1.toByte() || !content.copyOfRange(1, 9).unsignedList().equals(issuer.reference) ||
            !content.copyOfRange(9, 15).contentEquals(byteArrayOf(-1, 0x54, 0x41, 0x43, 0x48, 0x4F)) ||
            content[15].toInt() and 255 != equipmentType) return null
        return try { Gen1RsaPublicKey(content.copyOfRange(20, 28).unsignedList(),
            BigInteger(1, content.copyOfRange(28, 156)), BigInteger(1, content.copyOfRange(156, 164))) }
        catch (_: IllegalArgumentException) { null }
        // EOV is signed content; no expiry/trust decision is made in this mathematical step.
    }
}

internal fun rsaPublicBlock(key: Gen1RsaPublicKey, signature: ByteArray): ByteArray {
    val engine = RSAEngine()
    engine.init(false, RSAKeyParameters(false, key.modulus, key.exponent))
    val result = engine.processBlock(signature, 0, signature.size)
    require(result.size <= 128)
    return ByteArray(128 - result.size) + result
}
internal fun fixedUnsigned(value: BigInteger, width: Int): ByteArray {
    require(value.signum() >= 0 && value.bitLength() <= width * 8)
    val bytes = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    require(bytes.size <= width)
    return ByteArray(width - bytes.size) + bytes
}
internal fun ByteArray.unsignedList(): List<Int> = map { it.toInt() and 255 }

