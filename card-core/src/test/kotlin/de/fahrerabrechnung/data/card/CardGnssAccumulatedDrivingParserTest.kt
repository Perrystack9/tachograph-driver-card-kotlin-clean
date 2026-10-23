package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CardGnssAccumulatedDrivingParserTest {
    private fun record(driving: Long = 1790000017L, position: Long = 1790000003L,
                       latitude: Int = 51234, longitude: Int = 12345,
                       accuracy: Int = 12, odometer: Int = 135_521): ByteArray {
        val b = ByteBuffer.allocate(18)
        b.putInt(driving.toInt()).putInt(position.toInt()).put(accuracy.toByte())
        for (value in listOf(latitude, longitude, odometer)) {
            b.put((value ushr 16).toByte()).put((value ushr 8).toByte()).put(value.toByte())
        }
        return b.array()
    }

    private fun file(generation: CardGeneration = CardGeneration.GEN2V2, count: Int = 336,
                     newest: Int = 0, records: Map<Int, ByteArray> = mapOf(0 to record())):
        CardGnssAccumulatedDrivingFile {
        val bytes = ByteArray(CardGnssAccumulatedDrivingParser.fileLength(generation, count))
        ByteBuffer.wrap(bytes).putShort(newest.toShort())
        for ((slot, raw) in records) raw.copyInto(bytes, 2 + slot * 18)
        return CardGnssAccumulatedDrivingFile(generation, count, bytes)
    }

    private fun parsed(raw: ByteArray) = CardGnssAccumulatedDrivingParser.parse(
        file(records = mapOf(0 to raw))).single()

    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: ParseError) { return }
        throw AssertionError("ParseError expected")
    }

    @Test fun exactLengthsAndGenerationCapacitiesFollowTheDriverCardFormat() {
        assertEquals(4538, CardGnssAccumulatedDrivingParser.fileLength(CardGeneration.GEN2, 252))
        assertEquals(6050, CardGnssAccumulatedDrivingParser.fileLength(CardGeneration.GEN2, 336))
        assertEquals(6050, CardGnssAccumulatedDrivingParser.fileLength(CardGeneration.GEN2V2, 336))
        assertEquals(1, CardGnssAccumulatedDrivingParser.parse(file(CardGeneration.GEN2, 252)).size)
        assertEquals(1, CardGnssAccumulatedDrivingParser.parse(file(CardGeneration.GEN2, 300)).size)
    }

    @Test fun unsupportedGenerationsAndCapacitiesAreRejectedBeforeCopying() {
        for (g in listOf(CardGeneration.GEN1, CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED))
            rejected { CardGnssAccumulatedDrivingParser.fileLength(g, 336) }
        for (n in listOf(Int.MIN_VALUE, 0, 251, 337, 65535, Int.MAX_VALUE))
            rejected { CardGnssAccumulatedDrivingParser.fileLength(CardGeneration.GEN2, n) }
        rejected { CardGnssAccumulatedDrivingParser.fileLength(CardGeneration.GEN2V2, 252) }
    }

    @Test fun truncatedOversizedAndWrongGenerationFilesAreRejected() {
        for (length in listOf(0, 1, 2, 4537, 4539, 6049, 6051)) rejected {
            CardGnssAccumulatedDrivingFile(CardGeneration.GEN2V2, 336, ByteArray(length))
        }
        rejected { CardGnssAccumulatedDrivingFile(CardGeneration.GEN1, 336, ByteArray(6050)) }
    }

    @Test fun outOfRangeRingPointersAreRejected() {
        for (pointer in listOf(336, 65535)) rejected {
            CardGnssAccumulatedDrivingParser.parse(file(newest = pointer))
        }
        rejected { CardGnssAccumulatedDrivingParser.parse(file(CardGeneration.GEN2, 252, 252)) }
    }

    @Test fun wrappedRingKeepsPhysicalSlotsAndSourceOrderWithoutSortingOrDeduplicating() {
        val records = CardGnssAccumulatedDrivingParser.parse(file(newest = 1, records = mapOf(
            335 to record(driving = 1790000099L), 0 to record(driving = 1790000001L),
            1 to record(driving = 1790000001L))))
        assertEquals(listOf(335, 0, 1), records.map { it.slotIndex })
        assertEquals(listOf(1790000099L, 1790000001L, 1790000001L), records.map { it.accumulatedDrivingTimeRaw })
    }

    @Test fun pointerAtTheFinalSlotKeepsTheFirstSlotFirst() {
        val records = CardGnssAccumulatedDrivingParser.parse(file(newest = 335,
            records = mapOf(335 to record(), 0 to record(), 334 to record())))
        assertEquals(listOf(0, 334, 335), records.map { it.slotIndex })
    }

    @Test fun drivingAndPositionTimesKeepIndependentExactSeconds() {
        val r = parsed(record())
        assertEquals(Instant.ofEpochSecond(1790000017L), r.accumulatedDrivingTimeUtc)
        assertEquals(Instant.ofEpochSecond(1790000003L), r.positionTimeUtc)
        assertEquals(r.positionTimeUtc, r.position!!.timeUtc)
        assertNotEquals(r.accumulatedDrivingTimeUtc, r.position!!.timeUtc)
    }

    @Test fun unsignedTimesBeyond2038AreNotTruncatedOrClamped() {
        val r = parsed(record(driving = 0xf0000000L, position = 0x80000001L))
        assertEquals(Instant.ofEpochSecond(0xf0000000L), r.accumulatedDrivingTimeUtc)
        assertEquals(Instant.ofEpochSecond(0x80000001L), r.position!!.timeUtc)
    }

    @Test fun missingPositionTimeNeverUsesTheDrivingTimestamp() {
        for (time in listOf(0L, 0xffffffffL)) {
            val r = parsed(record(position = time))
            assertNotNull(r.accumulatedDrivingTimeUtc)
            assertEquals(time, r.positionTimeRaw)
            assertNull(r.positionTimeUtc)
            assertNull(r.position)
        }
    }

    @Test fun missingDrivingTimeDoesNotEraseAnIndependentRecordedPosition() {
        for (time in listOf(0L, 0xffffffffL)) {
            val r = parsed(record(driving = time))
            assertNull(r.accumulatedDrivingTimeUtc)
            assertEquals(time, r.accumulatedDrivingTimeRaw)
            assertNotNull(r.position)
        }
    }

    @Test fun signedDegreeMinuteCoordinatesAreConvertedInEveryHemisphere() {
        for (lat in listOf(51234, -51234)) for (lon in listOf(12345, -12345)) {
            val r = parsed(record(latitude = lat, longitude = lon))
            assertEquals(lat, r.latitudeRaw)
            assertEquals(lon, r.longitudeRaw)
            assertEquals(if (lat > 0) 51.39 else -51.39, r.position!!.latitude, 0.000000001)
            assertEquals(if (lon > 0) 12.575 else -12.575, r.position!!.longitude, 0.000000001)
        }
    }

    @Test fun zeroCoordinatesAndOneTenthMinuteSouthWestRemainRealCoordinates() {
        val zero = parsed(record(latitude = 0, longitude = 0))
        assertEquals(0.0, zero.position!!.latitude, 0.0)
        assertEquals(0.0, zero.position!!.longitude, 0.0)
        val near = parsed(record(latitude = -1, longitude = -1))
        assertEquals(-1.0 / 600, near.position!!.latitude, 0.000000001)
        assertEquals(-1.0 / 600, near.position!!.longitude, 0.000000001)
    }

    @Test fun polesAndAntimeridianAreValidButMinutesBeyondThemAreNot() {
        for (lat in listOf(-90000, 90000)) for (lon in listOf(-180000, 180000)) {
            val p = parsed(record(latitude = lat, longitude = lon)).position!!
            assertEquals(if (lat < 0) -90.0 else 90.0, p.latitude, 0.0)
            assertEquals(if (lon < 0) -180.0 else 180.0, p.longitude, 0.0)
        }
        assertNull(parsed(record(latitude = 90001)).position)
        assertNull(parsed(record(longitude = 180001)).position)
    }

    @Test fun invalidMinutesBoundsAndSignedExtremesKeepRawRecordsWithoutAFalsePosition() {
        for (latitude in listOf(51600, 51999, -51600, 90001, -90001, 8388607, -8388608)) {
            val r = parsed(record(latitude = latitude))
            assertEquals(latitude, r.latitudeRaw)
            assertNotNull(r.accumulatedDrivingTimeUtc)
            assertNull(r.position)
        }
        for (longitude in listOf(12600, -12600, 180001, -180001, 8388607, -8388608)) {
            val r = parsed(record(longitude = longitude))
            assertEquals(longitude, r.longitudeRaw)
            assertNull(r.position)
        }
    }

    @Test fun accuracyIsHdopRatherThanMetresAndUnknownValuesRemainRaw() {
        assertEquals(1.2, parsed(record(accuracy = 12)).accuracyHdop!!, 0.0)
        assertEquals(0.1, parsed(record(accuracy = 1)).accuracyHdop!!, 0.0)
        assertEquals(10.0, parsed(record(accuracy = 100)).accuracyHdop!!, 0.0)
        for (value in listOf(0, 101, 254, 255)) {
            val r = parsed(record(accuracy = value))
            assertEquals(value, r.accuracyRaw)
            assertNull(r.accuracyHdop)
        }
    }

    @Test fun odometerRemainsAnUnsignedSourceValueWithoutDistanceCalculation() {
        for (value in listOf(0, 135521, 9_999_999)) {
            val r = parsed(record(odometer = value))
            assertEquals(value, r.odometerRaw)
            assertEquals(value, r.odometerKm)
        }
        for (value in listOf(10_000_000, 0xffffff)) {
            val r = parsed(record(odometer = value))
            assertEquals(value, r.odometerRaw)
            assertNull(r.odometerKm)
        }
    }

    @Test fun onlyCompleteZeroOrUnknownFillersAreOmitted() {
        for (g in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            assertTrue(CardGnssAccumulatedDrivingParser.parse(file(g, records = emptyMap())).isEmpty())
            val bytes = file(g, records = emptyMap()).bytes.also { it.fill(255.toByte(), 2) }
            assertTrue(CardGnssAccumulatedDrivingParser.parse(CardGnssAccumulatedDrivingFile(g, 336, bytes)).isEmpty())
        }
        val partial = ByteArray(18).also { it[17] = 1 }
        val r = parsed(partial)
        assertEquals(1, r.odometerRaw)
        assertNull(r.position)
        assertNull(r.accumulatedDrivingTimeUtc)
    }

    @Test fun inputAndExposedByteArraysCannotMutateTheFrozenFile() {
        val input = file().bytes
        val frozen = CardGnssAccumulatedDrivingFile(CardGeneration.GEN2V2, 336, input)
        input.fill(255.toByte())
        frozen.bytes.fill(255.toByte())
        val r = CardGnssAccumulatedDrivingParser.parse(frozen).single()
        assertEquals(1790000017L, r.accumulatedDrivingTimeRaw)
        assertEquals(51234, r.latitudeRaw)
    }
}

