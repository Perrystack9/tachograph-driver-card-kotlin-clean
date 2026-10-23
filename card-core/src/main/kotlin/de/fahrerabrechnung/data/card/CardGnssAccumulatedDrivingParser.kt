package de.fahrerabrechnung.data.card

import java.time.Instant
import kotlin.math.abs

/** Complete EF GNSS_Places (0524). Decoded independently after card identification. */
class CardGnssAccumulatedDrivingFile(
    val generation: CardGeneration,
    val recordCount: Int,
    bytes: ByteArray
) {
    init {
        if (bytes.size != CardGnssAccumulatedDrivingParser.fileLength(generation, recordCount))
            throw ParseError(0, "GNSS_Places", "incorrect complete file length")
    }

    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

/** A recorded position, not a route, activity, homecoming or distance calculation. */
data class CardRecordedGnssPosition(val timeUtc: Instant, val latitude: Double, val longitude: Double)

data class CardGnssAccumulatedDrivingRecord(
    val slotIndex: Int,
    val accumulatedDrivingTimeRaw: Long,
    val positionTimeRaw: Long,
    val accuracyRaw: Int,
    val latitudeRaw: Int,
    val longitudeRaw: Int,
    val odometerRaw: Int
) {
    val accumulatedDrivingTimeUtc: Instant? get() = recordedTime(accumulatedDrivingTimeRaw)
    val positionTimeUtc: Instant? get() = recordedTime(positionTimeRaw)
    // GNSSAccuracy encodes NMEA HDOP * 10; it is not an accuracy radius in metres.
    val accuracyHdop: Double? get() = accuracyRaw.takeIf { it in 1..100 }?.div(10.0)
    val odometerKm: Int? get() = odometerRaw.takeIf { it in 0..9_999_999 }
    val position: CardRecordedGnssPosition? get() {
        val time = positionTimeUtc ?: return null
        val latitude = coordinateDegrees(latitudeRaw, 90) ?: return null
        val longitude = coordinateDegrees(longitudeRaw, 180) ?: return null
        return CardRecordedGnssPosition(time, latitude, longitude)
    }
}

/** EU 2016/799 Appendix 1 2.76–80 and Appendix 2 TCS_154/155, amended by 2018/502
 * and 2021/1228. The legacy GNSS_Places record remains 18 bytes on Gen2V2 cards.
 * Its separate authentication EF is not decoded or implicitly treated as authenticated here. */
object CardGnssAccumulatedDrivingParser {
    const val RECORD_LENGTH = 18

    fun fileLength(generation: CardGeneration, count: Int): Int {
        val valid = when (generation) {
            CardGeneration.GEN2 -> count in 252..336
            CardGeneration.GEN2V2 -> count == 336
            else -> false
        }
        if (!valid) throw ParseError(0, "GNSS_Places", "unsupported record count or generation")
        return 2 + count * RECORD_LENGTH
    }

    fun parse(file: CardGnssAccumulatedDrivingFile): List<CardGnssAccumulatedDrivingRecord> {
        val bytes = file.bytes
        val newest = BinaryReader(bytes).u16be("gnssADPointerNewestRecord")
        if (newest !in 0 until file.recordCount)
            throw ParseError(0, "gnssADPointerNewestRecord", "pointer outside record ring")
        return buildList {
            repeat(file.recordCount) { ordinal ->
                val slot = (newest + 1 + ordinal) % file.recordCount
                val offset = 2 + slot * RECORD_LENGTH
                val raw = bytes.copyOfRange(offset, offset + RECORD_LENGTH)
                // Only a complete personalisation filler is omitted. Partial/unknown fields survive.
                if (!raw.all { it == 0.toByte() } && !raw.all { it == 255.toByte() }) {
                    val r = BinaryReader(raw)
                    add(CardGnssAccumulatedDrivingRecord(
                        slot, r.u32be("accumulatedDrivingTime"), r.u32be("positionTime"),
                        r.u8("gnssAccuracy"), signedCoordinate(r, "latitude"),
                        signedCoordinate(r, "longitude"), unsigned24(r, "vehicleOdometerValue")
                    ))
                }
            }
        }
    }

    private fun unsigned24(r: BinaryReader, field: String): Int =
        (r.u8(field) shl 16) or (r.u8(field) shl 8) or r.u8(field)

    private fun signedCoordinate(r: BinaryReader, field: String): Int =
        (unsigned24(r, field) shl 8) shr 8
}

internal fun recordedTime(raw: Long): Instant? =
    raw.takeIf { it != 0L && it != 0xffffffffL }?.let(Instant::ofEpochSecond)

/** Signed ±DDMM.M / ±DDDMM.M multiplied by ten, not decimal degrees multiplied by a scale. */
internal fun coordinateDegrees(raw: Int, maxDegrees: Int): Double? {
    if (raw !in -maxDegrees * 1000..maxDegrees * 1000) return null
    val magnitude = abs(raw)
    val degrees = magnitude / 1000
    val tenthsOfMinute = magnitude % 1000
    if (tenthsOfMinute >= 600 || (degrees == maxDegrees && tenthsOfMinute != 0)) return null
    val value = degrees + tenthsOfMinute / 600.0
    return if (raw < 0) -value else value
}

