package de.fahrerabrechnung.data.card

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CardActivityParserTest {
    private val firstDate = Instant.parse("2026-09-27T00:00:00Z").epochSecond
    private val secondDate = Instant.parse("2026-09-28T00:00:00Z").epochSecond

    private fun day(previous: Int, date: Long, distance: Int, vararg changes: Int): ByteArray {
        val data = ByteArray(12 + changes.size * 2)
        fun u16(offset: Int, value: Int) {
            data[offset] = (value ushr 8).toByte()
            data[offset + 1] = value.toByte()
        }
        u16(0, previous)
        u16(2, data.size)
        for (i in 0..3) data[4 + i] = (date ushr (24 - 8 * i)).toByte()
        data[8] = 0
        data[9] = 1 // BCD daily presence counter: 0001
        u16(10, distance)
        changes.forEachIndexed { index, word -> u16(12 + index * 2, word) }
        return data
    }

    private fun ring(vararg entries: Pair<Int, ByteArray>): ByteArray =
        ByteArray(64).also { bytes ->
            entries.forEach { (offset, record) ->
                record.forEachIndexed { i, b -> bytes[(offset + i) % bytes.size] = b }
            }
        }

    private fun failure(block: () -> Unit): ParseError {
        try { block() } catch (e: ParseError) { return e }
        throw AssertionError("ParseError expected")
    }

    @Test fun wrappedRecordsKeepChronologyAndCardStatus() {
        val older = day(0, firstDate, 37, 0, 0x1000 or 180, 0x1800 or 900)
        val newer = day(older.size, secondDate, 12, 0, 0x2000 or 60)
        val days = CardActivityParser.parse(CardActivityFile(48, 2, ring(48 to older, 2 to newer)))
        assertEquals(2, days.size)
        assertEquals(48, days[0].ringOffset)
        assertEquals(Instant.ofEpochSecond(firstDate), days[0].recordDateUtc)
        assertEquals("0001", days[0].dailyPresenceCounter)
        assertEquals(37, days[0].distanceKm)
        assertEquals(listOf(0, 180, 900), days[0].changes.map { it.minuteOfDay })
        assertEquals(DriverActivity.WORK, days[0].changes[1].activity)
        assertEquals(DriverActivity.DRIVING, days[0].changes[2].activity)
        assertEquals(12, days[1].distanceKm)
        assertEquals(DriverActivity.REST, days[1].changes[1].activity)
        assertFalse(days[1].changes[1].cardInserted)
    }

    @Test fun brokenLengthsAndCircularLinksAreRejected() {
        val older = day(0, firstDate, 1, 0)
        val newer = day(older.size, secondDate, 2, 0)
        val bytes = ring(40 to older, 54 to newer)
        assertEquals(2, CardActivityParser.parse(CardActivityFile(40, 54, bytes)).size)
        val badPrevious = bytes.copyOf().also { it[55] = 1 }
        assertEquals("activityPreviousRecordLength",
            failure { CardActivityParser.parse(CardActivityFile(40, 54, badPrevious)) }.field)
        val badLength = bytes.copyOf().also { it[43] = 0 }
        assertEquals("activityRecordLength",
            failure { CardActivityParser.parse(CardActivityFile(40, 54, badLength)) }.field)
        assertEquals("activityPreviousRecordLength",
            failure { CardActivityParser.parse(CardActivityFile(40, 41, bytes)) }.field)
    }

    @Test fun invalidChangeTimeAndBcdAreRejected() {
        val late = day(0, firstDate, 1, 0, 1440)
        assertEquals("activityChangeInfo",
            failure { CardActivityParser.parse(CardActivityFile(0, 0, ring(0 to late))) }.field)
        val bcd = day(0, firstDate, 1, 0).also { it[9] = 0xFA.toByte() }
        assertEquals("activityDailyPresenceCounter",
            failure { CardActivityParser.parse(CardActivityFile(0, 0, ring(0 to bcd))) }.field)
    }
}

