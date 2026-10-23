package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CardVehicleUnitsParserTest {
    private fun record(time: Long = 1790000000L, manufacturer: Int = 253, device: Int = 254,
                       software: ByteArray = "1234".toByteArray()): ByteArray =
        ByteBuffer.allocate(10).putInt(time.toInt()).put(manufacturer.toByte()).put(device.toByte()).put(software).array()
    private fun bytes(newest: Int = 0, rows: Map<Int, ByteArray> = mapOf(0 to record()),
                      count: Int = 200, fill: Byte = 0): ByteArray = ByteArray(2 + count * 10) { fill }.also { file ->
        ByteBuffer.wrap(file).putShort(newest.toShort())
        rows.forEach { (slot, row) -> row.copyInto(file, 2 + slot * 10) }
    }
    private fun parse(newest: Int = 0, rows: Map<Int, ByteArray> = mapOf(0 to record()), count: Int = 200,
                      generation: CardGeneration = CardGeneration.GEN2V2) =
        CardVehicleUnitsParser.parse(CardVehicleUnitsFile(generation, count, bytes(newest, rows, count)))

    @Test fun independentGenerationCapacitiesAndCompleteLengthAreRequired() {
        assertEquals(842, CardVehicleUnitsParser.fileLength(CardGeneration.GEN2, 84))
        assertEquals(2002, CardVehicleUnitsParser.fileLength(CardGeneration.GEN2, 200))
        assertEquals(2002, CardVehicleUnitsParser.fileLength(CardGeneration.GEN2V2, 200))
        for (g in CardGeneration.entries.filter { it !in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2) })
            assertThrows(ParseError::class.java) { CardVehicleUnitsParser.fileLength(g, 200) }
        for (count in listOf(-1, 0, 83, 201, 65535))
            assertThrows(ParseError::class.java) { CardVehicleUnitsParser.fileLength(CardGeneration.GEN2, count) }
        for (count in listOf(84, 199, 201))
            assertThrows(ParseError::class.java) { CardVehicleUnitsParser.fileLength(CardGeneration.GEN2V2, count) }
        for (length in listOf(0, 2, 2001, 2003))
            assertThrows(ParseError::class.java) { CardVehicleUnitsFile(CardGeneration.GEN2V2, 200, ByteArray(length)) }
    }
    @Test fun physicalRingOrderRetainsDuplicateRecordsAndUnorderedTimes() {
        val same = record(time = 1790000100L)
        val rows = parse(newest = 1, rows = mapOf(0 to record(time = 1790000050L), 1 to same, 2 to same,
            199 to record(time = 1790000000L)))
        assertEquals(listOf(2, 199, 0, 1), rows.map { it.slotIndex })
        assertEquals(listOf(1790000100L, 1790000000L, 1790000050L, 1790000100L), rows.map { it.useBeginRaw })
    }
    @Test fun everyPhysicalSlotInBothGenerationCapacitiesIsDecoded() {
        for ((g, count) in listOf(CardGeneration.GEN2 to 84, CardGeneration.GEN2 to 137, CardGeneration.GEN2V2 to 200)) {
            val rows = parse(count-1, (0 until count).associateWith { record(device = it) }, count, g)
            assertEquals(count, rows.size); assertEquals((0 until count).toList(), rows.map { it.slotIndex })
            assertEquals(count-1, rows.last().deviceIdRaw)
        }
    }
    @Test fun pointerOutsideTheRingIsRejected() {
        for (pointer in listOf(200, 201, 0x8000, 0xffff))
            assertThrows(ParseError::class.java) { parse(newest = pointer) }
        assertThrows(ParseError::class.java) { parse(newest = 84, count = 84, generation = CardGeneration.GEN2) }
    }
    @Test fun onlyWhollyZeroOrFfSlotsAreEmpty() {
        for (fill in listOf(0.toByte(), 255.toByte())) {
            val file = CardVehicleUnitsFile(CardGeneration.GEN2V2, 200, bytes(rows = emptyMap(), fill = fill))
            assertTrue(CardVehicleUnitsParser.parse(file).isEmpty())
        }
        val partial = ByteArray(10) { 255.toByte() }.also { it[0] = 0 }
        assertEquals(255, parse(rows = mapOf(0 to partial)).single().manufacturerCodeRaw)
    }
    @Test fun everyManufacturerAndDeviceCodeIsRetainedWithoutTruckClassification() {
        for (code in 0..255) {
            val row = parse(rows = mapOf(0 to record(manufacturer = code, device = code))).single()
            assertEquals(code, row.manufacturerCodeRaw); assertEquals(code, row.deviceIdRaw)
        }
    }
    @Test fun exactPrintableIa5VersionIsDisplayedWithoutNumericConversion() {
        for (text in listOf("0123", "A.01", " 1.2", "1.2 ")) {
            val row = parse(rows = mapOf(0 to record(software = text.toByteArray()))).single()
            assertEquals(text, row.softwareVersionText)
            assertEquals(text.map { it.code }, row.softwareVersionRaw)
        }
    }
    @Test fun unknownAndNonPrintableSoftwareBytesRemainExactRawValues() {
        for (software in listOf(byteArrayOf(0, 1, 127, 128.toByte()), ByteArray(4), ByteArray(4) { 255.toByte() },
            "    ".toByteArray())) {
            val row = parse(rows = mapOf(0 to record(software = software))).single()
            assertNull(row.softwareVersionText); assertEquals(software.map { it.toInt() and 255 }, row.softwareVersionRaw)
        }
        assertEquals("00 01 7F 80", parse(rows = mapOf(0 to record(software = byteArrayOf(0, 1, 127, 128.toByte())))).single().softwareVersionHex)
    }
    @Test fun unsignedPost2038BeginningIsAnExactUtcInstant() {
        for (time in listOf(0x80000001L, 0xfffffffeL)) {
            val row = parse(rows = mapOf(0 to record(time))).single()
            assertEquals(time, row.useBeginRaw); assertEquals(time, row.useBeginUtc!!.epochSecond)
        }
    }
    @Test fun unknownBeginningKeepsDeviceAndSoftwareWithoutInventingAnInstant() {
        for (time in listOf(0L, 0xffffffffL)) {
            val row = parse(rows = mapOf(0 to record(time))).single()
            assertNull(row.useBeginUtc); assertEquals(time, row.useBeginRaw); assertEquals("1234", row.softwareVersionText)
        }
    }
    @Test fun completeFileDefensivelyCopiesBothInputAndReturnedBytes() {
        val input = bytes(); val file = CardVehicleUnitsFile(CardGeneration.GEN2V2, 200, input)
        input[6] = 0; file.bytes[8] = 0
        val row = CardVehicleUnitsParser.parse(file).single()
        assertEquals(253, row.manufacturerCodeRaw); assertEquals("1234", row.softwareVersionText)
    }
    @Test fun capacityUsesOnlyItsOwnFullApplicationField() {
        val app = ByteArray(17).also { ByteBuffer.wrap(it, 15, 2).putShort(137) }
        assertEquals(137, DriverCardDecoder.vehicleUnitCount(app, CardGeneration.GEN2))
        app[16] = 200.toByte()
        assertEquals(200, DriverCardDecoder.vehicleUnitCount(app, CardGeneration.GEN2V2))
    }
    @Test fun missingOrUnsupportedCapacityIsNotGuessedFromOtherSources() {
        for (size in listOf(0, 10, 15, 16, 18))
            assertThrows(ParseError::class.java) { DriverCardDecoder.vehicleUnitCount(ByteArray(size), CardGeneration.GEN2) }
        val app = ByteArray(17).also { it[8] = 200.toByte(); it[12] = 200.toByte(); it[14] = 200.toByte() }
        assertThrows(ParseError::class.java) { DriverCardDecoder.vehicleUnitCount(app, CardGeneration.GEN2) }
        for (g in listOf(CardGeneration.GEN1, CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED))
            assertThrows(ParseError::class.java) { DriverCardDecoder.vehicleUnitCount(app, g) }
    }
}

