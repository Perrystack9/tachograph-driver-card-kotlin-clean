package de.fahrerabrechnung.data.card

import java.time.Instant

/** Complete Gen2V2 driver-card EF Load_Unload_Operations, never a derived work interval. */
class CardLoadUnloadFile(val generation: CardGeneration, val recordCount: Int, bytes: ByteArray) {
    init {
        if (bytes.size != CardLoadUnloadParser.fileLength(generation, recordCount))
            throw ParseError(0, "Load_Unload_Operations", "incorrect complete file length")
    }
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

enum class CardRecordedLoadUnloadOperation { LOAD, UNLOAD, SIMULTANEOUS_LOAD_UNLOAD }

/** Beginning of the entered operation and the GNSS fix are distinct source instants. */
data class CardLoadUnloadRecord(
    val slotIndex: Int,
    val operationTimeRaw: Long,
    val operationTypeRaw: Int,
    val positionTimeRaw: Long,
    val accuracyRaw: Int,
    val latitudeRaw: Int,
    val longitudeRaw: Int,
    val authenticationStatusRaw: Int,
    val odometerRaw: Int,
) {
    val operationTimeUtc: Instant? get() = recordedTime(operationTimeRaw)
    val positionTimeUtc: Instant? get() = recordedTime(positionTimeRaw)
    val operation: CardRecordedLoadUnloadOperation? get() = when (operationTypeRaw) {
        1 -> CardRecordedLoadUnloadOperation.LOAD
        2 -> CardRecordedLoadUnloadOperation.UNLOAD
        3 -> CardRecordedLoadUnloadOperation.SIMULTANEOUS_LOAD_UNLOAD
        else -> null
    }
    val accuracyHdop: Double? get() = accuracyRaw.takeIf { it in 1..100 }?.div(10.0)
    val odometerKm: Int? get() = odometerRaw.takeIf { it in 0..9_999_999 }
    // A recorded tachograph status is not cryptographic verification by this application.
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

/** EU 2021/1228 Appendix 1 2.24c/d, 2.114a; Appendix 2 TCS_154/155.
 * Driver cards only: 1624 twenty-byte records and an unsigned two-byte newest-slot pointer.
 * Reserved individual fields remain raw; only wholly zero/FF personalisation slots are omitted. */
object CardLoadUnloadParser {
    const val RECORD_COUNT = 1624
    const val RECORD_LENGTH = 20

    fun fileLength(generation: CardGeneration, count: Int): Int {
        if (generation != CardGeneration.GEN2V2 || count != RECORD_COUNT)
            throw ParseError(0, "Load_Unload_Operations", "unsupported record count or generation")
        return 2 + count * RECORD_LENGTH
    }

    fun parse(file: CardLoadUnloadFile): List<CardLoadUnloadRecord> {
        val bytes = file.bytes
        val newest = BinaryReader(bytes).u16be("loadUnloadPointerNewestRecord")
        if (newest !in 0 until file.recordCount)
            throw ParseError(0, "loadUnloadPointerNewestRecord", "pointer outside record ring")
        return buildList {
            repeat(file.recordCount) { ordinal ->
                val slot = (newest + 1 + ordinal) % file.recordCount
                val offset = 2 + slot * RECORD_LENGTH
                val raw = bytes.copyOfRange(offset, offset + RECORD_LENGTH)
                if (!raw.all { it == 0.toByte() } && !raw.all { it == 255.toByte() }) {
                    val r = BinaryReader(raw)
                    add(CardLoadUnloadRecord(slot, r.u32be("timeStamp"), r.u8("operationType"),
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

