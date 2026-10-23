package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

/** Synthetic EFs only. Exercises both file structures rather than real card data. */
class CardEventFaultParserTest {
    private val generations = listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)
    private fun count(kind: CardEventFaultKind) = if (kind == CardEventFaultKind.EVENTS) 12 else 24
    private fun record(type: Int = 3, begin: Long = 1790000200L, end: Long = 1790000100L,
                       page: Int = 1, plate: ByteArray = "TEST PLATE".padEnd(13).toByteArray()) =
        ByteBuffer.allocate(24).put(type.toByte()).putInt(begin.toInt()).putInt(end.toInt())
            .put(13.toByte()).put(page.toByte()).put(plate).array()
    private fun file(kind: CardEventFaultKind, generation: CardGeneration = CardGeneration.GEN2V2,
                     index: Int = 0, row: ByteArray = record(), perType: Int = count(kind)): CardEventFaultFile {
        val raw = ByteArray(CardEventFaultParser.fileLength(kind, generation, perType))
        row.copyInto(raw, index * 24)
        return CardEventFaultFile(kind, generation, perType, raw)
    }
    private fun rejected(action: () -> Unit): ParseError {
        try { action() } catch (e: ParseError) { return e }
        throw AssertionError("Expected ParseError")
    }

    @Test fun fileCopiesInput() {
        val first = file(CardEventFaultKind.EVENTS)
        val raw = first.bytes.copyOf(); val frozen = CardEventFaultFile(first.kind, first.generation, first.recordsPerType, raw)
        raw.fill(0); assertEquals(1, CardEventFaultParser.parse(frozen).size)
    }

    @Test fun allGenerationsDecodeBothFileTypesWithoutChangingInvertedTimes() {
        for (generation in generations) for (kind in CardEventFaultKind.entries) {
            val file = file(kind, generation, count(kind) + 2)
            val row = CardEventFaultParser.parse(file).single()
            assertEquals(1, row.groupIndex); assertEquals(2, row.slotIndex)
            assertEquals(3, row.type); assertEquals(1790000200L, row.beginUtc!!.epochSecond)
            assertEquals(1790000100L, row.endUtc!!.epochSecond)
            assertEquals(13, row.registrationNation); assertEquals("TEST PLATE", row.registrationNumber)
        }
    }

    @Test fun lengthsUseTheApplicationCountAndSixOrElevenEventGroupsAndTwoFaultGroups() {
        assertEquals(864, CardEventFaultParser.fileLength(CardEventFaultKind.EVENTS, CardGeneration.GEN1, 6))
        assertEquals(1728, CardEventFaultParser.fileLength(CardEventFaultKind.EVENTS, CardGeneration.GEN1, 12))
        assertEquals(1584, CardEventFaultParser.fileLength(CardEventFaultKind.EVENTS, CardGeneration.GEN2, 6))
        assertEquals(3168, CardEventFaultParser.fileLength(CardEventFaultKind.EVENTS, CardGeneration.GEN2V2, 12))
        assertEquals(576, CardEventFaultParser.fileLength(CardEventFaultKind.FAULTS, CardGeneration.GEN1, 12))
        assertEquals(1152, CardEventFaultParser.fileLength(CardEventFaultKind.FAULTS, CardGeneration.GEN2V2, 24))
    }

    @Test fun everyTruncationAndTrailingBytesAreRejectedBeforeDecoding() {
        for (generation in generations) for (kind in CardEventFaultKind.entries) {
            val good = file(kind, generation)
            for (length in (0 until good.bytes.size) + (good.bytes.size + 1)) {
                val error = rejected { CardEventFaultParser.parse(CardEventFaultFile(kind, generation, good.recordsPerType, ByteArray(length))) }
                assertEquals(kind.field, error.field); assertEquals(0, error.offset)
            }
        }
    }

    @Test fun invalidCountsAndUnknownGenerationsFailBeforeAllocation() {
        for (generation in generations) for (kind in CardEventFaultKind.entries) {
            val range = if (kind == CardEventFaultKind.EVENTS) 6..12 else 12..24
            for (invalid in listOf(-1, 0, range.first - 1, range.last + 1, 255, Int.MAX_VALUE))
                rejected { CardEventFaultParser.fileLength(kind, generation, invalid) }
            if (generation == CardGeneration.GEN2V2) rejected { CardEventFaultParser.fileLength(kind, generation, range.first) }
        }
        for (generation in listOf(CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED)) for (kind in CardEventFaultKind.entries)
            rejected { CardEventFaultParser.fileLength(kind, generation, count(kind)) }
    }

    @Test fun zeroSpaceAndUnknownFillerDoNotInventRecords() {
        for (generation in generations) for (kind in CardEventFaultKind.entries) {
            val length = CardEventFaultParser.fileLength(kind, generation, count(kind))
            for (filler in listOf(ByteArray(length), ByteArray(length) { 255.toByte() }, ByteArray(length).also {
                for (slot in 0 until length / 24) java.util.Arrays.fill(it, slot * 24 + 11, slot * 24 + 24, 32.toByte())
            })) assertTrue(CardEventFaultParser.parse(CardEventFaultFile(kind, generation, count(kind), filler)).isEmpty())
        }
    }

    @Test fun independentUnknownTimesStayUnknownWhileUnsignedActualTimesRemainExact() {
        val first = CardEventFaultParser.parse(file(CardEventFaultKind.EVENTS, row = record(begin = 0, end = 0xfffffffeL))).single()
        assertNull(first.beginUtc); assertEquals(0xfffffffeL, first.endUtc!!.epochSecond)
        val second = CardEventFaultParser.parse(file(CardEventFaultKind.FAULTS, row = record(type = 0x40, begin = 1790000000, end = 0xffffffffL))).single()
        assertNull(second.endUtc); assertEquals(1790000000L, second.beginUtc!!.epochSecond)
        val undated = CardEventFaultParser.parse(file(CardEventFaultKind.EVENTS, row = record(type = 0xF1, begin = 0, end = 0))).single()
        assertEquals(0xF1, undated.type); assertNull(undated.beginUtc); assertNull(undated.endUtc)
    }

    @Test fun sourceGroupAndSlotOrderAreRetainedWithoutGuessingAChronologyOrType() {
        val raw = file(CardEventFaultKind.EVENTS, index = 13, row = record(type = 0xF1)).bytes.copyOf()
        record(type = 6, begin = 1790000300, end = 1790000400).copyInto(raw, 0)
        record(type = 0x19, begin = 1790000000, end = 1790000100).copyInto(raw, raw.size - 24)
        val rows = CardEventFaultParser.parse(CardEventFaultFile(CardEventFaultKind.EVENTS, CardGeneration.GEN2V2, 12, raw))
        assertEquals(listOf(6, 0xF1, 0x19), rows.map { it.type })
        assertEquals(listOf(0 to 0, 1 to 1, 10 to 11), rows.map { it.groupIndex to it.slotIndex })
    }

    @Test fun plateCodePagesAreAppliedAndAnUnsupportedNonblankPlateFailsWithItsSourceOffset() {
        for (kind in CardEventFaultKind.entries) {
            val row = record(page = 15, plate = "TEST Ä".padEnd(13).toByteArray(Charset.forName("ISO-8859-15")))
            assertEquals("TEST Ä", CardEventFaultParser.parse(file(kind, row = row)).single().registrationNumber)
            val error = rejected { CardEventFaultParser.parse(file(kind, index = 7, row = record(page = 127))) }
            assertEquals(7 * 24 + 10, error.offset)
            val blank = record(page = 255, plate = ByteArray(13) { 255.toByte() }).also { it[9] = 255.toByte() }
            val unknown = CardEventFaultParser.parse(file(kind, row = blank)).single()
            assertNull(unknown.registrationNumber); assertNull(unknown.registrationNation)
        }
    }



    @Test fun metadataCountsAreReadAtOffsetsThreeAndFourWithoutUsingActivityOrV2ExtensionFields() {
        for (generation in generations) {
            val app = ByteArray(if (generation == CardGeneration.GEN1) 10 else 15).also { it[3] = 12; it[4] = 24; it[5] = 0x55 }
            assertEquals(12, DriverCardDecoder.eventFaultCount(app, generation, CardEventFaultKind.EVENTS))
            assertEquals(24, DriverCardDecoder.eventFaultCount(app, generation, CardEventFaultKind.FAULTS))
            rejected { DriverCardDecoder.eventFaultCount(app.copyOf(app.size - 1), generation, CardEventFaultKind.EVENTS) }
            app[3] = 255.toByte(); rejected { DriverCardDecoder.eventFaultCount(app, generation, CardEventFaultKind.EVENTS) }
            assertEquals(24, DriverCardDecoder.eventFaultCount(app, generation, CardEventFaultKind.FAULTS))
        }
    }
}

