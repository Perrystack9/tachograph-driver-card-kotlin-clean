package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Synthetic complete EF 0508; no real card data or APDU dumps. */
class CardControlParserTest {
    private val generations = listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)
    private val time = 1790000000L
    private fun bytes(type: Int = 0xF8, seconds: Long = time, codePage: Int = 1,
                      plate: ByteArray = "TEST PLATE".padEnd(13).toByteArray(),
                      begin: Long = time - 200, end: Long = time - 100) = ByteBuffer.allocate(46)
        .put(type.toByte()).putInt(seconds.toInt()).put(3.toByte()).put(13.toByte())
        .put("TEST CONTROL".padEnd(16).toByteArray()).put(13.toByte()).put(codePage.toByte())
        .put(plate).putInt(begin.toInt()).putInt(end.toInt()).array()
    private fun rejected(action: () -> Unit): ParseError {
        try { action() } catch (e: ParseError) { return e }
        throw AssertionError("Expected ParseError")
    }

    @Test fun inputBuffersAreCopied() {
        val input = bytes(); val file = CardControlFile(CardGeneration.GEN2V2, input)
        input.fill(0); assertNotNull(CardControlParser.parse(file))
    }

    @Test fun allGenerationsDecodeTheExact46ByteRecordAndIndependentFields() {
        for (generation in generations) {
            val record = CardControlParser.parse(CardControlFile(generation, bytes()))!!
            assertEquals(0xF8, record.controlType)
            assertEquals(Instant.ofEpochSecond(time), record.timeUtc)
            assertEquals(3, record.controlCardType); assertEquals(13, record.controlCardIssuingState)
            assertEquals("TEST CONTROL", record.controlCardNumber)
            assertEquals(13, record.vehicleRegistrationNation); assertEquals("TEST PLATE", record.vehicleRegistrationNumber)
            assertEquals(Instant.ofEpochSecond(time - 200), record.downloadBeginUtc)
            assertEquals(Instant.ofEpochSecond(time - 100), record.downloadEndUtc)
        }
    }

    @Test fun everyTruncationAndTrailingBytesAreRejected() {
        for (generation in generations) for (length in (0..45) + 47) {
            val error = rejected { CardControlParser.parse(CardControlFile(generation, ByteArray(length))) }
            assertEquals("Control_Activity_Data", error.field)
        }
    }

    @Test fun unsupportedGenerationsCannotBeParsedOrStoredAsControls() {
        for (generation in listOf(CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED))
            rejected { CardControlParser.parse(CardControlFile(generation, bytes())) }
    }

    @Test fun unusedTimeRemainsNoControlDespiteIdentificationFiller() {
        for (generation in generations) {
            assertNull(CardControlParser.parse(CardControlFile(generation, ByteArray(46))))
            assertNull(CardControlParser.parse(CardControlFile(generation, bytes(seconds = 0, codePage = 255))))
        }
    }

    @Test fun optionalZeroFieldsStayUnknownAndContradictoryPeriodIsNotRepaired() {
        val raw = bytes(begin = 0, end = time).also {
            java.util.Arrays.fill(it, 7, 23, 0.toByte()); java.util.Arrays.fill(it, 25, 38, 32.toByte())
        }
        val record = CardControlParser.parse(CardControlFile(CardGeneration.GEN2V2, raw))!!
        assertNull(record.controlCardNumber); assertNull(record.vehicleRegistrationNumber)
        assertNull(record.downloadBeginUtc); assertEquals(Instant.ofEpochSecond(time), record.downloadEndUtc)
        val contradictory = CardControlParser.parse(CardControlFile(CardGeneration.GEN1, bytes(begin = time, end = time - 100)))!!
        assertEquals(Instant.ofEpochSecond(time), contradictory.downloadBeginUtc)
        assertEquals(Instant.ofEpochSecond(time - 100), contradictory.downloadEndUtc)
    }

    @Test fun unsignedTimesAndAllTypeBitsRemainExact() {
        val record = CardControlParser.parse(CardControlFile(CardGeneration.GEN2V2,
            bytes(type = 255, seconds = 0xffffffffL, begin = 0, end = 0)))!!
        assertEquals(255, record.controlType); assertEquals(0xffffffffL, record.timeUtc.epochSecond)
        assertNull(record.downloadBeginUtc); assertNull(record.downloadEndUtc)
    }

    @Test fun plateUsesItsCodePageAndUnsupportedPagesFailSafely() {
        val text = "TEST Ä".padEnd(13)
        assertEquals("TEST Ä", CardControlParser.parse(CardControlFile(CardGeneration.GEN1,
            bytes(codePage = 15, plate = text.toByteArray(Charset.forName("ISO-8859-15")))))!!.vehicleRegistrationNumber)
        val error = rejected { CardControlParser.parse(CardControlFile(CardGeneration.GEN2, bytes(codePage = 255))) }
        assertEquals(24, error.offset)
    }


}

