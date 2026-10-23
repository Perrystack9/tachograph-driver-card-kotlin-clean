package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CardBorderCrossingsParserTest {
    private fun record(left: Int = 13, entered: Int = 1, time: Long = 1790000010L,
                       hdop: Int = 15, lat: Int = -49123, lon: Int = 123456,
                       status: Int = 1, odometer: Int = 12345): ByteArray {
        val b = ByteBuffer.allocate(17)
        fun u24(value: Int) { b.put((value shr 16).toByte()).put((value shr 8).toByte()).put(value.toByte()) }
        b.put(left.toByte()).put(entered.toByte()).putInt(time.toInt()).put(hdop.toByte())
        u24(lat); u24(lon); b.put(status.toByte()); u24(odometer)
        return b.array()
    }
    private fun bytes(newest: Int = 0, rows: Map<Int, ByteArray> = mapOf(0 to record())): ByteArray {
        val result = ByteArray(19042)
        ByteBuffer.wrap(result).putShort(newest.toShort())
        rows.forEach { (slot, raw) -> raw.copyInto(result, 2 + slot * 17) }
        return result
    }
    private fun parse(newest: Int = 0, rows: Map<Int, ByteArray> = mapOf(0 to record())) =
        CardBorderCrossingsParser.parse(CardBorderCrossingsFile(CardGeneration.GEN2V2, 1120, bytes(newest, rows)))
    private fun rejected(action: () -> Unit) {
        try { action(); fail("ParseError expected") } catch (_: ParseError) { }
    }

    @Test fun supportsOnlyCompleteGen2V2DriverCardCapacity() {
        assertEquals(19042, CardBorderCrossingsParser.fileLength(CardGeneration.GEN2V2, 1120))
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.UNKNOWN))
            rejected { CardBorderCrossingsParser.fileLength(g, 1120) }
        for (count in listOf(-1, 0, 4, 1119, 1121, Int.MAX_VALUE))
            rejected { CardBorderCrossingsParser.fileLength(CardGeneration.GEN2V2, count) }
    }
    @Test fun rejectsTruncatedAndOverlongFilesBeforeParsing() {
        for (length in listOf(0, 1, 2, 18, 19, 19041, 19043))
            rejected { CardBorderCrossingsFile(CardGeneration.GEN2V2, 1120, ByteArray(length)) }
    }
    @Test fun fieldOffsetsRetainTheInlinePositionAndItsOwnStatus() {
        val row = parse().single()
        assertEquals(0, row.slotIndex); assertEquals(13, row.countryLeftRaw); assertEquals(1, row.countryEnteredRaw)
        assertEquals(1790000010L, row.positionTimeRaw); assertEquals(15, row.accuracyRaw)
        assertEquals(-49123, row.latitudeRaw); assertEquals(123456, row.longitudeRaw)
        assertEquals(1, row.authenticationStatusRaw); assertEquals(12345, row.odometerRaw)
    }
    @Test fun rejectsPointersOutsideThePhysicalRing() {
        for (pointer in listOf(1120, 1121, 65535)) rejected { parse(pointer) }
        assertEquals(0, parse(0).single().slotIndex)
        assertEquals(1119, parse(1119, mapOf(1119 to record())).single().slotIndex)
    }
    @Test fun wrappedOrderPreservesDuplicatesAndNonChronologicalTimes() {
        val rows = parse(0, mapOf(1118 to record(time = 1790000030L),
            1119 to record(time = 1790000010L), 0 to record(time = 1790000010L)))
        assertEquals(listOf(1118, 1119, 0), rows.map { it.slotIndex })
        assertEquals(listOf(1790000030L, 1790000010L, 1790000010L), rows.map { it.positionTimeRaw })
    }
    @Test fun fullRingRetainsEveryRecordIncludingTheNewest() {
        val rows = parse(1119, (0 until 1120).associateWith { record(odometer = it) })
        assertEquals((0 until 1120).toList(), rows.map { it.slotIndex })
        assertEquals((0 until 1120).toList(), rows.map { it.odometerRaw })
    }
    @Test fun wholeZeroAndFfFillersAreOmitted() {
        assertTrue(parse(rows = emptyMap()).isEmpty())
        assertTrue(parse(rows = mapOf(0 to ByteArray(17) { 255.toByte() })).isEmpty())
    }
    @Test fun partialUnknownRecordIsKeptWithoutRejectingOtherRecords() {
        val raw = ByteArray(17) { 255.toByte() }; raw[0] = 0
        val rows = parse(1, mapOf(0 to raw, 1 to record()))
        assertEquals(2, rows.size)
        val unknown = rows.first()
        assertEquals(0, unknown.countryLeftRaw); assertEquals(255, unknown.countryEnteredRaw)
        assertNull(unknown.positionTimeUtc); assertNull(unknown.position); assertNull(unknown.recordedStatus)
        assertNull(unknown.odometerKm); assertNotNull(rows.last().position)
    }
    @Test fun allCountryValuesRemainRawIncludingInsertionAndRestOfWorldCodes() {
        for (code in 0..255) {
            val row = parse(rows = mapOf(0 to record(left = code, entered = code))).single()
            assertEquals(code, row.countryLeftRaw); assertEquals(code, row.countryEnteredRaw)
        }
    }
    @Test fun unsignedTimesAfter2038RemainExactUtcInstants() {
        for (time in listOf(0x80000001L, 0xfffffffeL))
            assertEquals(time, parse(rows = mapOf(0 to record(time = time))).single().positionTimeUtc!!.epochSecond)
    }
    @Test fun unknownTimeDoesNotBecomeAnInventedPosition() {
        for (time in listOf(0L, 0xffffffffL)) {
            val row = parse(rows = mapOf(0 to record(time = time))).single()
            assertEquals(time, row.positionTimeRaw); assertNull(row.positionTimeUtc); assertNull(row.position)
            assertEquals(CardRecordedGnssAuthenticationStatus.AUTHENTICATED, row.recordedStatus)
        }
    }
    @Test fun everyAuthenticationStatusIsRetainedWithoutInventedTrust() {
        for (status in 0..255) {
            val row = parse(rows = mapOf(0 to record(status = status))).single()
            assertEquals(status, row.authenticationStatusRaw)
            assertEquals(when (status) {
                0 -> CardRecordedGnssAuthenticationStatus.NOT_AUTHENTICATED
                1 -> CardRecordedGnssAuthenticationStatus.AUTHENTICATED
                else -> null
            }, row.recordedStatus)
            assertNotNull(row.position)
        }
    }
    @Test fun signedDegreesMinutesAndZeroPositionsDecodePrecisely() {
        val p = parse().single().position!!
        assertEquals(-49.205, p.latitude, 0.00000001); assertEquals(123.76, p.longitude, 0.00000001)
        val zero = parse(rows = mapOf(0 to record(lat = 0, lon = 0))).single().position!!
        assertEquals(0.0, zero.latitude, 0.0); assertEquals(0.0, zero.longitude, 0.0)
        val nearZero = parse(rows = mapOf(0 to record(lat = -1, lon = 1))).single().position!!
        assertEquals(-1.0 / 600, nearZero.latitude, 0.00000001)
        assertEquals(1.0 / 600, nearZero.longitude, 0.00000001)
    }
    @Test fun invalidCoordinatesRemainRawAndDoNotBecomeAPosition() {
        for (lat in listOf(49600, 90001, -90001, 0x7fffff, -0x800000)) {
            val row = parse(rows = mapOf(0 to record(lat = lat))).single()
            assertEquals(lat, row.latitudeRaw); assertNull(row.position)
        }
        for (lon in listOf(123600, 180001, -180001, 0x7fffff, -0x800000))
            assertNull(parse(rows = mapOf(0 to record(lon = lon))).single().position)
        for (sign in listOf(-1, 1)) {
            val p = parse(rows = mapOf(0 to record(lat = sign * 90000, lon = sign * 180000))).single().position!!
            assertEquals(sign * 90.0, p.latitude, 0.0); assertEquals(sign * 180.0, p.longitude, 0.0)
        }
    }
    @Test fun hdopAndOdometerUseTheirOwnBoundsWithoutDistanceCalculation() {
        for (hdop in listOf(1, 100))
            assertEquals(hdop / 10.0, parse(rows = mapOf(0 to record(hdop = hdop))).single().accuracyHdop!!, 0.0)
        for (hdop in listOf(0, 101, 255)) {
            val row = parse(rows = mapOf(0 to record(hdop = hdop))).single()
            assertEquals(hdop, row.accuracyRaw); assertNull(row.accuracyHdop); assertNotNull(row.position)
        }
        for (odometer in listOf(0, 9999999))
            assertEquals(odometer, parse(rows = mapOf(0 to record(odometer = odometer))).single().odometerKm!!)
        for (odometer in listOf(10000000, 0xffffff)) {
            val row = parse(rows = mapOf(0 to record(odometer = odometer))).single()
            assertEquals(odometer, row.odometerRaw); assertNull(row.odometerKm)
        }
    }
    @Test fun rawInputAndEveryReturnedBufferAreDefensiveCopies() {
        val source = bytes()
        val file = CardBorderCrossingsFile(CardGeneration.GEN2V2, 1120, source)
        source.fill(0); file.bytes.fill(0)
        assertEquals(1790000010L, CardBorderCrossingsParser.parse(file).single().positionTimeRaw)
        assertNotSame(file.bytes, file.bytes)
    }
}

