package de.fahrerabrechnung.data.card

/** EU2016/799 Appendix2 TCS33,142..147: conditional transparent MF files, Gen2 only. */
enum class CardCommunicationKind(val fileId: Int, val minimumLength: Int, val maximumLength: Int) {
    ATR_INFO(0x2F01, 7, 128), EXTENDED_LENGTH(0x0006, 3, 3);
    val storageKind: String get() = "MASTER_COMMUNICATION_$name"
    fun supports(generation: CardGeneration): Boolean = generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2)
}

class CardCommunicationFile(val kind: CardCommunicationKind, val generation: CardGeneration, bytes: ByteArray) {
    private val captured = bytes.copyOf()
    val bytes: ByteArray get() = captured.copyOf()
    init {
        if (!kind.supports(generation) || captured.size !in kind.minimumLength..kind.maximumLength)
            throw ParseError(0, kind.name, "unsupported generation or complete file length")
    }
}

data class CardCommunicationRead(val file: CardCommunicationFile? = null, val readFailed: Boolean = false)
sealed interface CardCommunicationRecord
/** The ISO7816 template body is preserved, not interpreted as negotiated reader buffer sizes. */
data class CardAtrInfoRecord(val extendedLengthTemplateBytes: Int, val otherObjectCount: Int) : CardCommunicationRecord
data class CardExtendedLengthRecord(val protocolCodeRaw: Int) : CardCommunicationRecord {
    val supportsT0: Boolean? get() = when (protocolCodeRaw) { 0x10, 0x11 -> true; 0x01 -> false; else -> null }
    val supportsT1: Boolean? get() = when (protocolCodeRaw) { 0x01, 0x11 -> true; 0x10 -> false; else -> null }
}

object CardCommunicationParser {
    fun parse(file: CardCommunicationFile): CardCommunicationRecord? {
        val bytes = file.bytes
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() }) return null
        if (file.kind == CardCommunicationKind.EXTENDED_LENGTH) {
            if (bytes[0] != 2.toByte() || bytes[1] != 1.toByte())
                throw ParseError(0, file.kind.name, "invalid protocol data object")
            // Unknown protocol values remain unknown; they must not enable any new command.
            return CardExtendedLengthRecord(bytes[2].toInt() and 255)
        }
        var offset = 0
        var templateLength: Int? = null
        var others = 0
        fun next(): Int {
            if (offset >= bytes.size) throw ParseError(offset, file.kind.name, "truncated data object")
            return bytes[offset++].toInt() and 255
        }
        while (offset < bytes.size) {
            val first = next()
            // ISO7816 BER-TLV padding belongs to the exact original file, not the object body.
            if (first == 0 || first == 255) continue
            var tag = first
            if (first and 31 == 31) {
                val second = next()
                if (second and 127 == 0) throw ParseError(offset - 1, file.kind.name, "invalid extended tag")
                tag = tag shl 8 or second
                if (second and 128 != 0) {
                    val third = next()
                    if (third and 128 != 0) throw ParseError(offset - 1, file.kind.name, "unsupported extended tag")
                    tag = tag shl 8 or third
                }
            }
            val firstLength = next()
            val length = if (firstLength < 128) firstLength else {
                val width = firstLength and 127
                if (width !in 1..2) throw ParseError(offset - 1, file.kind.name, "unsupported indefinite or long length")
                var value = 0
                repeat(width) { value = value shl 8 or next() }
                value
            }
            if (length > bytes.size - offset) throw ParseError(offset, file.kind.name, "truncated object body")
            if (tag == 0x7F66) {
                if (templateLength != null || length == 0)
                    throw ParseError(offset, file.kind.name, "duplicate or empty extended length template")
                templateLength = length
            } else others++
            offset += length
        }
        return CardAtrInfoRecord(templateLength ?: throw ParseError(0, file.kind.name, "missing extended length template"), others)
    }

    /** Compact-TLV card capabilities (tag7, three bytes); unknown historical formats stay unknown. */
    fun advertisedBy(atr: Atr): Boolean? {
        val bytes = atr.historical
        if (bytes.isEmpty()) return null
        val category = bytes[0].toInt() and 255
        if (category !in setOf(0x00, 0x80)) return null
        val end = if (category == 0) bytes.size - 3 else bytes.size
        if (end < 1) return null
        var offset = 1
        var result: Boolean? = null
        while (offset < end) {
            val header = bytes[offset++].toInt() and 255
            if (header == 0) continue
            val length = header and 15
            if (length == 0 || length > end - offset) return null
            if (header ushr 4 == 7) {
                if (length != 3 || result != null) return null
                // CCID returns decoded character values, not electrical bit order. The
                // ISO7816 third function table uses bit7 for either TS convention.
                result = bytes[offset + 2].toInt() and 0x40 != 0
            }
            offset += length
        }
        return result
    }
}

/** All reads remain short SELECT/READ APDUs; an advertised feature never changes transport limits. */
internal object CardCommunicationReader {
    suspend fun read(session: ApduSession, kind: CardCommunicationKind, advertised: Boolean?): Pair<ByteArray?, Boolean> {
        val selected = session.send(CardCommands.selectFile(kind.fileId))
        if (!selected.success) {
            // Only an exact file-not-found response proves optional absence. IO/access failures do not.
            return null to !(selected.statusWord == 0x6A82 && selected.data.isEmpty() && advertised != true)
        }
        suspend fun exact(start: Int, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val requested = length - filled
                val reply = session.send(CardCommands.readBinary(start + filled, requested))
                if (!reply.success || reply.data.size !in 1..requested)
                    throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
                reply.data.copyInto(bytes, filled)
                filled += reply.data.size
            }
            return bytes
        }
        suspend fun exists(offset: Int): Boolean {
            val reply = session.send(CardCommands.readBinary(offset, 1), correctLength = false)
            if (reply.success && reply.data.size == 1) return true
            if (reply.data.isEmpty() && reply.statusWord in setOf(0x6700, 0x6B00, 0x6282)) return false
            throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        }
        val prefix = exact(0, kind.minimumLength)
        if (exists(kind.maximumLength)) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        var low = kind.minimumLength
        var high = kind.maximumLength
        while (low < high) {
            val midpoint = (low + high) / 2
            if (exists(midpoint)) low = midpoint + 1 else high = midpoint
        }
        return (prefix + exact(kind.minimumLength, low - kind.minimumLength)) to false
    }
}

