package de.fahrerabrechnung.data.card

import java.time.Instant

/** Complete Gen2V2 EF Places_Authentication, separate from the position source. */
class CardPlacesAuthenticationFile(val generation: CardGeneration, bytes: ByteArray) {
    init {
        if (bytes.size != CardPlacesAuthenticationParser.fileLength(generation))
            throw ParseError(0, "Places_Authentication", "incorrect complete file length")
    }

    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

data class CardPlacesAuthenticationRecord(
    val slotIndex: Int,
    val entryTimeRaw: Long,
    val authenticationStatusRaw: Int
) {
    val entryTimeUtc: Instant? get() = entryTimeRaw
        .takeIf { it != 0L && it != 0xffffffffL }?.let(Instant::ofEpochSecond)
    val recordedStatus: CardRecordedGnssAuthenticationStatus? get() = when (authenticationStatusRaw) {
        0 -> CardRecordedGnssAuthenticationStatus.NOT_AUTHENTICATED
        1 -> CardRecordedGnssAuthenticationStatus.AUTHENTICATED
        else -> null // Reserved values stay available in authenticationStatusRaw.
    }
}

/** EU 2021/1228 Appendix 1 CardPlaceAuthDailyWorkPeriod, PlaceAuthStatusRecord and PositionAuthenticationStatus; Appendix 2 TCS_155.
 * No reader, position join, persistence or trust decision is performed here. */
object CardPlacesAuthenticationParser {
    const val RECORD_COUNT = 112
    const val RECORD_LENGTH = 5

    fun fileLength(generation: CardGeneration): Int {
        if (generation != CardGeneration.GEN2V2)
            throw ParseError(0, "Places_Authentication", "unsupported generation")
        return 2 + RECORD_COUNT * RECORD_LENGTH
    }

    fun parse(file: CardPlacesAuthenticationFile): List<CardPlacesAuthenticationRecord> {
        val bytes = file.bytes
        val newest = BinaryReader(bytes).u16be("placeAuthPointerNewestRecord")
        if (newest !in 0 until RECORD_COUNT)
            throw ParseError(0, "placeAuthPointerNewestRecord", "pointer outside record ring")
        return buildList {
            repeat(RECORD_COUNT) { ordinal ->
                val slot = (newest + 1 + ordinal) % RECORD_COUNT
                val offset = 2 + slot * RECORD_LENGTH
                val raw = bytes.copyOfRange(offset, offset + RECORD_LENGTH)
                if (!raw.all { it == 0.toByte() } && !raw.all { it == 255.toByte() }) {
                    val r = BinaryReader(raw)
                    add(CardPlacesAuthenticationRecord(slot,
                        r.u32be("entryTime"), r.u8("authenticationStatus")))
                }
            }
        }
    }
}

