package de.fahrerabrechnung.data.card

import java.nio.charset.Charset
import java.time.Instant

data class CardVehicleUse(
    val ringIndex: Int,
    val firstUseUtc: Instant,
    val lastUseUtc: Instant?,
    val registrationNation: Int,
    val registrationNumber: String,
    val vin: String?,
    val odometerBeginKm: Int,
    val odometerEndKm: Int?,
    val vuDataBlockCounter: Int,
)

/** Appendix 1 CardVehicleRecord and Appendix 2 EF Vehicles_Used, Gen1/Gen2. */
object CardVehiclesParser {
    fun parse(file: CardVehiclesFile): List<CardVehicleUse> {
        val width = when (file.generation) {
            CardGeneration.GEN1 -> 31
            CardGeneration.GEN2, CardGeneration.GEN2V2 -> 48
            else -> throw ParseError(0, "Vehicles_Used", "unsupported generation")
        }
        if (file.recordCount !in 1..200 || file.newestRecordIndex !in 0 until file.recordCount ||
            file.records.size != file.recordCount * width)
            throw ParseError(0, "Vehicles_Used", "invalid record ring")
        // The pointer is an index of the newest record. Begin with its successor.
        return (1..file.recordCount).mapNotNull { step ->
            val index = (file.newestRecordIndex + step) % file.recordCount
            val bytes = file.records.copyOfRange(index * width, (index + 1) * width)
            // Unused slots can have non-zero filler in text/identification fields.
            // The odometers and both use times are initialised to zero on the card.
            if (bytes.take(14).all { it == 0.toByte() }) return@mapNotNull null
            val r = BinaryReader(bytes)
            fun u24(field: String) = (r.u8(field) shl 16) or (r.u8(field) shl 8) or r.u8(field)
            val beginKm = u24("vehicleOdometerBegin")
            val endKm = u24("vehicleOdometerEnd")
            val firstSeconds = r.u32be("vehicleFirstUse")
            val lastSeconds = r.u32be("vehicleLastUse")
            val nation = r.u8("vehicleRegistrationNation")
            val codePage = r.u8("vehicleRegNumber.codePage")
            val charset = when (codePage) {
                0, 1 -> Charsets.ISO_8859_1
                2, 3, 5, 7, 9, 13, 15, 16 -> Charset.forName("ISO-8859-$codePage")
                80 -> Charset.forName("KOI8-R")
                85 -> Charset.forName("KOI8-U")
                else -> throw ParseError(index * width + 15, "vehicleRegNumber.codePage", "unsupported code page")
            }
            val plate = r.text(13, charset, "vehicleRegNumber")
            val counter = r.u16be("vuDataBlockCounter")
            val vin = if (width == 48) r.text(17, Charsets.US_ASCII, "vehicleIdentificationNumber")
                .ifBlank { null } else null
            if (firstSeconds == 0L || (lastSeconds != 0L && lastSeconds < firstSeconds) ||
                (plate.isBlank() && vin == null) || r.remaining != 0)
                throw ParseError(index * width, "CardVehicleRecord", "invalid use or identity")
            CardVehicleUse(index, Instant.ofEpochSecond(firstSeconds),
                lastSeconds.takeIf { it != 0L }?.let(Instant::ofEpochSecond),
                nation, plate, vin, beginKm,
                endKm.takeIf { lastSeconds != 0L }, counter)
        }
    }
}

