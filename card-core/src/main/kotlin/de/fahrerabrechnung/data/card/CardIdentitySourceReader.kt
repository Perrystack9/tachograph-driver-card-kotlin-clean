package de.fahrerabrechnung.data.card

/** Freeze bytes already consumed for recognition; prove the EF ends exactly after that capture. */
internal object CardIdentitySourceReader {
    suspend fun freezeComplete(session: ApduSession, kind: CardIdentitySourceKind,
        generation: CardGeneration, captured: ByteArray): CardIdentitySourceFile {
        val file = CardIdentitySourceFile(kind, generation, captured)
        if (!session.send(CardCommands.selectFile(kind.fileId)).success) throw ReaderException(ReaderFailure.IO)
        // A corrected6C00/Le256 reply, empty9000, or error with data proves no complete boundary.
        val end = session.send(CardCommands.readBinary(file.bytes.size, 1), correctLength = false)
        if (end.data.isNotEmpty() || end.statusWord !in setOf(0x6700, 0x6B00, 0x6282))
            throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        CardIdentitySourceParser.parse(file)
        return file
    }
}

