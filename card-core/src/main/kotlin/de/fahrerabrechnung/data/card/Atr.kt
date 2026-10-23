package de.fahrerabrechnung.data.card

data class Atr(val raw: ByteArray, val protocols: Set<Int>, val historical: ByteArray)

object AtrDecoder {
    fun parse(bytes: ByteArray): Atr {
        if (bytes.size !in 2..33 || (bytes[0].toInt() and 255) !in setOf(0x3B, 0x3F))
            throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        var y = bytes[1].toInt() ushr 4
        val historicLength = bytes[1].toInt() and 15
        var index = 2
        val protocols = mutableSetOf<Int>()
        var hasTck = false
        repeat(8) {
            val count = Integer.bitCount(y and 7)
            if (index + count > bytes.size) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            index += count
            if (y and 8 == 0) {
                if (protocols.isEmpty()) protocols += 0
                if (index + historicLength + (if (hasTck) 1 else 0) != bytes.size)
                    throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
                val historical = bytes.copyOfRange(index, index + historicLength)
                if (hasTck && bytes.copyOfRange(1, bytes.size).fold(0) { acc, b -> acc xor (b.toInt() and 255) } != 0)
                    throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
                return Atr(bytes.copyOf(), protocols, historical)
            }
            if (index >= bytes.size) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            val td = bytes[index++].toInt() and 255
            val protocol = td and 15
            protocols += protocol
            if (protocol != 0) hasTck = true
            y = td ushr 4
        }
        throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
    }
}

