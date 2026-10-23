package de.fahrerabrechnung.data.card

import java.time.Instant

/** Complete driver-card Gen2V2 EF Border_Crossings, independent of other position files. */
class CardBorderCrossingsFile(
    val generation: CardGeneration,
    val recordCount: Int,
    bytes: ByteArray
) {
    init {
        if (bytes.size != CardBorderCrossingsParser.fileLength(generation, recordCount))
            throw ParseError(0, "Border_Crossings", "incorrect complete file length")
    }

    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

/** Country fields may describe card insertion rather than a proven crossing. */
data class CardBorderCrossingRecord(
    val slotIndex: Int,
    val countryLeftRaw: Int,
    val countryEnteredRaw: Int,
    val positionTimeRaw: Long,
    val accuracyRaw: Int,
    val latitudeRaw: Int,
    val longitudeRaw: Int,
    val authenticationStatusRaw: Int,
    val odometerRaw: Int
) {
    val positionTimeUtc: Instant? get() = recordedTime(positionTimeRaw)
    val accuracyHdop: Double? get() = accuracyRaw.takeIf { it in 1..100 }?.div(10.0)
    val odometerKm: Int? get() = odometerRaw.takeIf { it in 0..9_999_999 }
    // A stored status is not a cryptographic verification performed by this app.
    val recordedStatus: CardRecordedGnssAuthenticationStatus? get() = when (authenticationStatusRaw) {
        0 -> CardRecordedGnssAuthenticationStatus.NOT_AUTHENTICATED
        1 -> CardRecordedGnssAuthenticationStatus.AUTHENTICATED
        else -> null
    }
    val position: CardRecordedGnssPosition? get() {
        val time = positionTimeUtc ?: return null
        val latitude = coordinateDegrees(latitudeRaw, 90) ?: return null
        val longitude = coordinateDegrees(longitudeRaw, 180) ?: return null
        return CardRecordedGnssPosition(time, latitude, longitude)
    }
}

/** EU 2021/1228 Appendix 1 2.11a/b and 2.79c; Appendix 2 TCS_154/155.
 * Driver cards only: 1120 records of 17 bytes, preceded by a 2-byte newest-slot pointer.
 * No reader, persistence, cross-file join or business interpretation is performed here. */
object CardBorderCrossingsParser {
    const val RECORD_COUNT = 1120
    const val RECORD_LENGTH = 17

    fun fileLength(generation: CardGeneration, count: Int): Int {
        if (generation != CardGeneration.GEN2V2 || count != RECORD_COUNT)
            throw ParseError(0, "Border_Crossings", "unsupported record count or generation")
        return 2 + count * RECORD_LENGTH
    }

    fun parse(file: CardBorderCrossingsFile): List<CardBorderCrossingRecord> {
        val bytes = file.bytes
        val newest = BinaryReader(bytes).u16be("borderCrossingPointerNewestRecord")
        if (newest !in 0 until file.recordCount)
            throw ParseError(0, "borderCrossingPointerNewestRecord", "pointer outside record ring")
        return buildList {
            repeat(file.recordCount) { ordinal ->
                val slot = (newest + 1 + ordinal) % file.recordCount
                val offset = 2 + slot * RECORD_LENGTH
                val raw = bytes.copyOfRange(offset, offset + RECORD_LENGTH)
                // Only complete personalisation fillers are omitted; partial fields survive.
                if (!raw.all { it == 0.toByte() } && !raw.all { it == 255.toByte() }) {
                    val r = BinaryReader(raw)
                    add(CardBorderCrossingRecord(slot,
                        r.u8("countryLeft"), r.u8("countryEntered"),
                        r.u32be("positionTime"), r.u8("gnssAccuracy"),
                        signedCoordinate(r, "latitude"), signedCoordinate(r, "longitude"),
                        r.u8("authenticationStatus"), unsigned24(r, "vehicleOdometerValue")))
                }
            }
        }
    }

    private fun unsigned24(r: BinaryReader, field: String): Int =
        (r.u8(field) shl 16) or (r.u8(field) shl 8) or r.u8(field)

    private fun signedCoordinate(r: BinaryReader, field: String): Int =
        (unsigned24(r, field) shl 8) shr 8
}

