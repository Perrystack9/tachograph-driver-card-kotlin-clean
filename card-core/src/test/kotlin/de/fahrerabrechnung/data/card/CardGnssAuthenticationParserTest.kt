package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CardGnssAuthenticationParserTest {
    private fun record(time: Long = 1790000017L, status: Int = 1): ByteArray =
        ByteBuffer.allocate(5).putInt(time.toInt()).put(status.toByte()).array()

    private fun file(newest: Int = 0, records: Map<Int, ByteArray> = mapOf(0 to record())):
        CardGnssAuthenticationFile {
        val bytes = ByteArray(1682)
        ByteBuffer.wrap(bytes).putShort(newest.toShort())
        for ((slot, raw) in records) raw.copyInto(bytes, 2 + slot * 5)
        return CardGnssAuthenticationFile(CardGeneration.GEN2V2, bytes)
    }

    private fun parsed(raw: ByteArray) = CardGnssAuthenticationParser.parse(
        file(records = mapOf(0 to raw))).single()

    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: ParseError) { return }
        throw AssertionError("ParseError expected")
    }

    @Test fun onlyGeneration2Version2HasThisCompleteAuthenticationFile() {
        assertEquals(1682, CardGnssAuthenticationParser.fileLength(CardGeneration.GEN2V2))
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2,
                        CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED)) {
            rejected { CardGnssAuthenticationParser.fileLength(g) }
            rejected { CardGnssAuthenticationFile(g, ByteArray(1682)) }
        }
    }

    @Test fun truncatedOversizedAndPositionSourceFilesAreRejected() {
        for (length in listOf(0, 1, 2, 1681, 1683, 6050)) rejected {
            CardGnssAuthenticationFile(CardGeneration.GEN2V2, ByteArray(length))
        }
    }

    @Test fun outOfRangeRingPointersAreRejected() {
        for (pointer in listOf(336, 65535)) rejected {
            CardGnssAuthenticationParser.parse(file(newest = pointer))
        }
    }

    @Test fun ringWrapRetainsPhysicalSlotsDuplicateTimesAndSourceOrder() {
        val records = CardGnssAuthenticationParser.parse(file(newest = 1, records = mapOf(
            335 to record(1790000099L, 0), 0 to record(1790000001L, 1),
            1 to record(1790000001L, 2))))
        assertEquals(listOf(335, 0, 1), records.map { it.slotIndex })
        assertEquals(listOf(1790000099L, 1790000001L, 1790000001L),
            records.map { it.accumulatedDrivingTimeRaw })
        assertEquals(listOf(0, 1, 2), records.map { it.authenticationStatusRaw })
    }

    @Test fun newestAtFinalSlotStartsAtTheFirstPhysicalSlot() {
        val records = CardGnssAuthenticationParser.parse(file(newest = 335,
            records = mapOf(335 to record(), 0 to record(), 334 to record())))
        assertEquals(listOf(0, 334, 335), records.map { it.slotIndex })
    }

    @Test fun aCompletelyPopulatedRingRetainsEveryRecord() {
        val records = CardGnssAuthenticationParser.parse(file(newest = 17,
            records = (0..335).associateWith { record(1790000000L + it, it % 2) }))
        assertEquals(336, records.size)
        assertEquals(18, records.first().slotIndex)
        assertEquals(17, records.last().slotIndex)
        assertEquals((0..335).toSet(), records.map { it.slotIndex }.toSet())
    }

    @Test fun unsignedTimePreservesExactSecondsIncludingBeyond2038() {
        for (time in listOf(1790000017L, 0x80000001L, 0xfffffffeL)) {
            val r = parsed(record(time))
            assertEquals(time, r.accumulatedDrivingTimeRaw)
            assertEquals(Instant.ofEpochSecond(time), r.accumulatedDrivingTimeUtc)
        }
    }

    @Test fun unknownTimesRemainRawAndDoNotEraseARecordedStatus() {
        for (time in listOf(0L, 0xffffffffL)) {
            val r = parsed(record(time, 1))
            assertEquals(time, r.accumulatedDrivingTimeRaw)
            assertNull(r.accumulatedDrivingTimeUtc)
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
            assertNotNull(r.accumulatedDrivingTimeUtc)
        }
    }

    @Test fun onlyCompleteZeroOrFfPersonalisationFillersAreOmitted() {
        assertTrue(CardGnssAuthenticationParser.parse(file(records = emptyMap())).isEmpty())
        val ff = file(records = emptyMap()).bytes.also { it.fill(255.toByte(), 2) }
        assertTrue(CardGnssAuthenticationParser.parse(
            CardGnssAuthenticationFile(CardGeneration.GEN2V2, ff)).isEmpty())
        val partial = parsed(record(0xffffffffL, 254))
        assertEquals(254, partial.authenticationStatusRaw)
        assertNull(partial.accumulatedDrivingTimeUtc)
        assertNull(partial.recordedStatus)
    }

    @Test fun inputAndExposedByteArraysCannotMutateTheFrozenFile() {
        val input = file().bytes
        val frozen = CardGnssAuthenticationFile(CardGeneration.GEN2V2, input)
        input.fill(255.toByte())
        frozen.bytes.fill(255.toByte())
        val r = CardGnssAuthenticationParser.parse(frozen).single()
        assertEquals(1790000017L, r.accumulatedDrivingTimeRaw)
        assertEquals(1, r.authenticationStatusRaw)
    }
}

