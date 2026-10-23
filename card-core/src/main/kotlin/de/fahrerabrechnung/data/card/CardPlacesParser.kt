package de.fahrerabrechnung.data.card

import java.time.Instant

/** Complete EF Places (0506), including its physical circular slots. */
class CardPlacesFile(val generation: CardGeneration, val recordCount: Int, bytes: ByteArray) {
    init {
        if (bytes.size != CardPlacesParser.fileLength(generation, recordCount))
            throw ParseError(0, "Places", "incorrect complete file length")
    }
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

enum class CardWorkPeriodEntry { BEGIN, END }

/** Recorded daily-work-period place, not an inferred shift, rest, route or homecoming. */
data class CardPlaceRecord(
    val slotIndex: Int, val generation: CardGeneration,
    val entryTimeRaw: Long, val entryTypeRaw: Int, val countryRaw: Int, val regionRaw: Int,
    val odometerRaw: Int, val positionTimeRaw: Long? = null, val accuracyRaw: Int? = null,
    val latitudeRaw: Int? = null, val longitudeRaw: Int? = null,
) {
    val entryTimeUtc: Instant? get() = recordedTime(entryTimeRaw)
    val entry: CardWorkPeriodEntry? get() = if (entryTypeRaw in
        (if (generation == CardGeneration.GEN1) 0..5 else 0..7))
        if (entryTypeRaw % 2 == 0) CardWorkPeriodEntry.BEGIN else CardWorkPeriodEntry.END else null
    val odometerKm: Int? get() = odometerRaw.takeIf { it in 0..9_999_999 }
    val positionTimeUtc: Instant? get() = positionTimeRaw?.let(::recordedTime)
    val accuracyHdop: Double? get() = accuracyRaw?.takeIf { it in 1..100 }?.div(10.0)
    val position: CardRecordedGnssPosition? get() {
        val time = positionTimeUtc ?: return null
        val latitude = latitudeRaw?.let { coordinateDegrees(it, 90) } ?: return null
        val longitude = longitudeRaw?.let { coordinateDegrees(it, 180) } ?: return null
        return CardRecordedGnssPosition(time, latitude, longitude)
    }
}

/** EU 2016/799 Appendix 1 EntryTypeDailyWorkPeriod, PlaceRecord and CardPlaceDailyWorkPeriod;
 * Appendix 2 TCS_154/155. EU 2021/1228 retains the 21-byte legacy record on Gen2V2.
 * Entry time and GNSS position time remain distinct; no authentication join is made. */
object CardPlacesParser {
    fun recordLength(generation: CardGeneration): Int = when (generation) {
        CardGeneration.GEN1 -> 10
        CardGeneration.GEN2, CardGeneration.GEN2V2 -> 21
        else -> throw ParseError(0, "Places", "unsupported generation")
    }
    fun headerLength(generation: CardGeneration): Int = if (generation == CardGeneration.GEN1) 1 else 2
    fun fileLength(generation: CardGeneration, count: Int): Int {
        val width = recordLength(generation)
        if (count !in 84..112 || (generation == CardGeneration.GEN2V2 && count != 112))
            throw ParseError(0, "Places", "unsupported record count")
        return headerLength(generation) + count * width
    }
    fun parse(file: CardPlacesFile): List<CardPlaceRecord> {
        val bytes = file.bytes
        val header = headerLength(file.generation)
        val reader = BinaryReader(bytes)
        val newest = if (header == 1) reader.u8("placePointerNewestRecord") else reader.u16be("placePointerNewestRecord")
        if (newest !in 0 until file.recordCount)
            throw ParseError(0, "placePointerNewestRecord", "pointer outside record ring")
        val width = recordLength(file.generation)
        return buildList {
            repeat(file.recordCount) { ordinal ->
                val slot = (newest + 1 + ordinal) % file.recordCount
                val offset = header + slot * width
                val raw = bytes.copyOfRange(offset, offset + width)
                if (!raw.all { it == 0.toByte() } && !raw.all { it == 255.toByte() }) {
                    val r = BinaryReader(raw)
                    val time = r.u32be("entryTime")
                    val type = r.u8("entryTypeDailyWorkPeriod")
                    val country = r.u8("dailyWorkPeriodCountry")
                    val region = r.u8("dailyWorkPeriodRegion")
                    val odometer = unsigned24(r, "vehicleOdometerValue")
                    if (file.generation == CardGeneration.GEN1)
                        add(CardPlaceRecord(slot, file.generation, time, type, country, region, odometer))
                    else add(CardPlaceRecord(slot, file.generation, time, type, country, region, odometer,
                        r.u32be("positionTime"), r.u8("gnssAccuracy"), signed24(r, "latitude"), signed24(r, "longitude")))
                }
            }
        }
    }
    private fun unsigned24(r: BinaryReader, field: String): Int =
        (r.u8(field) shl 16) or (r.u8(field) shl 8) or r.u8(field)
    private fun signed24(r: BinaryReader, field: String): Int = (unsigned24(r, field) shl 8) shr 8
}

