package de.fahrerabrechnung.data.card

import java.time.Instant

/** Complete EF VehicleUnits_Used. These are tachograph units, not vehicle identities. */
class CardVehicleUnitsFile(val generation: CardGeneration, val recordCount: Int, bytes: ByteArray) {
    init {
        if (bytes.size != CardVehicleUnitsParser.fileLength(generation, recordCount))
            throw ParseError(0, "VehicleUnits_Used", "incorrect complete file length")
    }
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

/** Discrete recorded beginning; no end, distance, VIN or manufacturer of the truck is present. */
data class CardVehicleUnitRecord(val slotIndex: Int, val useBeginRaw: Long,
    val manufacturerCodeRaw: Int, val deviceIdRaw: Int, val softwareVersionRaw: List<Int>) {
    val useBeginUtc: Instant? get() = recordedTime(useBeginRaw)
    // Preserve all four bytes. Only printable IA5 can be presented as a version string.
    val softwareVersionText: String? get() = softwareVersionRaw.takeIf {
        it.size == 4 && it.all { byte -> byte in 32..126 } && it.any { byte -> byte != 32 }
    }?.joinToString("") { it.toChar().toString() }
    val softwareVersionHex: String get() = softwareVersionRaw.joinToString(" ") { "%02X".format(it) }
}

/** EU2016/799 Appendix1 2.39/2.40/2.106/2.226 and Appendix2 TCS154/155;
 * EU2018/502 adds the independent application capacity; EU2021/1228 fixes V2 capacity at200.
 * Two-byte newest-slot pointer, followed by count ten-byte records. */
object CardVehicleUnitsParser {
    const val RECORD_LENGTH = 10

    fun fileLength(generation: CardGeneration, count: Int): Int {
        val valid = when (generation) {
            CardGeneration.GEN2 -> count in 84..200
            CardGeneration.GEN2V2 -> count == 200
            else -> false
        }
        if (!valid) throw ParseError(0, "VehicleUnits_Used", "unsupported record count or generation")
        return 2 + count * RECORD_LENGTH
    }

    fun parse(file: CardVehicleUnitsFile): List<CardVehicleUnitRecord> {
        val bytes = file.bytes
        val newest = BinaryReader(bytes).u16be("vehicleUnitPointerNewestRecord")
        if (newest !in 0 until file.recordCount)
            throw ParseError(0, "vehicleUnitPointerNewestRecord", "pointer outside record ring")
        return buildList {
            repeat(file.recordCount) { ordinal ->
                val slot = (newest + 1 + ordinal) % file.recordCount
                val offset = 2 + slot * RECORD_LENGTH
                val raw = bytes.copyOfRange(offset, offset + RECORD_LENGTH)
                if (!raw.all { it == 0.toByte() } && !raw.all { it == 255.toByte() }) {
                    val reader = BinaryReader(raw)
                    add(CardVehicleUnitRecord(slot, reader.u32be("timeStamp"),
                        reader.u8("manufacturerCode"), reader.u8("deviceID"),
                        reader.slice(4, "vuSoftwareVersion").map { it.toInt() and 255 }))
                }
            }
        }
    }
}

