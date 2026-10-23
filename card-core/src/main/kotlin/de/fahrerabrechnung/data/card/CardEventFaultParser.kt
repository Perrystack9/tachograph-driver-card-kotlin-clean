package de.fahrerabrechnung.data.card

import java.nio.charset.Charset
import java.time.Instant

enum class CardEventFaultKind(val fileId: Int, val field: String) {
    EVENTS(0x0502, "Events_Data"), FAULTS(0x0503, "Faults_Data")
}

/** A complete EF from one selected card application. Slots retain their physical position. */
class CardEventFaultFile(val kind: CardEventFaultKind, val generation: CardGeneration,
                         val recordsPerType: Int, bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()
}

data class CardEventFaultRecord(
    val groupIndex: Int, val slotIndex: Int, val type: Int,
    val beginUtc: Instant?, val endUtc: Instant?,
    val registrationNation: Int?, val registrationNumber: String?,
)

/** Appendix 1 2.19–2.22 and Appendix 2 TCS_150/154/155, amended in 2018/502 and 2021/1228.
 * No ring pointer: six/eleven event groups or two fault groups, each with 24-byte slots.
 * Preserve unknown fields, type codes and independent times, including inverted time overlaps.
 */
object CardEventFaultParser {
    const val RECORD_LENGTH = 24

    fun groupCount(kind: CardEventFaultKind, generation: CardGeneration): Int {
        if (generation !in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            throw ParseError(0, kind.field, "unsupported generation")
        return if (kind == CardEventFaultKind.FAULTS) 2 else if (generation == CardGeneration.GEN1) 6 else 11
    }

    fun fileLength(kind: CardEventFaultKind, generation: CardGeneration, recordsPerType: Int): Int {
        val groups = groupCount(kind, generation)
        val range = if (kind == CardEventFaultKind.EVENTS) 6..12 else 12..24
        if (recordsPerType !in range || (generation == CardGeneration.GEN2V2 && recordsPerType != range.last))
            throw ParseError(0, kind.field, "invalid record count")
        return groups * recordsPerType * RECORD_LENGTH
    }

    fun parse(file: CardEventFaultFile): List<CardEventFaultRecord> {
        val length = fileLength(file.kind, file.generation, file.recordsPerType)
        if (file.bytes.size != length) throw ParseError(0, file.kind.field, "incorrect complete file length")
        return buildList {
            repeat(groupCount(file.kind, file.generation)) { group ->
                repeat(file.recordsPerType) slotLoop@ { slot ->
                    val offset = (group * file.recordsPerType + slot) * RECORD_LENGTH
                    val raw = file.bytes.copyOfRange(offset, offset + RECORD_LENGTH)
                    val r = BinaryReader(raw)
                    val type = r.u8("type")
                    fun time(field: String) = r.u32be(field).takeIf { it != 0L && it != 0xffffffffL }
                        ?.let(Instant::ofEpochSecond)
                    val begin = time("beginTime"); val end = time("endTime")
                    val nation = r.u8("registrationNation").takeIf { it != 0 && it != 255 }
                    val page = r.u8("registrationNumber.codePage")
                    val plateBytes = r.slice(13, "registrationNumber")
                    val blankPlate = plateBytes.all { it == 0.toByte() || it == 255.toByte() || it == 32.toByte() }
                    // Personalisation defaults are zero/space; unknown/not-applicable fields are FF.
                    if (type in setOf(0, 255) && begin == null && end == null && nation == null && blankPlate)
                        return@slotLoop
                    val plate = if (blankPlate) null else {
                        val charset = try { when (page) {
                            0, 1 -> Charsets.ISO_8859_1
                            2, 3, 5, 7, 9, 13, 15, 16 -> Charset.forName("ISO-8859-$page")
                            80 -> Charset.forName("KOI8-R")
                            85 -> Charset.forName("KOI8-U")
                            else -> throw ParseError(offset + 10, "registrationNumber.codePage", "unsupported code page")
                        } } catch (_: IllegalArgumentException) {
                            throw ParseError(offset + 10, "registrationNumber.codePage", "unavailable code page")
                        }
                        plateBytes.toString(charset).trimEnd('\u0000', ' ').ifBlank { null }
                    }
                    add(CardEventFaultRecord(group, slot, type, begin, end, nation, plate))
                }
            }
        }
    }
}

