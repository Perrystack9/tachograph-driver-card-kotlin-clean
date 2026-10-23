package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CardPlacesAuthenticationParserTest {
    private fun record(time: Long = 1790000017L, status: Int = 1): ByteArray =
        ByteBuffer.allocate(5).putInt(time.toInt()).put(status.toByte()).array()

    private fun file(newest: Int = 0, records: Map<Int, ByteArray> = mapOf(0 to record())):
        CardPlacesAuthenticationFile {
        val bytes = ByteArray(562)
        ByteBuffer.wrap(bytes).putShort(newest.toShort())
        for ((slot, raw) in records) raw.copyInto(bytes, 2 + slot * 5)
        return CardPlacesAuthenticationFile(CardGeneration.GEN2V2, bytes)
    }

    private fun parsed(raw: ByteArray) = CardPlacesAuthenticationParser.parse(
        file(records = mapOf(0 to raw))).single()

    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: ParseError) { return }
        throw AssertionError("ParseError expected")
    }

    @Test fun onlyGeneration2Version2HasThisCompleteAuthenticationFile() {
        assertEquals(562, CardPlacesAuthenticationParser.fileLength(CardGeneration.GEN2V2))
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2,
                        CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED)) {
            rejected { CardPlacesAuthenticationParser.fileLength(g) }
            rejected { CardPlacesAuthenticationFile(g, ByteArray(562)) }
        }
    }

    @Test fun truncatedOversizedAndPositionSourceFilesAreRejected() {
        for (length in listOf(0, 1, 2, 561, 563, 2354)) rejected {
            CardPlacesAuthenticationFile(CardGeneration.GEN2V2, ByteArray(length))
        }
    }

    @Test fun outOfRangeRingPointersAreRejected() {
        for (pointer in listOf(112, 65535)) rejected {
            CardPlacesAuthenticationParser.parse(file(newest = pointer))
        }
    }

    @Test fun ringWrapRetainsPhysicalSlotsDuplicateTimesAndSourceOrder() {
        val records = CardPlacesAuthenticationParser.parse(file(newest = 1, records = mapOf(
            111 to record(1790000099L, 0), 0 to record(1790000001L, 1),
            1 to record(1790000001L, 2))))
        assertEquals(listOf(111, 0, 1), records.map { it.slotIndex })
        assertEquals(listOf(1790000099L, 1790000001L, 1790000001L),
            records.map { it.entryTimeRaw })
        assertEquals(listOf(0, 1, 2), records.map { it.authenticationStatusRaw })
    }

    @Test fun newestAtFinalSlotStartsAtTheFirstPhysicalSlot() {
        val records = CardPlacesAuthenticationParser.parse(file(newest = 111,
            records = mapOf(111 to record(), 0 to record(), 110 to record())))
        assertEquals(listOf(0, 110, 111), records.map { it.slotIndex })
    }

    @Test fun aCompletelyPopulatedRingRetainsEveryRecord() {
        val records = CardPlacesAuthenticationParser.parse(file(newest = 17,
            records = (0..111).associateWith { record(1790000000L + it, it % 2) }))
        assertEquals(112, records.size)
        assertEquals(18, records.first().slotIndex)
        assertEquals(17, records.last().slotIndex)
        assertEquals((0..111).toSet(), records.map { it.slotIndex }.toSet())
    }

    @Test fun unsignedTimePreservesExactSecondsIncludingBeyond2038() {
        for (time in listOf(1790000017L, 0x80000001L, 0xfffffffeL)) {
            val r = parsed(record(time))
            assertEquals(time, r.entryTimeRaw)
            assertEquals(Instant.ofEpochSecond(time), r.entryTimeUtc)
        }
    }

    @Test fun unknownTimesRemainRawAndDoNotEraseARecordedStatus() {
        for (time in listOf(0L, 0xffffffffL)) {
            val r = parsed(record(time, 1))
            assertEquals(time, r.entryTimeRaw)
            assertNull(r.entryTimeUtc)
            assertEquals(CardRecordedGnssAuthenticationStatus.AUTHENTICATED, r.recordedStatus)
        }
    }

    @Test fun knownStatusCodesDescribeOnlyTheRecordedSourceValue() {
        assertEquals(CardRecordedGnssAuthenticationStatus.NOT_AUTHENTICATED,
            parsed(record(status = 0)).recordedStatus)
        assertEquals(CardRecordedGnssAuthenticationStatus.AUTHENTICATED,
            parsed(record(status = 1)).recordedStatus)
    }

    @Test fun everyReservedStatusIsRetainedWithoutInferringAuthentication() {
        for (status in 2..255) {
            val r = parsed(record(status = status))
            assertEquals(status, r.authenticationStatusRaw)
            assertNull(r.recordedStatus)
            assertNotNull(r.entryTimeUtc)
        }
    }

    @Test fun onlyCompleteZeroOrFfPersonalisationFillersAreOmitted() {
        assertTrue(CardPlacesAuthenticationParser.parse(file(records = emptyMap())).isEmpty())
        val ff = file(records = emptyMap()).bytes.also { it.fill(255.toByte(), 2) }
        assertTrue(CardPlacesAuthenticationParser.parse(
            CardPlacesAuthenticationFile(CardGeneration.GEN2V2, ff)).isEmpty())
        val partial = parsed(record(0xffffffffL, 254))
        assertEquals(254, partial.authenticationStatusRaw)
        assertNull(partial.entryTimeUtc)
        assertNull(partial.recordedStatus)
    }

    @Test fun inputAndExposedByteArraysCannotMutateTheFrozenFile() {
        val input = file().bytes
        val frozen = CardPlacesAuthenticationFile(CardGeneration.GEN2V2, input)
        input.fill(255.toByte())
        frozen.bytes.fill(255.toByte())
        val r = CardPlacesAuthenticationParser.parse(frozen).single()
        assertEquals(1790000017L, r.entryTimeRaw)
        assertEquals(1, r.authenticationStatusRaw)
    }
}

