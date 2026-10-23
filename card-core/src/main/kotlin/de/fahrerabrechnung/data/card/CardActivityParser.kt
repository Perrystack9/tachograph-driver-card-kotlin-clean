package de.fahrerabrechnung.data.card

import java.time.Instant

enum class DriverActivity { REST, AVAILABILITY, WORK, DRIVING }
enum class CardSlot { DRIVER, CO_DRIVER }

data class ActivityChange(
    val minuteOfDay: Int,
    val activity: DriverActivity,
    val slot: CardSlot,
    val crewDriving: Boolean,
    val cardInserted: Boolean
)

data class CardActivityDay(
    val ringOffset: Int,
    val recordDateUtc: Instant,
    val dailyPresenceCounter: String,
    val distanceKm: Int,
    val changes: List<ActivityChange>
)

/**
 * Decodes EF Driver_Activity_Data's cyclic CardActivityDailyRecord sequence.
 * This is source data, not inferred work/rest intervals: card withdrawal never
 * creates a rest period. Appendix 2 of EU 2016/799 defines the record and bits.
 */
object CardActivityParser {
    fun parse(file: CardActivityFile): List<CardActivityDay> {
        val ring = file.dailyRecords
        val size = ring.size
        if (size < 14 || file.oldestDayOffset !in ring.indices ||
            file.newestDayOffset !in ring.indices)
            throw ParseError(0, "Driver_Activity_Data", "invalid ring metadata")

        // Appendix2 TCS150/154, default tables (consolidation pp281/284):
        // both pointers and the entire unused ring are zero. A zero header alone proves nothing.
        if (file.oldestDayOffset == 0 && file.newestDayOffset == 0 && ring.all { it == 0.toByte() })
            return emptyList()

        fun u16(offset: Int): Int =
            ((ring[offset % size].toInt() and 255) shl 8) or
                (ring[(offset + 1) % size].toInt() and 255)

        val days = ArrayList<CardActivityDay>()
        val visited = HashSet<Int>()
        var offset = file.oldestDayOffset
        var previousLength = 0
        var used = 0
        while (true) {
            if (!visited.add(offset)) throw ParseError(offset, "activityDailyRecords", "cyclic pointer")
            val previous = u16(offset)
            val length = u16(offset + 2)
            if (previous != previousLength)
                throw ParseError(offset, "activityPreviousRecordLength", "broken record chain")
            if (length !in 14..minOf(size, 2892) || (length - 12) % 2 != 0 ||
                used + length > size)
                throw ParseError(offset, "activityRecordLength", "invalid or overlapping record")
            val raw = ByteArray(length) { ring[(offset + it) % size] }
            val reader = BinaryReader(raw)
            reader.u16be("activityPreviousRecordLength")
            reader.u16be("activityRecordLength")
            val date = reader.epochSeconds("activityRecordDate")
            val presence = reader.bcd(4, "activityDailyPresenceCounter")
            val distance = reader.u16be("activityDayDistance")
            val changes = ArrayList<ActivityChange>()
            var lastMinute = -1
            repeat((length - 12) / 2) {
                val word = reader.u16be("activityChangeInfo")
                val minute = word and 0x07FF
                if (minute !in 0..1439 || minute < lastMinute ||
                    (it == 0 && minute != 0))
                    throw ParseError(offset, "activityChangeInfo", "invalid change time")
                changes += ActivityChange(
                    minuteOfDay = minute,
                    activity = DriverActivity.entries[(word ushr 11) and 3],
                    slot = if ((word and 0x8000) == 0) CardSlot.DRIVER else CardSlot.CO_DRIVER,
                    crewDriving = (word and 0x4000) != 0,
                    cardInserted = (word and 0x2000) == 0
                )
                lastMinute = minute
            }
            days += CardActivityDay(offset, date, presence, distance, changes)
            used += length
            if (offset == file.newestDayOffset) return days
            previousLength = length
            offset = (offset + length) % size
        }
    }
}

