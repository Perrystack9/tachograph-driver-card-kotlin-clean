package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant

enum class CardMetadataKind(val fileId: Int, val fileLength: Int, val inMasterFile: Boolean = false) {
    DRIVING_LICENCE(0x0521, 53), CURRENT_USAGE(0x0507, 19), LAST_CARD_DOWNLOAD(0x050E, 4),
    ICC_IDENTIFICATION(0x0002, 25, true), IC_IDENTIFICATION(0x0005, 8, true)
}

/** Original complete read-only metadata EF in the selected card application. */
class CardMetadataFile(val kind: CardMetadataKind, val generation: CardGeneration, bytes: ByteArray) {
    init {
        if (generation !in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2) || bytes.size != kind.fileLength)
            throw ParseError(0, kind.name, "unsupported generation or incorrect complete file length")
    }
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

data class CardMetadataRead(val file: CardMetadataFile? = null, val readFailed: Boolean = false)

/** Appendix1 chapter4: unknown code pages/undecodable text retain their original unsigned bytes. */
data class CardEncodedText(val codePageRaw: Int, val bytesRaw: List<Int>) {
    val isBlank: Boolean get() = bytesRaw.all { it == 0 || it == 32 }
    val text: String? get() {
        if (isBlank) return null
        val charsetName = when (codePageRaw) {
            1, 2, 3, 5, 7, 9, 13, 15, 16 -> "ISO-8859-$codePageRaw"
            80 -> "KOI8-R"
            85 -> "KOI8-U"
            else -> return null
        }
        return runCatching {
            Charset.forName(charsetName).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytesRaw.map { it.toByte() }.toByteArray())).toString()
                .trimEnd('\u0000', ' ').takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        }.getOrNull()
    }
}

sealed interface CardMetadataRecord { val timeUtc: Instant? }
data class CardDrivingLicenceRecord(val authority: CardEncodedText, val issuingNationRaw: Int,
    val numberBytesRaw: List<Int>) : CardMetadataRecord {
    override val timeUtc: Instant? get() = null
    val numberIsBlank: Boolean get() = numberBytesRaw.all { it == 0 || it == 32 }
    val numberText: String? get() = printableIa5(numberBytesRaw)
    val numberHex: String get() = numberBytesRaw.joinToString(" ") { "%02X".format(it) }
}
data class CardCurrentUsageRecord(val sessionOpenTimeRaw: Long, val vehicleNationRaw: Int,
    val vehicleRegistration: CardEncodedText) : CardMetadataRecord {
    override val timeUtc: Instant? get() = recordedTime(sessionOpenTimeRaw)
}
data class CardDownloadRecord(val lastDownloadTimeRaw: Long) : CardMetadataRecord {
    override val timeUtc: Instant? get() = recordedTime(lastDownloadTimeRaw)
}

private fun printableIa5(bytes: List<Int>): String? {
    val content = bytes.dropLastWhile { it == 0 || it == 32 }
    return content.takeIf { it.isNotEmpty() && it.all { byte -> byte in 32..126 } }
        ?.joinToString("") { it.toChar().toString() }
}

/** EU2016/799 Appendix1 2.16/2.18/2.89/2.99/2.166/2.167/chapter4; TCS150/154.
 * None of these files supplies a driving-licence expiry/class, usage end, or this app's import time. */
object CardMetadataParser {
    fun parse(file: CardMetadataFile): CardMetadataRecord? {
        val bytes = file.bytes
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 255.toByte() }) return null
        val r = BinaryReader(bytes)
        fun codedText(size: Int, field: String) = CardEncodedText(r.u8("$field.codePage"),
            r.slice(size, field).map { it.toInt() and 255 })
        return when (file.kind) {
            CardMetadataKind.DRIVING_LICENCE -> {
                val authority = codedText(35, "drivingLicenceIssuingAuthority")
                val nation = r.u8("drivingLicenceIssuingNation")
                val number = r.slice(16, "drivingLicenceNumber").map { it.toInt() and 255 }
                CardDrivingLicenceRecord(authority, nation, number).takeUnless {
                    authority.codePageRaw in 0..1 && authority.isBlank && nation == 0 && it.numberIsBlank
                }
            }
            CardMetadataKind.CURRENT_USAGE -> {
                val time = r.u32be("sessionOpenTime")
                val nation = r.u8("sessionOpenVehicle.vehicleRegistrationNation")
                val plate = codedText(13, "sessionOpenVehicle.vehicleRegistrationNumber")
                CardCurrentUsageRecord(time, nation, plate).takeUnless {
                    time == 0L && nation == 0 && plate.codePageRaw in 0..1 && plate.isBlank
                }
            }
            CardMetadataKind.LAST_CARD_DOWNLOAD -> CardDownloadRecord(r.u32be("lastCardDownload"))
            CardMetadataKind.ICC_IDENTIFICATION, CardMetadataKind.IC_IDENTIFICATION -> CardMasterFileParser.parse(file)
        }
    }
}

