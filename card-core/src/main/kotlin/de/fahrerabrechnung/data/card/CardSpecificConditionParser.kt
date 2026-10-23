package de.fahrerabrechnung.data.card

import java.time.Instant

/** Complete EF 0522, including the Gen2 ring pointer. Never an inferred time interval. */
class CardSpecificConditionFile(val generation: CardGeneration, val recordCount: Int, bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()
}

data class CardSpecificConditionRecord(val slotIndex: Int, val timeUtc: Instant?, val type: Int)

/** EU 2016/799 Appendix 1 2.152–154; Appendix 2 TCS_150/154/155, amended by 2021/1228. */
object CardSpecificConditionParser {
    const val RECORD_LENGTH = 5

    fun fileLength(generation: CardGeneration, count: Int): Int {
        val valid = when (generation) {
            CardGeneration.GEN1 -> count == 56
            CardGeneration.GEN2 -> count in 56..112
            CardGeneration.GEN2V2 -> count == 112
            else -> false
        }
        if (!valid) throw ParseError(0, "Specific_Conditions", "unsupported record count or generation")
        return count * RECORD_LENGTH + if (generation == CardGeneration.GEN1) 0 else 2
    }

    fun parse(file: CardSpecificConditionFile): List<CardSpecificConditionRecord> {
        if (file.bytes.size != fileLength(file.generation, file.recordCount))
            throw ParseError(0, "Specific_Conditions", "incorrect complete file length")
        val header = if (file.generation == CardGeneration.GEN1) 0 else 2
        val newest = if (header == 0) null else BinaryReader(file.bytes).u16be("conditionPointerNewestRecord")
        if (newest != null && newest !in 0 until file.recordCount)
            throw ParseError(0, "conditionPointerNewestRecord", "pointer outside record ring")
        return buildList {
            repeat(file.recordCount) { ordinal ->
                val slot = if (newest == null) ordinal else (newest + 1 + ordinal) % file.recordCount
                val offset = header + slot * RECORD_LENGTH
                val r = BinaryReader(file.bytes.copyOfRange(offset, offset + RECORD_LENGTH))
                val rawTime = r.u32be("entryTime")
                val type = r.u8("specificConditionType")
                val time = rawTime.takeIf { it != 0L && it != 0xffffffffL }?.let(Instant::ofEpochSecond)
                // Personalisation fillers are not a record. A nonempty record keeps unknown fields.
                if (time != null || type !in setOf(0, 255))
                    add(CardSpecificConditionRecord(slot, time, type))
            }
        }
    }

    fun description(generation: CardGeneration, type: Int): String = when (type) {
        1 -> "Out of Scope · Beginn"
        2 -> "Out of Scope · Ende"
        3 -> if (generation == CardGeneration.GEN1) "Fähre/Zug" else "Fähre/Zug · Beginn"
        4 -> if (generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) "Fähre/Zug · Ende"
            else "Unbekannte Sonderbedingung (Code 04)"
        else -> "Unbekannte Sonderbedingung (Code ${type.toString(16).uppercase().padStart(2, '0')})"
    }
}

