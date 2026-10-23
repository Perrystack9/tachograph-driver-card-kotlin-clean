package de.fahrerabrechnung.data.card

import java.time.Instant
import java.util.Locale

/** Original MF identification fields. These never replace the confirmed driver-card binding. */
data class CardIccIdentificationRecord(
    val clockStopRaw: Int, val serialNumberRaw: Long, val monthYearRaw: List<Int>,
    val equipmentTypeRaw: Int, val manufacturerCodeRaw: Int, val approvalNumberRaw: List<Int>,
    val personaliserIdRaw: Int, val embedderCountryRaw: List<Int>, val moduleEmbedderRaw: Int,
    val manufacturerInformationRaw: List<Int>, val icIdentifierRaw: List<Int>,
) : CardMetadataRecord {
    override val timeUtc: Instant? get() = null
    val approvalNumberText: String? get() = masterIa5(approvalNumberRaw)
    val monthYearText: String? get() {
        fun bcd(value: Int): Int? = if ((value shr 4) in 0..9 && (value and 15) in 0..9)
            (value shr 4) * 10 + (value and 15) else null
        val month = bcd(monthYearRaw[0])?.takeIf { it in 1..12 } ?: return null
        val year = bcd(monthYearRaw[1]) ?: return null
        return "%02d/%02d".format(Locale.ROOT, month, year)
    }
}

/** Both four-byte strings are required for a chip identifier; their byte order is not reinterpreted. */
data class CardChipIdentificationRecord(val serialNumberRaw: List<Int>, val manufacturingReferencesRaw: List<Int>) : CardMetadataRecord {
    override val timeUtc: Instant? get() = null
    val identifierHex: String get() = (serialNumberRaw + manufacturingReferencesRaw).masterHex()
}

internal fun List<Int>.masterHex(): String = joinToString(" ") { "%02X".format(it) }
private fun masterIa5(bytes: List<Int>): String? = bytes.dropLastWhile { it == 0 || it == 32 }
    .takeIf { it.isNotEmpty() && it.all { byte -> byte in 32..126 } }
    ?.joinToString("") { it.toChar().toString() }

/** EU2016/799 Appendix1 2.11/2.13/2.23/2.65/2.72 and Appendix2 TCS142/144. */
object CardMasterFileParser {
    fun parse(file: CardMetadataFile): CardMetadataRecord? {
        if (!file.kind.inMasterFile) throw ParseError(0, file.kind.name, "not an MF identification file")
        val bytes = file.bytes
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 255.toByte() }) return null
        // Default EF_ICC uses spaces for the eight-byte approval field and zeros elsewhere.
        if (file.kind == CardMetadataKind.ICC_IDENTIFICATION && bytes.withIndex().all { (index, byte) ->
                byte == 0.toByte() || (index in 9..16 && byte == 32.toByte()) }) return null
        val r = BinaryReader(bytes)
        fun raw(size: Int, name: String) = r.slice(size, name).map { it.toInt() and 255 }
        return when (file.kind) {
            CardMetadataKind.ICC_IDENTIFICATION -> CardIccIdentificationRecord(
                r.u8("clockStop"), r.u32be("cardExtendedSerialNumber.serialNumber"), raw(2, "monthYear"),
                r.u8("type"), r.u8("manufacturerCode"), raw(8, "cardApprovalNumber"),
                r.u8("cardPersonaliserID"), raw(2, "embedder.countryCode"), r.u8("embedder.moduleEmbedder"),
                raw(2, "embedder.manufacturerInformation"), raw(2, "icIdentifier"))
            CardMetadataKind.IC_IDENTIFICATION -> CardChipIdentificationRecord(raw(4, "icSerialNumber"), raw(4, "icManufacturingReferences"))
            else -> throw ParseError(0, file.kind.name, "not an MF identification file")
        }
    }
}

