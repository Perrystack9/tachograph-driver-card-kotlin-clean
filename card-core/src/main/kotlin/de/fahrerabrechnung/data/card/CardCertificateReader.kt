package de.fahrerabrechnung.data.card

/** Bounded full-EF reading, independent of a possibly damaged DER length or blank/default EF. */
internal object CardCertificateReader {
    suspend fun read(session: ApduSession, kind: CardCertificateKind, generation: CardGeneration): CardCertificateFile {
        if (!kind.supports(generation)) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        if (!session.send(CardCommands.selectFile(kind.fileId)).success) throw ReaderException(ReaderFailure.IO)
        val minimum = if (generation == CardGeneration.GEN1) 194 else 204
        val maximum = if (generation == CardGeneration.GEN1) 194 else 341

        suspend fun exact(start: Int, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val requested = minOf(256, length - filled)
                val response = session.send(CardCommands.readBinary(start + filled, requested))
                if (!response.success || response.data.size !in 1..requested)
                    throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
                response.data.copyInto(bytes, filled)
                filled += response.data.size
            }
            return bytes
        }
        suspend fun exists(offset: Int): Boolean {
            // Never hide6C00 at EOF behind the general Le256 correction. It is not a boundary proof.
            val response = session.send(CardCommands.readBinary(offset, 1), correctLength = false)
            if (response.success && response.data.size == 1) return true
            if (response.data.isEmpty() && response.statusWord in setOf(0x6700, 0x6B00, 0x6282)) return false
            throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        }
        val prefix = exact(0, minimum)
        if (exists(maximum)) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        var low = minimum
        var high = maximum
        // At most nine one-byte probes over204..341, including the required maximum proof.
        while (low < high) {
            val midpoint = (low + high) / 2
            if (exists(midpoint)) low = midpoint + 1 else high = midpoint
        }
        return CardCertificateFile(kind, generation, prefix + exact(minimum, low - minimum))
            .also { CardCertificateParser.parse(it) }
    }
}

