package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CardSpecificConditionParserTest {
    private fun file(g: CardGeneration = CardGeneration.GEN2V2, newest: Int = 0,
                     entries: List<Triple<Int, Long, Int>> = emptyList()): CardSpecificConditionFile {
        val n = if (g == CardGeneration.GEN1) 56 else 112
        val bytes = ByteArray(CardSpecificConditionParser.fileLength(g, n))
        val b = ByteBuffer.wrap(bytes)
        val header = if (g == CardGeneration.GEN1) 0 else 2
        if (header != 0) b.putShort(newest.toShort())
        for ((slot, time, type) in entries) {
            b.position(header + 5 * slot)
            b.putInt(time.toInt()).put(type.toByte())
        }
        return CardSpecificConditionFile(g, n, bytes)
    }
    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: ParseError) { return }
        throw AssertionError("ParseError expected")
    }

    @Test fun fileCopiesInput() {
        val raw = file().bytes
        val copied = CardSpecificConditionFile(CardGeneration.GEN2V2, 112, raw)
        raw[2] = 1
        assertEquals(0, copied.bytes[2].toInt())
    }

    @Test fun capacitiesAndApplicationOffsetsFollowEachGeneration() {
        assertEquals(280, CardSpecificConditionParser.fileLength(CardGeneration.GEN1, 56))
        assertEquals(282, CardSpecificConditionParser.fileLength(CardGeneration.GEN2, 56))
        assertEquals(562, CardSpecificConditionParser.fileLength(CardGeneration.GEN2V2, 112))
        assertEquals(56, DriverCardDecoder.specificConditionCount(ByteArray(10), CardGeneration.GEN1))
        val application = ByteArray(15).also { it[12] = 99; it[14] = 112 }
        assertEquals(112, DriverCardDecoder.specificConditionCount(application, CardGeneration.GEN2V2))
        application[14] = 56
        assertEquals(56, DriverCardDecoder.specificConditionCount(application, CardGeneration.GEN2))
        rejected { DriverCardDecoder.specificConditionCount(application, CardGeneration.GEN2V2) }
    }
    @Test fun unsupportedGenerationCapacityAndApplicationLengthAreRejected() {
        for (g in listOf(CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED)) rejected { CardSpecificConditionParser.fileLength(g, 112) }
        for (count in listOf(0, 55, 113, 65535)) rejected { CardSpecificConditionParser.fileLength(CardGeneration.GEN2, count) }
        rejected { CardSpecificConditionParser.fileLength(CardGeneration.GEN1, 112) }
        rejected { DriverCardDecoder.specificConditionCount(ByteArray(14), CardGeneration.GEN2) }
        rejected { DriverCardDecoder.specificConditionCount(ByteArray(15), CardGeneration.UNKNOWN) }
    }
    @Test fun gen1StartsWithARecordAndHasNoRingPointer() {
        val records = CardSpecificConditionParser.parse(file(CardGeneration.GEN1,
            entries = listOf(Triple(0, 1790000000L, 3), Triple(55, 1790000015L, 2))))
        assertEquals(listOf(0, 55), records.map { it.slotIndex })
        assertEquals(Instant.ofEpochSecond(1790000000L), records.first().timeUtc)
        assertEquals("Fähre/Zug", CardSpecificConditionParser.description(CardGeneration.GEN1, 3))
    }
    @Test fun gen2WrappedRingRetainsPhysicalSlotsAndExactSeconds() {
        val records = CardSpecificConditionParser.parse(file(newest = 1, entries = listOf(
            Triple(111, 1790000001L, 1), Triple(0, 1790000015L, 2), Triple(1, 1790000015L, 4))))
        assertEquals(listOf(111, 0, 1), records.map { it.slotIndex })
        assertEquals(listOf(1, 2, 4), records.map { it.type })
        assertEquals(records[1].timeUtc, records[2].timeUtc)
        assertEquals(3, records.size)
    }
    @Test fun completeZeroAndUnknownFillersDoNotInventRecords() {
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val empty = file(g)
            assertTrue(CardSpecificConditionParser.parse(empty).isEmpty())
            val bytes = empty.bytes.copyOf()
            bytes.fill(255.toByte(), if (g == CardGeneration.GEN1) 0 else 2)
            assertTrue(CardSpecificConditionParser.parse(CardSpecificConditionFile(g, empty.recordCount, bytes)).isEmpty())
        }
    }
    @Test fun missingTimesAndUnknownCodesRemainRecordedWithoutPairingOrTimeFilling() {
        val records = CardSpecificConditionParser.parse(file(newest = 3, entries = listOf(
            Triple(0, 0, 1), Triple(1, 0xffffffffL, 2), Triple(2, 1790000000L, 254), Triple(3, 1790000001L, 0))))
        assertEquals(4, records.size)
        assertNull(records[0].timeUtc); assertNull(records[1].timeUtc)
        assertEquals(254, records[2].type); assertEquals(0, records[3].type)
        assertEquals("Unbekannte Sonderbedingung (Code FE)", CardSpecificConditionParser.description(CardGeneration.GEN2V2, 254))
    }
    @Test fun unsignedTimesBeyond2038KeepTheirActualInstant() {
        val records = CardSpecificConditionParser.parse(file(entries = listOf(Triple(0, 0xf0000000L, 3))))
        assertEquals(Instant.ofEpochSecond(0xf0000000L), records.single().timeUtc)
    }
    @Test fun truncatedOversizedFilesAndInvalidRingPointerAreRejected() {
        val complete = file()
        for (size in listOf(0, 561, 563)) rejected {
            CardSpecificConditionParser.parse(CardSpecificConditionFile(complete.generation, complete.recordCount, complete.bytes.copyOf(size)))
        }
        for (pointer in listOf(112, 65535)) rejected { CardSpecificConditionParser.parse(file(newest = pointer)) }
    }
    @Test fun ferryLabelsRemainGenerationSpecificAndReservedTypesStayNeutral() {
        assertEquals("Fähre/Zug · Beginn", CardSpecificConditionParser.description(CardGeneration.GEN2, 3))
        assertEquals("Fähre/Zug · Ende", CardSpecificConditionParser.description(CardGeneration.GEN2V2, 4))
        assertEquals("Unbekannte Sonderbedingung (Code 04)", CardSpecificConditionParser.description(CardGeneration.GEN1, 4))
        assertEquals("Out of Scope · Beginn", CardSpecificConditionParser.description(CardGeneration.GEN1, 1))
        assertEquals("Out of Scope · Ende", CardSpecificConditionParser.description(CardGeneration.GEN2V2, 2))
    }

}

