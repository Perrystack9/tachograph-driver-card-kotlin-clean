package de.fahrerabrechnung.data.card

import java.time.Instant

/** Complete Gen2V2 driver-card EF Load_Type_Entries, independent of load/unload operations. */
class CardLoadTypeFile(val generation: CardGeneration, val recordCount: Int, bytes: ByteArray) {
    init {
        if (bytes.size != CardLoadTypeParser.fileLength(generation, recordCount))
            throw ParseError(0, "Load_Type_Entries", "incorrect complete file length")
    }
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

enum class CardRecordedLoadType { UNDEFINED, GOODS, PASSENGERS }

/** A recorded entry is not an inferred cargo interval or a vehicle classification. */
data class CardLoadTypeRecord(val slotIndex: Int, val entryTimeRaw: Long, val loadTypeRaw: Int) {
    val entryTimeUtc: Instant? get() = recordedTime(entryTimeRaw)
    val loadType: CardRecordedLoadType? get() = when (loadTypeRaw) {
        0 -> CardRecordedLoadType.UNDEFINED
        1 -> CardRecordedLoadType.GOODS
        2 -> CardRecordedLoadType.PASSENGERS
        else -> null
    }
}

/** EU 2021/1228 Appendix 1 2.24a/b, 2.90a; Appendix 2 TCS_154/155.
 * Driver cards only: a two-byte newest-slot pointer and 336 five-byte records.
 * Individual unknown values are retained; only wholly zero/FF personalisation slots are omitted. */
object CardLoadTypeParser {
    const val RECORD_COUNT = 336
    const val RECORD_LENGTH = 5

    fun fileLength(generation: CardGeneration, count: Int): Int {
        if (generation != CardGeneration.GEN2V2 || count != RECORD_COUNT)
            throw ParseError(0, "Load_Type_Entries", "unsupported record count or generation")
        return 2 + count * RECORD_LENGTH
    }

    fun parse(file: CardLoadTypeFile): List<CardLoadTypeRecord> {
        val bytes = file.bytes
        val newest = BinaryReader(bytes).u16be("loadTypeEntryPointerNewestRecord")
        if (newest !in 0 until file.recordCount)
            throw ParseError(0, "loadTypeEntryPointerNewestRecord", "pointer outside record ring")
        return buildList {
            repeat(file.recordCount) { ordinal ->
                val slot = (newest + 1 + ordinal) % file.recordCount
                val offset = 2 + slot * RECORD_LENGTH
                val raw = bytes.copyOfRange(offset, offset + RECORD_LENGTH)
                if (!raw.all { it == 0.toByte() } && !raw.all { it == 255.toByte() }) {
                    val reader = BinaryReader(raw)
                    add(CardLoadTypeRecord(slot, reader.u32be("timeStamp"), reader.u8("loadTypeEntered")))
                }
            }
        }
    }
}

