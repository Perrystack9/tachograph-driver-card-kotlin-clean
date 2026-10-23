package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CardLoadTypeParserTest {
    private fun record(time: Long = 1790000000L, type: Int = 1): ByteArray =
        ByteBuffer.allocate(5).putInt(time.toInt()).put(type.toByte()).array()

    private fun bytes(newest: Int = 0, rows: Map<Int, ByteArray> = mapOf(0 to record()),
                      fill: Byte = 0): ByteArray = ByteArray(1682) { fill }.also { file ->
        file[0] = (newest shr 8).toByte(); file[1] = newest.toByte()
        rows.forEach { (slot, row) -> row.copyInto(file, 2 + slot * 5) }
    }

    private fun parse(newest: Int = 0, rows: Map<Int, ByteArray> = mapOf(0 to record())) =
        CardLoadTypeParser.parse(CardLoadTypeFile(CardGeneration.GEN2V2, 336, bytes(newest, rows)))

    @Test fun fixedDriverCapacityAndCompleteLengthAreRequired() {
        assertEquals(1682, CardLoadTypeParser.fileLength(CardGeneration.GEN2V2, 336))
        for (generation in CardGeneration.entries.filter { it != CardGeneration.GEN2V2 })
            assertThrows(ParseError::class.java) { CardLoadTypeParser.fileLength(generation, 336) }
        for (count in listOf(-1, 0, 4, 335, 337, 65535))
            assertThrows(ParseError::class.java) { CardLoadTypeParser.fileLength(CardGeneration.GEN2V2, count) }
        for (length in listOf(0, 2, 1681, 1683))
            assertThrows(ParseError::class.java) { CardLoadTypeFile(CardGeneration.GEN2V2, 336, ByteArray(length)) }
    }

    @Test fun physicalSlotsFollowTheRingAndPreserveDuplicatesAndUnorderedTimes() {
        val same = record(time = 1790000100L)
        val rows = parse(newest = 1, rows = mapOf(0 to record(time = 1790000050L),
            1 to same, 2 to same, 335 to record(time = 1790000000L, type = 2)))
        assertEquals(listOf(2, 335, 0, 1), rows.map { it.slotIndex })
        assertEquals(listOf(1790000100L, 1790000000L, 1790000050L, 1790000100L), rows.map { it.entryTimeRaw })
        assertEquals(rows.first().entryTimeRaw, rows.last().entryTimeRaw)
    }

    @Test fun lastSlotAndEveryRecordInAFullRingAreDecoded() {
        val rows = parse(newest = 335, rows = (0 until 336).associateWith { record(type = it % 3) })
        assertEquals(336, rows.size)
        assertEquals((0 until 336).toList(), rows.map { it.slotIndex })
        assertEquals(CardRecordedLoadType.PASSENGERS, rows.last().loadType)
    }

    @Test fun pointerOutsideTheRingIsRejectedIncludingUnsignedValues() {
        for (pointer in listOf(336, 337, 0x8000, 0xffff))
            assertThrows(ParseError::class.java) { parse(newest = pointer) }
    }

    @Test fun whollyZeroAndWhollyFfSlotsAreEmpty() {
        for (fill in listOf(0.toByte(), 255.toByte())) {
            val file = CardLoadTypeFile(CardGeneration.GEN2V2, 336, bytes(rows = emptyMap(), fill = fill))
            assertTrue(CardLoadTypeParser.parse(file).isEmpty())
        }
    }

    @Test fun partiallyUnknownRecordsRemainAlongsideValidRecords() {
        val partial = ByteArray(5) { 255.toByte() }.also { it[0] = 0 }
        val rows = parse(rows = mapOf(0 to partial, 1 to record(type = 2)))
        assertEquals(listOf(1, 0), rows.map { it.slotIndex })
        assertEquals(0x00ffffffL, rows.last().entryTimeRaw)
        assertEquals(255, rows.last().loadTypeRaw)
        assertNull(rows.last().loadType)
        assertNotNull(rows.last().entryTimeUtc)
    }

    @Test fun everyLoadTypeCodeIsRetainedWithoutReplacingReservedValues() {
        for (code in 0..255) {
            val row = parse(rows = mapOf(0 to record(type = code))).single()
            assertEquals(code, row.loadTypeRaw)
            assertEquals(when (code) {
                0 -> CardRecordedLoadType.UNDEFINED
                1 -> CardRecordedLoadType.GOODS
                2 -> CardRecordedLoadType.PASSENGERS
                else -> null
            }, row.loadType)
        }
    }

    @Test fun undefinedLoadTypeWithARecordedTimeIsNotAnEmptySlot() {
        val row = parse(rows = mapOf(0 to record(type = 0))).single()
        assertEquals(CardRecordedLoadType.UNDEFINED, row.loadType)
        assertEquals(1790000000L, row.entryTimeUtc!!.epochSecond)
    }

    @Test fun unsignedTimesAfter2038RemainExactUtcInstants() {
        for (time in listOf(0x80000001L, 0xfffffffeL)) {
            val row = parse(rows = mapOf(0 to record(time = time))).single()
            assertEquals(time, row.entryTimeRaw)
            assertEquals(time, row.entryTimeUtc!!.epochSecond)
        }
    }

    @Test fun missingTimesRetainTheLoadTypeWithoutAnInventedInstant() {
        for (time in listOf(0L, 0xffffffffL)) {
            val row = parse(rows = mapOf(0 to record(time = time, type = 2))).single()
            assertEquals(time, row.entryTimeRaw)
            assertNull(row.entryTimeUtc)
            assertEquals(CardRecordedLoadType.PASSENGERS, row.loadType)
        }
    }

    @Test fun fileDefensivelyCopiesInputAndReturnedBytes() {
        val input = bytes()
        val file = CardLoadTypeFile(CardGeneration.GEN2V2, 336, input)
        input[2] = 0
        file.bytes[6] = 2
        val row = CardLoadTypeParser.parse(file).single()
        assertEquals(1790000000L, row.entryTimeRaw)
        assertEquals(CardRecordedLoadType.GOODS, row.loadType)
    }

    @Test fun applicationCapacityIsIndependentOfBorderAndLoadUnloadCounts() {
        val extension = byteArrayOf(0, 8, 0, 0, 0, 0, 1, 80, 12, 0)
        assertEquals(336, DriverCardDecoder.loadTypeCount(extension, CardGeneration.GEN2V2))
    }

    @Test fun applicationCapacityRejectsIncompleteOrUnsupportedExtensions() {
        val valid = byteArrayOf(0, 8, 4, 96, 6, 88, 1, 80, 12, 0)
        for (generation in CardGeneration.entries.filter { it != CardGeneration.GEN2V2 })
            assertThrows(ParseError::class.java) { DriverCardDecoder.loadTypeCount(valid, generation) }
        for (length in listOf(0, 2, 8, 9, 11))
            assertThrows(ParseError::class.java) { DriverCardDecoder.loadTypeCount(ByteArray(length), CardGeneration.GEN2V2) }
        for (length in listOf(0, 7, 9, 65535)) {
            val bytes = valid.copyOf().also { it[0] = (length shr 8).toByte(); it[1] = length.toByte() }
            assertThrows(ParseError::class.java) { DriverCardDecoder.loadTypeCount(bytes, CardGeneration.GEN2V2) }
        }
    }

    @Test fun applicationCapacityRejectsWorkshopAndWrongDriverCounts() {
        for (count in listOf(0, 4, 335, 337, 65535)) {
            val extension = byteArrayOf(0, 8, 4, 96, 6, 88, (count shr 8).toByte(), count.toByte(), 12, 0)
            assertThrows(ParseError::class.java) { DriverCardDecoder.loadTypeCount(extension, CardGeneration.GEN2V2) }
        }
    }
}

