package de.fahrerabrechnung.data.card

data class CommandApdu(val cla: Int, val ins: Int, val p1: Int, val p2: Int,
                       val data: ByteArray = byteArrayOf(), val le: Int? = null) {
    init {
        require(listOf(cla, ins, p1, p2).all { it in 0..255 })
        require(data.size <= 255 && (le == null || le in 1..256))
    }
    fun encode(): ByteArray {
        val head = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte())
        return head + (if (data.isNotEmpty()) byteArrayOf(data.size.toByte()) + data else byteArrayOf()) +
            (if (le != null) byteArrayOf((le % 256).toByte()) else byteArrayOf())
    }
    fun withLe(length: Int) = copy(le = length)
}

data class ResponseApdu(val data: ByteArray, val sw1: Int, val sw2: Int) {
    val statusWord: Int get() = sw1 shl 8 or sw2
    val success: Boolean get() = statusWord == 0x9000
    val meaning: String get() = when {
        success -> "success"
        sw1 == 0x61 -> "more data"
        sw1 == 0x6C -> "wrong length"
        statusWord == 0x6A82 -> "file not found"
        statusWord == 0x6982 -> "security status not satisfied"
        statusWord == 0x6985 -> "conditions of use not satisfied"
        statusWord == 0x6D00 -> "instruction not supported"
        else -> "card status"
    }
    companion object {
        fun decode(bytes: ByteArray): ResponseApdu {
            if (bytes.size < 2) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            return ResponseApdu(bytes.copyOfRange(0, bytes.size - 2),
                bytes[bytes.size - 2].toInt() and 255, bytes.last().toInt() and 255)
        }
    }
}

class ApduSession(private val transport: CardReaderTransport) {
    /** Bounded GET RESPONSE / 6C retry, with a cumulative cap against malformed or hostile cards. */
    suspend fun send(command: CommandApdu, correctLength: Boolean = true): ResponseApdu {
        var current = command
        val collected = ArrayList<Byte>()
        var corrected = false
        repeat(32) {
            val answer = ResponseApdu.decode(transport.exchange(current.encode()))
            if (correctLength && answer.sw1 == 0x6C && !corrected) {
                if (answer.data.isNotEmpty()) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
                corrected = true
                current = current.withLe(if (answer.sw2 == 0) 256 else answer.sw2)
                return@repeat
            }
            if (collected.size + answer.data.size > 65536) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            collected.addAll(answer.data.toList())
            if (answer.sw1 == 0x61) {
                current = CommandApdu(command.cla, 0xC0, 0, 0, le = if (answer.sw2 == 0) 256 else answer.sw2)
                corrected = false
                return@repeat
            }
            return ResponseApdu(collected.toByteArray(), answer.sw1, answer.sw2)
        }
        throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
    }
}

object CardCommands {
    /** Appendix2 TCS124: implicit algorithm in both DFs, no Lc/data/Le. */
    fun performHashOfFile() = CommandApdu(0x80, 0x2A, 0x90, 0)
    fun computeDigitalSignature(length: Int): CommandApdu {
        require(length in setOf(64, 96, 128, 132))
        return CommandApdu(0, 0x2A, 0x9E, 0x9A, le = length)
    }

    fun selectFile(id: Int) = CommandApdu(0, 0xA4, 2, 0x0C,
        byteArrayOf((id ushr 8).toByte(), id.toByte()))
    fun selectAid(aid: ByteArray) = CommandApdu(0, 0xA4, 4, 0x0C, aid)
    fun readBinary(offset: Int, length: Int): CommandApdu {
        require(offset in 0..32767 && length in 1..256)
        return CommandApdu(0, 0xB0, offset ushr 8, offset and 255, le = length)
    }
}

