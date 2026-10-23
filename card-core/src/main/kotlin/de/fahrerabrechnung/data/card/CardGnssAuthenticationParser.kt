package de.fahrerabrechnung.data.card

import java.time.Instant

/** Complete Gen2V2 EF GNSS_Places_Authentication, separate from the position source. */
class CardGnssAuthenticationFile(val generation: CardGeneration, bytes: ByteArray) {
    init {
        if (bytes.size != CardGnssAuthenticationParser.fileLength(generation))
            throw ParseError(0, "GNSS_Places_Authentication", "incorrect complete file length")
    }

    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

/** A status recorded by the tachograph, not independent cryptographic verification. */
enum class CardRecordedGnssAuthenticationStatus { NOT_AUTHENTICATED, AUTHENTICATED }

data class CardGnssAuthenticationRecord(
    val slotIndex: Int,
    val accumulatedDrivingTimeRaw: Long,
    val authenticationStatusRaw: Int
) {
    val accumulatedDrivingTimeUtc: Instant? get() = accumulatedDrivingTimeRaw
        .takeIf { it != 0L && it != 0xffffffffL }?.let(Instant::ofEpochSecond)
    val recordedStatus: CardRecordedGnssAuthenticationStatus? get() = when (authenticationStatusRaw) {
        0 -> CardRecordedGnssAuthenticationStatus.NOT_AUTHENTICATED
        1 -> CardRecordedGnssAuthenticationStatus.AUTHENTICATED
        else -> null // Reserved values stay available in authenticationStatusRaw.
    }
}

/** EU 2021/1228 Appendix 1 2.79a/b and 2.117a; Appendix 2 TCS_155.
 * No reader, position join, persistence or trust decision is performed here. */
object CardGnssAuthenticationParser {
    const val RECORD_COUNT = 336
    const val RECORD_LENGTH = 5

    fun fileLength(generation: CardGeneration): Int {
        if (generation != CardGeneration.GEN2V2)
            throw ParseError(0, "GNSS_Places_Authentication", "unsupported generation")
        return 2 + RECORD_COUNT * RECORD_LENGTH
    }

    fun parse(file: CardGnssAuthenticationFile): List<CardGnssAuthenticationRecord> {
        val bytes = file.bytes
        val newest = BinaryReader(bytes).u16be("gnssAuthADPointerNewestRecord")
        if (newest !in 0 until RECORD_COUNT)
            throw ParseError(0, "gnssAuthADPointerNewestRecord", "pointer outside record ring")
        return buildList {
            repeat(RECORD_COUNT) { ordinal ->
                val slot = (newest + 1 + ordinal) % RECORD_COUNT
                val offset = 2 + slot * RECORD_LENGTH
                val raw = bytes.copyOfRange(offset, offset + RECORD_LENGTH)
                if (!raw.all { it == 0.toByte() } && !raw.all { it == 255.toByte() }) {
                    val r = BinaryReader(raw)
                    add(CardGnssAuthenticationRecord(slot,
                        r.u32be("accumulatedDrivingTime"), r.u8("authenticationStatus")))
                }
            }
        }
    }
}

