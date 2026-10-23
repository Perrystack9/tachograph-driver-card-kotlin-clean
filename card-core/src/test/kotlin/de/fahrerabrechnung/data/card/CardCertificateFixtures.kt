package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer

/** Synthetic format fixture; deliberately not a cryptographically valid certificate. */
internal object CertificateFixtures {
    /** Synthetic framing matrix, including mixed issuer/subject curve widths. */
    fun samples(): List<ByteArray> = listOf(
        Triple("2A8648CE3D030107", 65, 64), Triple("2B2403030208010107", 65, 64),
        Triple("2B81040022", 97, 96), Triple("2B240303020801010B", 97, 96),
        Triple("2B240303020801010D", 129, 128), Triple("2B81040023", 133, 132),
        Triple("2B81040022", 97, 64), Triple("2B240303020801010D", 129, 96),
        Triple("2B81040023", 133, 128),
    ).map { (oid, width, signature) -> certificate(fields(oid, width), signature) }
    fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun tlv(tag: Int, bytes: ByteArray): ByteArray {
        val header = if (tag > 255) byteArrayOf((tag ushr 8).toByte(), tag.toByte()) else byteArrayOf(tag.toByte())
        val size = when {
            bytes.size < 128 -> byteArrayOf(bytes.size.toByte())
            bytes.size < 256 -> byteArrayOf(0x81.toByte(), bytes.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte())
        }
        return header + size + bytes
    }
    fun fields(oid: String = "2A8648CE3D030107", pointSize: Int = 65, code: Int = 17,
        effective: Long = 1790000000L, expires: Long = 1890000000L): List<ByteArray> = listOf(
        tlv(0x5F29, byteArrayOf(0)), tlv(0x42, ByteArray(8) { (it + 1).toByte() }),
        tlv(0x5F4C, DriverCardFiles.gen2Aid + code.toByte()),
        tlv(0x7F49, tlv(6, hex(oid)) + tlv(0x86, ByteArray(pointSize) { if (it == 0) 4 else 7 })),
        tlv(0x5F20, ByteArray(8) { (it + 9).toByte() }),
        tlv(0x5F25, ByteBuffer.allocate(4).putInt(effective.toInt()).array()),
        tlv(0x5F24, ByteBuffer.allocate(4).putInt(expires.toInt()).array()),
    )
    fun certificate(fields: List<ByteArray> = fields(), signatureSize: Int = 64): ByteArray =
        tlv(0x7F21, tlv(0x7F4E, fields.fold(byteArrayOf()) { all, field -> all + field }) +
            tlv(0x5F37, ByteArray(signatureSize) { 3 }))
}

