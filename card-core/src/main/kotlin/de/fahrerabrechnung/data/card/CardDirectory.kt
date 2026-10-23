package de.fahrerabrechnung.data.card

/** EU2016/799 Annex IC Appendix2 TCS142..145: transparent MF EF DIR, exactly20 bytes. */
class CardDirectoryFile(val generation: CardGeneration, bytes: ByteArray) {
    private val captured = bytes.copyOf()
    val bytes: ByteArray get() = captured.copyOf()
    init {
        require(generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2))
        require(captured.size == CardDirectoryParser.FILE_LENGTH)
    }
}

enum class CardDirectoryApplication { TACHOGRAPH_GEN1, TACHOGRAPH_GEN2 }
data class CardDirectoryRecord(val applications: List<CardDirectoryApplication>)

object CardDirectoryParser {
    const val FILE_LENGTH = 20
    fun parse(file: CardDirectoryFile): CardDirectoryRecord? {
        val bytes = file.bytes
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() }) return null
        // These two ordered61/4F objects are the prescribed DIR format, not arbitrary discovery.
        val aids = listOf(DriverCardFiles.gen1Aid, DriverCardFiles.gen2Aid)
        for ((index, aid) in aids.withIndex()) {
            val offset = index * 10
            for ((part, expected) in listOf(0x61, 8, 0x4F, 6).withIndex())
                if (bytes[offset + part].toInt() and 255 != expected)
                    throw ParseError(offset + part, "DIR", "invalid application template")
            if (!bytes.copyOfRange(offset + 4, offset + 10).contentEquals(aid))
                throw ParseError(offset + 4, "DIR", "unexpected application identifier")
        }
        return CardDirectoryRecord(listOf(CardDirectoryApplication.TACHOGRAPH_GEN1, CardDirectoryApplication.TACHOGRAPH_GEN2))
    }
}

/** Read while MF is still selected. Recognition and own-card binding remain independent. */
internal object CardDirectoryReader {
    const val FILE_ID = 0x2F00
    suspend fun read(session: ApduSession): ByteArray {
        if (!session.send(CardCommands.selectFile(FILE_ID)).success) throw ReaderException(ReaderFailure.IO)
        val bytes = ByteArray(CardDirectoryParser.FILE_LENGTH)
        var offset = 0
        while (offset < bytes.size) {
            val requested = bytes.size - offset
            val reply = session.send(CardCommands.readBinary(offset, requested))
            if (!reply.success || reply.data.size !in 1..requested)
                throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            reply.data.copyInto(bytes, offset)
            offset += reply.data.size
        }
        // A prefix is not a complete source. Never correct the one-byte EOF probe to Le256.
        val end = session.send(CardCommands.readBinary(bytes.size, 1), correctLength = false)
        if (end.data.isNotEmpty() || end.statusWord !in setOf(0x6700, 0x6B00, 0x6282))
            throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        return bytes
    }
}

