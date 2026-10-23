package de.fahrerabrechnung.data.card

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

class CardVehiclesParserTest {
    private fun record(first: Int, last: Int, plate: String, vin: String? = null): ByteArray =
        ByteArrayOutputStream().apply {
            fun u24(n: Int) { write(n ushr 16); write(n ushr 8); write(n) }
            fun u32(n: Int) { write(n ushr 24); write(n ushr 16); write(n ushr 8); write(n) }
            u24(123_000); u24(123_250); u32(first); u32(last)
            write(49); write(1)
            write(plate.toByteArray().copyOf(13).also { for (i in plate.length until 13) it[i] = 32 })
            write(0); write(7)
            if (vin != null) write(vin.toByteArray().copyOf(17))
        }.toByteArray()

    @Test fun gen2RingIsRotatedAndRetainsVehicleSourceFacts() {
        val first = record(1_700_000_000, 1_700_001_000, "TEST-001", "SYNTHETICVIN00001")
        val second = record(1_700_086_400, 0, "XY 456", "SYNTHETICVIN00001")
        val file = CardVehiclesFile(1, 3, CardGeneration.GEN2V2, first + second + ByteArray(48))
        val uses = CardVehiclesParser.parse(file)
        assertEquals(listOf(0, 1), uses.map { it.ringIndex })
        assertEquals("TEST-001", uses[0].registrationNumber)
        assertEquals(49, uses[0].registrationNation)
        assertEquals("SYNTHETICVIN00001", uses[0].vin)
        assertEquals(250, uses[0].odometerEndKm!! - uses[0].odometerBeginKm)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), uses[0].firstUseUtc)
        assertNull(uses[1].lastUseUtc)
        assertNull(uses[1].odometerEndKm)
    }

    @Test fun gen1AndMalformedRecords() {
        val data = record(1_700_000_000, 1_700_001_000, "TEST 1")
        assertEquals(31, data.size)
        val raw = byteArrayOf(0, 0) + data
        val file = DriverCardDecoder.vehicles(raw, 1, CardGeneration.GEN1)
        assertNull(CardVehiclesParser.parse(file).single().vin)
        try { DriverCardDecoder.vehicles(raw.copyOf(32), 1, CardGeneration.GEN1); fail() }
        catch (e: ParseError) { assertEquals("Vehicles_Used", e.field) }
        try { DriverCardDecoder.vehicles(byteArrayOf(0, 1) + data, 1, CardGeneration.GEN1); fail() }
        catch (e: ParseError) { assertEquals("Vehicles_Used", e.field) }
        val invalidTime = data.copyOf().also { it[9] = 0; it[10] = 0; it[11] = 0; it[12] = 1 }
        try { CardVehiclesParser.parse(CardVehiclesFile(0, 1, CardGeneration.GEN1, invalidTime)); fail() }
        catch (e: ParseError) { assertEquals("CardVehicleRecord", e.field) }
    }

    @Test fun partiallyInitialisedUnusedSlotDoesNotDiscardValidVehicle() {
        val valid = record(1_700_000_000, 1_700_001_000, "TEST-001", "SYNTHETICVIN00001")
        val unused = ByteArray(48).also {
            it[15] = 1 // registration text's code page can be preinitialised
            it[16] = ' '.code.toByte()
        }
        val file = CardVehiclesFile(0, 2, CardGeneration.GEN2V2, valid + unused)
        assertEquals(listOf(0), CardVehiclesParser.parse(file).map { it.ringIndex })
    }
}

