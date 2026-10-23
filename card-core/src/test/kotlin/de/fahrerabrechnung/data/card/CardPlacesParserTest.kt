package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CardPlacesParserTest {
    private fun record(g: CardGeneration, time: Long = 1790000001, type: Int = 0,
                       positionTime: Long = 1790000010, lat: Int = -49123, lon: Int = 123456): ByteArray {
        val b = ByteBuffer.allocate(CardPlacesParser.recordLength(g))
        fun u24(value: Int) { b.put((value shr 16).toByte()).put((value shr 8).toByte()).put(value.toByte()) }
        b.putInt(time.toInt()).put(type.toByte()).put(13.toByte()).put(7.toByte()); u24(12345)
        if (g != CardGeneration.GEN1) {
            b.putInt(positionTime.toInt()).put(15.toByte()); u24(lat); u24(lon)
        }
        return b.array()
    }
    private fun file(g: CardGeneration = CardGeneration.GEN2V2, count: Int = 112,
                     newest: Int = 0, records: Map<Int, ByteArray> = mapOf(0 to record(g))): CardPlacesFile {
        val bytes = ByteArray(CardPlacesParser.fileLength(g, count))
        val header = CardPlacesParser.headerLength(g)
        if (header == 1) bytes[0] = newest.toByte() else ByteBuffer.wrap(bytes).putShort(newest.toShort())
        records.forEach { (slot, raw) -> raw.copyInto(bytes, header + slot * CardPlacesParser.recordLength(g)) }
        return CardPlacesFile(g, count, bytes)
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("ParseError expected") } catch (_: ParseError) { }
    }
    @Test fun generationsHaveDifferentHeadersAndCompleteRecordLengths() {
        assertEquals(841, CardPlacesParser.fileLength(CardGeneration.GEN1, 84))
        assertEquals(1121, CardPlacesParser.fileLength(CardGeneration.GEN1, 112))
        assertEquals(1766, CardPlacesParser.fileLength(CardGeneration.GEN2, 84))
        assertEquals(2354, CardPlacesParser.fileLength(CardGeneration.GEN2V2, 112))
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2)) {
            assertEquals(1, CardPlacesParser.parse(file(g, 84)).size)
            rejected { CardPlacesParser.fileLength(g, 83) }; rejected { CardPlacesParser.fileLength(g, 113) }
        }
        rejected { CardPlacesParser.fileLength(CardGeneration.GEN2V2, 84) }
        rejected { CardPlacesParser.fileLength(CardGeneration.UNKNOWN, 112) }
        for (length in listOf(0, 2353, 2355)) rejected { CardPlacesFile(CardGeneration.GEN2V2, 112, ByteArray(length)) }
    }
    @Test fun pointerIsPhysicalAndRingPreservesDuplicatesAndNonChronologicalTimes() {
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2V2)) {
            val rows = CardPlacesParser.parse(file(g, newest = 1, records = mapOf(
                111 to record(g, 1790000030), 0 to record(g, 1790000001), 1 to record(g, 1790000001))))
            assertEquals(listOf(111, 0, 1), rows.map { it.slotIndex })
            assertEquals(listOf(1790000030L, 1790000001L, 1790000001L), rows.map { it.entryTimeRaw })
            rejected { CardPlacesParser.parse(file(g, newest = 112)) }
        }
    }
    @Test fun gen1HasNoInventedPositionAndReservedTypesAreNotBeginOrEnd() {
        for (type in 0..7) {
            val row = CardPlacesParser.parse(file(CardGeneration.GEN1, records = mapOf(0 to record(CardGeneration.GEN1, type = type)))).single()
            assertNull(row.position); assertNull(row.positionTimeRaw)
            assertEquals(if (type > 5) null else if (type % 2 == 0) CardWorkPeriodEntry.BEGIN else CardWorkPeriodEntry.END, row.entry)
            assertEquals(13, row.countryRaw); assertEquals(7, row.regionRaw); assertEquals(12345, row.odometerKm)
        }
    }
    @Test fun gen2AllowsGnssEntryTypesButReservedCodesRemainRaw() {
        for (type in listOf(6, 7, 8, 255)) {
            val row = CardPlacesParser.parse(file(records = mapOf(0 to record(CardGeneration.GEN2V2, type = type)))).single()
            assertEquals(type, row.entryTypeRaw)
            assertEquals(when (type) { 6 -> CardWorkPeriodEntry.BEGIN; 7 -> CardWorkPeriodEntry.END; else -> null }, row.entry)
        }
    }
    @Test fun unsignedTimeAndIndependentPositionTimeAreRetained() {
        val row = CardPlacesParser.parse(file(records = mapOf(0 to record(CardGeneration.GEN2V2, 0x80000001L, positionTime = 0)))).single()
        assertEquals(0x80000001L, row.entryTimeUtc!!.epochSecond); assertNull(row.position)
        val unknown = CardPlacesParser.parse(file(records = mapOf(0 to record(CardGeneration.GEN2V2, 0xffffffffL)))).single()
        assertNull(unknown.entryTimeUtc); assertEquals(1790000010L, unknown.position!!.timeUtc.epochSecond)
    }
    @Test fun signedCoordinatesUseDegreesMinutesAndHdopIsNotMetres() {
        val row = CardPlacesParser.parse(file()).single()
        assertEquals(-49.205, row.position!!.latitude, 0.00000001)
        assertEquals(123.76, row.position!!.longitude, 0.00000001)
        assertEquals(1.5, row.accuracyHdop!!, 0.0)
        for (lat in listOf(49600, 90001, 0x7fffff)) assertNull(CardPlacesParser.parse(file(records = mapOf(0 to
            record(CardGeneration.GEN2V2, lat = lat)))).single().position)
        val zero = CardPlacesParser.parse(file(records = mapOf(0 to record(CardGeneration.GEN2V2, lat = 0, lon = 0)))).single()
        assertEquals(0.0, zero.position!!.latitude, 0.0)
    }
    @Test fun onlyWholeFillersAreOmittedAndInvalidValuesSurvive() {
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2V2)) {
            assertTrue(CardPlacesParser.parse(file(g, records = emptyMap())).isEmpty())
            assertTrue(CardPlacesParser.parse(file(g, records = mapOf(0 to ByteArray(CardPlacesParser.recordLength(g)) { 255.toByte() }))).isEmpty())
            val raw = ByteArray(CardPlacesParser.recordLength(g)) { 255.toByte() }; raw[4] = 254.toByte()
            val row = CardPlacesParser.parse(file(g, records = mapOf(0 to raw))).single()
            assertNull(row.entryTimeUtc); assertNull(row.entry); assertNull(row.odometerKm)
            assertEquals(255, row.countryRaw); assertEquals(255, row.regionRaw); assertNull(row.position)
        }
    }
    @Test fun rawInputAndReturnedBuffersCannotMutateTheFile() {
        val bytes = file().bytes
        val frozen = CardPlacesFile(CardGeneration.GEN2V2, 112, bytes)
        bytes.fill(0); frozen.bytes.fill(0)
        assertEquals(1790000001L, CardPlacesParser.parse(frozen).single().entryTimeRaw)
    }
}

