package de.fahrerabrechnung.data.card

/** USB CCID 1.1 section 4 bulk messages. Length is little endian, header is ten bytes. */
object CcidProtocol {
    const val HEADER_SIZE = 10
    const val MAX_PAYLOAD = 65544
    const val POWER_ON = 0x62
    const val POWER_OFF = 0x63
    const val SLOT_STATUS = 0x65
    const val XFR_BLOCK = 0x6F
    const val DATA_BLOCK = 0x80
    const val STATUS = 0x81

    data class Response(val type: Int, val slot: Int, val sequence: Int, val status: Int,
                        val error: Int, val chain: Int, val data: ByteArray) {
        val cardPresent: Boolean get() = (status and 3) != 2
        val timeExtension: Boolean get() = (status ushr 6) == 2
    }

    fun command(type: Int, slot: Int, sequence: Int, payload: ByteArray = byteArrayOf(), level: Int = 0): ByteArray {
        require(type in listOf(POWER_ON, POWER_OFF, SLOT_STATUS, XFR_BLOCK))
        require(slot in 0..255 && sequence in 0..255 && payload.size <= MAX_PAYLOAD)
        require(level in setOf(0, 1, 2, 3, 0x10) && (type == XFR_BLOCK || level == 0))
        val n = payload.size
        return byteArrayOf(type.toByte(), n.toByte(), (n ushr 8).toByte(),
            (n ushr 16).toByte(), (n ushr 24).toByte(), slot.toByte(), sequence.toByte(),
            0, level.toByte(), 0) + payload
    }

    fun expectedLength(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        val n = (header[1].toLong() and 255) or ((header[2].toLong() and 255) shl 8) or
            ((header[3].toLong() and 255) shl 16) or ((header[4].toLong() and 255) shl 24)
        if (n > MAX_PAYLOAD) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        return HEADER_SIZE + n.toInt()
    }

    fun response(bytes: ByteArray, type: Int, slot: Int, sequence: Int): Response {
        val total = expectedLength(bytes)
        if (bytes.size != total || (bytes[0].toInt() and 255) != type ||
            (bytes[5].toInt() and 255) != slot || (bytes[6].toInt() and 255) != sequence)
            throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        val status = bytes[7].toInt() and 255
        val error = bytes[8].toInt() and 255
        if ((status and 3) == 2) throw ReaderException(ReaderFailure.CARD_ABSENT)
        if ((status ushr 6) == 1) throw ReaderException(
            when (error) {
                0xFE -> ReaderFailure.TIMEOUT // ICC_MUTE (CCID 1.1 table 6.2-2)
                else -> ReaderFailure.IO
            })
        if ((status ushr 6) == 3) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        return Response(type, slot, sequence, status, error, bytes[9].toInt() and 255,
            bytes.copyOfRange(HEADER_SIZE, total))
    }
}

/** Extended-APDU CCID response chain. A 0x10 reply asks for more command bytes. */
class CcidResponseChain(private val maxBytes: Int = 65536) {
    private val bytes = ArrayList<Byte>()
    private var begun = false
    var complete = false
        private set

    fun add(response: CcidProtocol.Response): ByteArray? {
        if (complete || response.timeExtension) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        when (response.chain) {
            0 -> if (begun) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE) else complete = true
            1 -> if (begun) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE) else begun = true
            2, 3 -> if (!begun) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
                else if (response.chain == 2) complete = true
            else -> throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        }
        if (bytes.size + response.data.size > maxBytes) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        bytes.addAll(response.data.toList())
        return if (complete) bytes.toByteArray() else null
    }
}

/** Extended-APDU command chaining levels for a reader's advertised message size. */
object CcidCommandBlocks {
    data class Block(val data: ByteArray, val level: Int)

    fun split(apdu: ByteArray, maxMessage: Int, extendedApdu: Boolean): List<Block> {
        require(apdu.size in 4..65535)
        val size = maxMessage - CcidProtocol.HEADER_SIZE
        if (size <= 0 || (apdu.size > size && !extendedApdu))
            throw ReaderException(ReaderFailure.UNSUPPORTED_READER)
        val count = (apdu.size + size - 1) / size
        if (count > 128) throw ReaderException(ReaderFailure.UNSUPPORTED_READER)
        return (0 until count).map { part ->
            val level = when {
                count == 1 -> 0
                part == 0 -> 1
                part == count - 1 -> 2
                else -> 3
            }
            Block(apdu.copyOfRange(part * size, minOf(apdu.size, (part + 1) * size)), level)
        }
    }
}

/** CCID functional descriptor belongs to the preceding USB interface descriptor. */
data class CcidDescriptor(val slots: Int, val maxMessage: Int, val features: Long) {
    val extendedApdu: Boolean get() = (features and 0x00040000L) != 0L
    val apduExchange: Boolean get() = (features and 0x00060000L) != 0L

    companion object {
        fun parse(raw: ByteArray, interfaceNumber: Int): CcidDescriptor? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val size = raw[offset].toInt() and 255
                if (size < 2 || offset + size > raw.size) return null
                val type = raw[offset + 1].toInt() and 255
                if (type == 4 && size >= 9) currentInterface = raw[offset + 2].toInt() and 255
                if (type == 0x21 && size >= 54 && currentInterface == interfaceNumber) {
                    fun le32(at: Int) = (0..3).fold(0L) { acc, shift ->
                        acc or ((raw[offset + at + shift].toLong() and 255) shl (8 * shift))
                    }
                    val message = le32(44)
                    if (message !in 11..(CcidProtocol.MAX_PAYLOAD + 10).toLong()) return null
                    return CcidDescriptor((raw[offset + 4].toInt() and 255) + 1, message.toInt(), le32(40))
                }
                offset += size
            }
            return null
        }
    }
}

