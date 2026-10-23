package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import java.time.Instant

/** Appendix2 TCS150/154: these are EFs in the selected application, never MF files. */
enum class CardCertificateKind(val fileId: Int) {
    CARD(0xC100), SIGNING(0xC101), CA(0xC108), LINK(0xC109);

    fun supports(generation: CardGeneration): Boolean = when (generation) {
        CardGeneration.GEN1 -> this == CARD || this == CA
        CardGeneration.GEN2, CardGeneration.GEN2V2 -> true
        else -> false
    }
    internal val storageKind: String get() = "CERTIFICATE_$name"
}

/** Complete original EF bytes. Decoding never replaces the encoded certificate body. */
class CardCertificateFile(val kind: CardCertificateKind, val generation: CardGeneration, bytes: ByteArray) {
    init {
        val lengthIsValid = if (generation == CardGeneration.GEN1) bytes.size == 194 else bytes.size in 204..341
        if (!kind.supports(generation) || !lengthIsValid)
            throw ParseError(0, "Certificate", "unsupported generation, kind or complete length")
    }
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

data class CardCertificateRead(val file: CardCertificateFile? = null, val readFailed: Boolean = false)

sealed interface CardCertificateRecord

/** Gen1's signed body is not plaintext. RSA message recovery is a separate future operation. */
data class CardRsaCertificateRecord(val signatureBytes: List<Int>, val remainderBytes: List<Int>,
    val authorityReferenceBytes: List<Int>) : CardCertificateRecord

/** Structural decoding only: neither an EC point check, signature check nor a trusted chain. */
data class CardEccCertificateRecord(val profileRaw: Int, val authorityReferenceBytes: List<Int>,
    val authorizationBytes: List<Int>, val curveOidBytes: List<Int>, val curveName: String,
    val publicPointBytes: List<Int>, val holderReferenceBytes: List<Int>,
    val effectiveTimeRaw: Long, val expirationTimeRaw: Long,
    val originalBodyBytes: List<Int>, val signatureBytes: List<Int>) : CardCertificateRecord {
    val effectiveTimeUtc: Instant? get() = recordedTime(effectiveTimeRaw)
    val expirationTimeUtc: Instant? get() = recordedTime(expirationTimeRaw)
    val equipmentTypeRaw: Int get() = authorizationBytes.last()
}

/** EU2016/799 Appendix11 CSM018/134..150, corrected by EU2018/502 CSM135.
 * No trust anchors, cryptographic verdict, inferred identity or card-writing operation. */
object CardCertificateParser {
    private val curves = mapOf(
        "2A8648CE3D030107" to ("NIST P-256" to 65),
        "2B2403030208010107" to ("Brainpool P256r1" to 65),
        "2B81040022" to ("NIST P-384" to 97),
        "2B240303020801010B" to ("Brainpool P384r1" to 97),
        "2B240303020801010D" to ("Brainpool P512r1" to 129),
        "2B81040023" to ("NIST P-521" to 133),
    )

    fun parse(file: CardCertificateFile): CardCertificateRecord? {
        val bytes = file.bytes
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 255.toByte() }) return null
        if (file.generation == CardGeneration.GEN1) return CardRsaCertificateRecord(
            bytes.copyOfRange(0, 128).unsigned(), bytes.copyOfRange(128, 186).unsigned(),
            bytes.copyOfRange(186, 194).unsigned())
        val outer = tlvs(bytes)
        requireFields(outer, listOf(0x7F21))
        val certificate = tlvs(outer.single().value)
        requireFields(certificate, listOf(0x7F4E, 0x5F37))
        val body = tlvs(certificate[0].value)
        requireFields(body, listOf(0x5F29, 0x42, 0x5F4C, 0x7F49, 0x5F20, 0x5F25, 0x5F24))
        for ((index, length) in listOf(0 to 1, 1 to 8, 2 to 7, 4 to 8, 5 to 4, 6 to 4))
            if (body[index].value.size != length) invalid("incorrect fixed field length")
        if (body[0].value[0] != 0.toByte() || !body[2].value.copyOfRange(0, 6).contentEquals(DriverCardFiles.gen2Aid))
            invalid("unsupported certificate profile or application")
        val key = tlvs(body[3].value)
        requireFields(key, listOf(0x06, 0x86))
        val oid = key[0].value.joinToString("") { "%02X".format(it.toInt() and 255) }
        val curve = curves[oid] ?: invalid("unsupported certificate curve")
        if (key[1].value.size != curve.second || key[1].value[0] != 4.toByte())
            invalid("incorrect uncompressed public point framing")
        // The issuer controls the signature size. It need not match the subject's curve.
        if (certificate[1].value.size !in setOf(64, 96, 128, 132)) invalid("incorrect plain signature length")
        fun time(index: Int) = ByteBuffer.wrap(body[index].value).int.toLong() and 0xFFFF_FFFFL
        return CardEccCertificateRecord(0, body[1].value.unsigned(), body[2].value.unsigned(),
            key[0].value.unsigned(), curve.first, key[1].value.unsigned(), body[4].value.unsigned(),
            time(5), time(6), certificate[0].encoded.unsigned(), certificate[1].value.unsigned())
    }

    private data class Tlv(val tag: Int, val value: ByteArray, val encoded: ByteArray)

    /** At most341 input bytes; one/two-byte tags and minimal definite DER lengths only. */
    private fun tlvs(bytes: ByteArray): List<Tlv> {
        val result = mutableListOf<Tlv>()
        var offset = 0
        fun byte(): Int {
            if (offset >= bytes.size) invalid("truncated tag or length")
            return bytes[offset++].toInt() and 255
        }
        while (offset < bytes.size) {
            val start = offset
            var tag = byte()
            if (tag and 31 == 31) {
                val next = byte()
                if (next !in 31..127) invalid("unsupported or nonminimal tag")
                tag = (tag shl 8) or next
            }
            var length = byte()
            if (length and 128 != 0) {
                val count = length and 127
                if (count !in 1..2) invalid("indefinite or oversized length")
                val first = byte()
                if (first == 0) invalid("leading zero length")
                length = if (count == 1) first else (first shl 8) or byte()
                if (length < 128 || (count == 2 && length < 256)) invalid("nonminimal length")
            }
            if (length > bytes.size - offset) invalid("truncated value")
            val end = offset + length
            result += Tlv(tag, bytes.copyOfRange(offset, end), bytes.copyOfRange(start, end))
            offset = end
        }
        return result
    }

    private fun requireFields(fields: List<Tlv>, tags: List<Int>) {
        if (fields.map { it.tag } != tags) invalid("missing, duplicate, reordered or extra field")
    }
    private fun ByteArray.unsigned(): List<Int> = map { it.toInt() and 255 }
    private fun invalid(reason: String): Nothing = throw ParseError(0, "Certificate", reason)
}

