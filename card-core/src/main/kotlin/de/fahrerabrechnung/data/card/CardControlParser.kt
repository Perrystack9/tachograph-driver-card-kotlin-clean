package de.fahrerabrechnung.data.card

import java.nio.charset.Charset
import java.time.Instant

/** Complete EF 0508 from the selected application, not a control-card identity of the driver. */
class CardControlFile(val generation: CardGeneration, bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()
}

data class CardControlRecord(
    val controlType: Int,
    val timeUtc: Instant,
    val controlCardType: Int,
    val controlCardIssuingState: Int,
    val controlCardNumber: String?,
    val vehicleRegistrationNation: Int,
    val vehicleRegistrationNumber: String?,
    val downloadBeginUtc: Instant?,
    val downloadEndUtc: Instant?,
)

/** EU 2016/799 Annex IC Appendix 1, 2.15/2.53; Appendix 2 TCS_150/152.
 * EF Control_Activity_Data is 46 bytes in Gen1/Gen2/Gen2V2. Zero controlTime is unused.
 * Preserve the complete type byte and independent optional fields; never infer a violation.
 */
object CardControlParser {
    const val FILE_LENGTH = 46

    fun parse(file: CardControlFile): CardControlRecord? {
        if (file.generation !in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            throw ParseError(0, "Control_Activity_Data", "unsupported generation")
        if (file.bytes.size != FILE_LENGTH)
            throw ParseError(0, "Control_Activity_Data", "expected 46 bytes")
        val r = BinaryReader(file.bytes)
        val type = r.u8("controlType")
        val time = r.u32be("controlTime")
        // An unused record has zero time, even if its identification fields contain filler.
        if (time == 0L) return null
        val cardType = r.u8("controlCardType")
        val issuingState = r.u8("controlCardIssuingMemberState")
        val number = r.text(16, Charsets.ISO_8859_1, "controlCardNumber").ifBlank { null }
        val nation = r.u8("controlVehicleRegistrationNation")
        val codePage = r.u8("controlVehicleRegistrationNumber.codePage")
        val charset = when (codePage) {
            0, 1 -> Charsets.ISO_8859_1
            2, 3, 5, 7, 9, 13, 15, 16 -> Charset.forName("ISO-8859-$codePage")
            80 -> Charset.forName("KOI8-R")
            85 -> Charset.forName("KOI8-U")
            else -> throw ParseError(24, "controlVehicleRegistrationNumber.codePage", "unsupported code page")
        }
        val plate = r.text(13, charset, "controlVehicleRegistrationNumber").ifBlank { null }
        fun optionalTime(field: String) = r.u32be(field).takeIf { it != 0L }?.let(Instant::ofEpochSecond)
        val begin = optionalTime("controlDownloadPeriodBegin")
        val end = optionalTime("controlDownloadPeriodEnd")
        return CardControlRecord(type, Instant.ofEpochSecond(time), cardType, issuingState,
            number, nation, plate, begin, end)
    }
}

