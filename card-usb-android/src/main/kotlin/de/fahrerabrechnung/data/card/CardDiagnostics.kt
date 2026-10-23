package de.fahrerabrechnung.data.card

/** Explicit, in-memory hardware probe. It contains no card number, names, APDUs or ATR dump. */
data class CardDiagnostic(
    val vendorId: Int?, val productId: Int?, val permission: Boolean,
    val slot: Int, val reader: ReaderState, val atrProtocols: Set<Int> = emptySet(),
    val apduResponded: Boolean = false, val generation: CardGeneration = CardGeneration.UNKNOWN,
    val failure: ReaderFailure? = null
)

class CardDiagnosticProbe(private val transport: AndroidCcidTransport) {
    suspend fun inspect(): CardDiagnostic {
        val devices = transport.devices()
        val info = devices.firstOrNull { it.permission } ?: devices.firstOrNull()
        var state: ReaderState = ReaderState.Absent
        var protocols = emptySet<Int>()
        var apduResponded = false
        var generation = CardGeneration.UNKNOWN
        var failure: ReaderFailure? = null
        try {
            state = transport.open()
            if (state == ReaderState.Open) {
                state = transport.slotStatus()
                if (state == ReaderState.CardPresent) {
                    protocols = AtrDecoder.parse(transport.powerOn()).protocols
                    val session = ApduSession(transport)
                    val g2 = application(session, DriverCardFiles.gen2Aid, 15)
                    val g1 = if (g2 == null) application(session, DriverCardFiles.gen1Aid, 10) else null
                    apduResponded = true
                    generation = DriverCardDecoder.generation(g1, g2).second
                }
            }
        } catch (e: ReaderException) {
            failure = e.failure
            state = when (e.failure) {
                ReaderFailure.CARD_ABSENT -> ReaderState.Empty
                ReaderFailure.CARD_REMOVED -> ReaderState.Disconnected
                ReaderFailure.DISCONNECTED -> ReaderState.Disconnected
                else -> state
            }
        } finally {
            runCatching { transport.powerOff() }
            transport.close()
        }
        return CardDiagnostic(info?.vendorId, info?.productId, info?.permission == true,
            transport.slot, state, protocols, apduResponded, generation, failure)
    }

    private suspend fun application(session: ApduSession, aid: ByteArray, size: Int): ByteArray? {
        val selected = session.send(CardCommands.selectAid(aid))
        if (selected.statusWord == 0x6A82) return null
        if (!selected.success || !session.send(CardCommands.selectFile(DriverCardFiles.APPLICATION)).success)
            throw ReaderException(ReaderFailure.IO)
        val data = session.send(CardCommands.readBinary(0, size))
        if (!data.success || data.data.size != size) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        return data.data
    }
}

