package de.fahrerabrechnung.data.card

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CardCommunicationTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun file(kind: CardCommunicationKind, raw: ByteArray, generation: CardGeneration = CardGeneration.GEN2V2) =
        CardCommunicationFile(kind, generation, raw)
    private fun atr(vararg historical: Int, inverse: Boolean = false) = AtrDecoder.parse(
        bytes(if (inverse) 0x3F else 0x3B, historical.size) + bytes(*historical))
    private inline fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: ParseError) { } }

    @Test fun originalLengthsAndGenerationsAreEnforcedWithoutMutatingInput() {
        assertEquals(0x2F01, CardCommunicationKind.ATR_INFO.fileId)
        assertEquals(0x0006, CardCommunicationKind.EXTENDED_LENGTH.fileId)
        for (kind in CardCommunicationKind.entries) {
            for (size in listOf(kind.minimumLength - 1, kind.maximumLength + 1)) rejected { file(kind, ByteArray(size)) }
            for (generation in listOf(CardGeneration.GEN1, CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED))
                rejected { file(kind, ByteArray(kind.minimumLength), generation) }
            val raw = ByteArray(kind.minimumLength)
            val captured = file(kind, raw); raw[0] = 1; captured.bytes[0] = 2
            assertArrayEquals(ByteArray(kind.minimumLength), captured.bytes)
        }
    }
    @Test fun everyCompleteDefaultLengthRemainsAStoredEmptySource() {
        for (kind in CardCommunicationKind.entries) for (size in kind.minimumLength..kind.maximumLength)
            for (fill in listOf(0, 255)) assertNull(CardCommunicationParser.parse(file(kind, ByteArray(size) { fill.toByte() })))
    }
    @Test fun allThreeProtocolCodesStayIndependentAndUnknownCodesDoNotEnableAnything() {
        for ((code, expected) in listOf(1 to (false to true), 0x10 to (true to false), 0x11 to (true to true))) {
            val r = CardCommunicationParser.parse(file(CardCommunicationKind.EXTENDED_LENGTH, bytes(2, 1, code))) as CardExtendedLengthRecord
            assertEquals(expected.first, r.supportsT0); assertEquals(expected.second, r.supportsT1)
        }
        for (code in listOf(0, 2, 0x12, 0xFF)) {
            val r = CardCommunicationParser.parse(file(CardCommunicationKind.EXTENDED_LENGTH, bytes(2, 1, code))) as CardExtendedLengthRecord
            assertEquals(code, r.protocolCodeRaw); assertNull(r.supportsT0); assertNull(r.supportsT1)
        }
        rejected { CardCommunicationParser.parse(file(CardCommunicationKind.EXTENDED_LENGTH, bytes(3, 1, 1))) }
        rejected { CardCommunicationParser.parse(file(CardCommunicationKind.EXTENDED_LENGTH, bytes(2, 2, 1))) }
    }
    @Test fun atrInfoPreservesTheOpaqueTemplateAndIndependentDataObjects() {
        val raw = bytes(0x7F, 0x66, 8, 2, 2, 1, 0, 2, 2, 2, 0, 0x5F, 0x52, 1, 0xA5, 0, 0xFF)
        val captured = file(CardCommunicationKind.ATR_INFO, raw)
        val record = CardCommunicationParser.parse(captured) as CardAtrInfoRecord
        assertEquals(8, record.extendedLengthTemplateBytes); assertEquals(1, record.otherObjectCount)
        assertArrayEquals(raw, captured.bytes)
        val longLength = bytes(0x7F, 0x66, 0x81, 4, 2, 2, 1, 0)
        assertEquals(4, (CardCommunicationParser.parse(file(CardCommunicationKind.ATR_INFO, longLength)) as CardAtrInfoRecord).extendedLengthTemplateBytes)
    }
    @Test fun malformedTruncatedDuplicateAndMissingAtrTemplatesAreRejected() {
        for (raw in listOf(bytes(0x7F, 0x66, 5, 2, 2, 1, 0), bytes(0x7F, 0x66, 0x80, 0, 0, 0, 0),
            bytes(0x7F, 0x66, 0, 0, 0, 0, 0), bytes(0x7F, 0x66, 1, 1, 0x7F, 0x66, 1, 1),
            bytes(0x5F, 0x52, 4, 1, 2, 3, 4), bytes(0x7F, 0x80, 1, 1, 0, 0, 0),
            bytes(0x7F, 0x66, 0x83, 0, 0, 1, 1))) rejected { CardCommunicationParser.parse(file(CardCommunicationKind.ATR_INFO, raw)) }
    }
    @Test fun capabilityFlagIsReadFromTheBoundedCompactTlvWithBothStatusLayouts() {
        assertEquals(true, CardCommunicationParser.advertisedBy(atr(0x80, 0x73, 0, 0, 0x40)))
        assertEquals(false, CardCommunicationParser.advertisedBy(atr(0x80, 0x73, 0, 0, 0)))
        assertEquals(true, CardCommunicationParser.advertisedBy(atr(0, 0x73, 0, 0, 0x40, 0, 0x90, 0)))
        assertEquals(true, CardCommunicationParser.advertisedBy(atr(0x80, 0x31, 0xFF, 0x73, 0, 0, 0x40)))
        for (inverse in listOf(false, true)) for (flag in 0..255) {
            assertEquals(flag and 0x40 != 0,
                CardCommunicationParser.advertisedBy(atr(0x80, 0x73, 0, 0, flag, inverse = inverse)))
            assertEquals(flag and 0x40 != 0,
                CardCommunicationParser.advertisedBy(atr(0, 0x73, 0, 0, flag, 0, 0x90, 0, inverse = inverse)))
        }
    }
    @Test fun unknownMalformedAndDuplicateCapabilitiesNeverPretendToBeUnsupported() {
        for (a in listOf(atr(), atr(0x10, 0x73, 0, 0, 0x40), atr(0x80, 0x72, 0, 0),
            atr(0x80, 0x73, 0, 0), atr(0x80, 0x73, 0, 0, 0x40, 0x73, 0, 0, 0),
            atr(0, 0x73, 0, 0, 0x40), atr(0x80, 0x70))) assertNull(CardCommunicationParser.advertisedBy(a))
    }

    private class Transport(val raw: ByteArray, val chunk: Int = 128, val eof: Int = 0x6700,
        val boundary: String? = null, val selected: Int = 0x9000, val selectedData: ByteArray = byteArrayOf()) : CardReaderTransport {
        val commands = mutableListOf<ByteArray>()
        override suspend fun availability() = SourceAvailability.AVAILABLE
        override suspend fun open() = ReaderState.Open
        override suspend fun powerOn() = byteArrayOf(0x3B, 0)
        override suspend fun powerOff() = Unit
        override fun close() = Unit
        private fun reply(data: ByteArray = byteArrayOf(), sw: Int = 0x9000) = data + byteArrayOf((sw ushr 8).toByte(), sw.toByte())
        override suspend fun exchange(apdu: ByteArray): ByteArray {
            commands += apdu.copyOf()
            if (apdu[1] == 0xA4.toByte()) return reply(selectedData, selected)
            check(apdu[1] == 0xB0.toByte())
            val offset = (apdu[2].toInt() and 255) shl 8 or (apdu[3].toInt() and 255)
            val length = apdu[4].toInt() and 255
            if (length == 1 && offset >= raw.size) when (boundary) {
                "6C00" -> return reply(sw = 0x6C00)
                "6C01" -> return reply(sw = 0x6C01)
                "empty-success" -> return reply()
                "data-error" -> return reply(byteArrayOf(1), eof)
                "excess" -> return reply(byteArrayOf(1, 2))
                "security" -> return reply(sw = 0x6982)
                "cancel" -> throw CancellationException("synthetic")
            }
            if (offset >= raw.size) return reply(sw = eof)
            if (offset + length > raw.size) return reply(sw = 0x6700)
            return reply(raw.copyOfRange(offset, offset + minOf(length, chunk)))
        }
    }
    @Test fun allAtrLengthsWithPartialRepliesAndThreeEofVariantsAreReadExactlyAndBoundedly() = runBlocking {
        for (size in 7..128) for (chunk in listOf(1, 3, 7, 128)) for (eof in listOf(0x6700, 0x6B00, 0x6282)) {
            val raw = ByteArray(size) { (it % 251).toByte() }; val t = Transport(raw, chunk, eof)
            val (captured, failed) = CardCommunicationReader.read(ApduSession(t), CardCommunicationKind.ATR_INFO, true)
            assertFalse(failed); assertArrayEquals(raw, captured)
            assertTrue(t.commands.all { it.size <= 7 && it[1].toInt() and 255 in setOf(0xA4, 0xB0) })
            assertTrue(t.commands.count { it[1] == 0xB0.toByte() && it[4] == 1.toByte() &&
                ((it[2].toInt() and 255) shl 8 or (it[3].toInt() and 255)) >= 7 } <= 129)
        }
    }
    @Test fun exactProtocolFileAndEmptyBytesRemainDifferentFromAbsentOptionalFile() = runBlocking {
        for (fill in listOf(0, 255)) {
            val t = Transport(ByteArray(3) { fill.toByte() }, chunk = 1)
            val r = CardCommunicationReader.read(ApduSession(t), CardCommunicationKind.EXTENDED_LENGTH, true)
            assertFalse(r.second); assertArrayEquals(t.raw, r.first)
        }
        for (advertised in listOf(null, false, true)) {
            val t = Transport(ByteArray(0), selected = 0x6A82)
            val r = CardCommunicationReader.read(ApduSession(t), CardCommunicationKind.EXTENDED_LENGTH, advertised)
            assertNull(r.first); assertEquals(advertised == true, r.second); assertEquals(1, t.commands.size)
        }
        for (t in listOf(Transport(ByteArray(0), selected = 0x6982), Transport(ByteArray(0), selected = 0x6A82, selectedData = byteArrayOf(1))))
            assertTrue(CardCommunicationReader.read(ApduSession(t), CardCommunicationKind.ATR_INFO, null).second)
    }
    @Test fun shortOverlongAndUnprovenFileBoundariesAreRejectedWithoutLengthCorrection() = runBlocking {
        for (kind in CardCommunicationKind.entries) {
            for (size in listOf(kind.minimumLength - 1, kind.maximumLength + 1)) {
                try { CardCommunicationReader.read(ApduSession(Transport(ByteArray(size))), kind, true); fail() }
                catch (_: ReaderException) { }
            }
            for (boundary in listOf("6C00", "6C01", "empty-success", "data-error", "excess", "security")) {
                val t = Transport(ByteArray(kind.minimumLength), boundary = boundary)
                try { CardCommunicationReader.read(ApduSession(t), kind, true); fail(boundary) } catch (_: ReaderException) { }
                assertTrue(t.commands.filter { it[1] == 0xB0.toByte() && it[4] == 1.toByte() }.isNotEmpty())
                assertFalse(t.commands.any { it[1] == 0xB0.toByte() && it[4] == 0.toByte() })
            }
        }
    }
    @Test fun cancellationFromBoundaryProbePropagates() = runBlocking {
        try { CardCommunicationReader.read(ApduSession(Transport(ByteArray(3), boundary = "cancel")), CardCommunicationKind.EXTENDED_LENGTH, true); fail() }
        catch (_: CancellationException) { }
    }

}

