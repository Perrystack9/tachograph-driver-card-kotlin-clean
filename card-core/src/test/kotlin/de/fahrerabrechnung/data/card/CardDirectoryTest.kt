package de.fahrerabrechnung.data.card

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal object DirectoryFixtures {
    fun bytes() = byteArrayOf(0x61, 8, 0x4F, 6, 0xFF.toByte(), 0x54, 0x41, 0x43, 0x48, 0x4F,
        0x61, 8, 0x4F, 6, 0xFF.toByte(), 0x53, 0x4D, 0x52, 0x44, 0x54)
}

class CardDirectoryParserTest {
    @Test fun prescribedTwoApplicationTemplatesDecodeWithoutInferringAV2Application() {
        for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val source = CardDirectoryFile(generation, DirectoryFixtures.bytes())
            assertEquals(listOf(CardDirectoryApplication.TACHOGRAPH_GEN1, CardDirectoryApplication.TACHOGRAPH_GEN2),
                CardDirectoryParser.parse(source)!!.applications)
            assertEquals(generation, source.generation)
        }
    }
    @Test fun everyChangedTemplateOrAidByteIsRejectedRatherThanUsedForDiscovery() {
        for (offset in 0 until 20) {
            val bad = DirectoryFixtures.bytes().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            try { CardDirectoryParser.parse(CardDirectoryFile(CardGeneration.GEN2, bad)); fail("byte$offset") }
            catch (_: ParseError) { }
        }
        val reversed = DirectoryFixtures.bytes().let { it.copyOfRange(10, 20) + it.copyOfRange(0, 10) }
        try { CardDirectoryParser.parse(CardDirectoryFile(CardGeneration.GEN2V2, reversed)); fail() }
        catch (_: ParseError) { }
    }
    @Test fun exactBlankFilesAreSourcesWithNoApplicationRecordAndMixedFillerIsInvalid() {
        for (fill in listOf(0, 255)) assertNull(CardDirectoryParser.parse(
            CardDirectoryFile(CardGeneration.GEN2V2, ByteArray(20) { fill.toByte() })))
        try { CardDirectoryParser.parse(CardDirectoryFile(CardGeneration.GEN2V2, ByteArray(20).also { it[0] = -1 })); fail() }
        catch (_: ParseError) { }
    }
    @Test fun unsupportedGenerationsAndAllWrongSizesCannotConstructSources() {
        for (generation in CardGeneration.entries) for (size in listOf(0, 10, 19, 20, 21, 128)) {
            if (generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2) && size == 20) continue
            try { CardDirectoryFile(generation, ByteArray(size)); fail("$generation/$size") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun inputAndExposedByteMutationsCannotChangeTheFrozenOriginal() {
        val raw = DirectoryFixtures.bytes(); val source = CardDirectoryFile(CardGeneration.GEN2, raw)
        raw.fill(0); source.bytes.fill(0)
        assertArrayEquals(DirectoryFixtures.bytes(), source.bytes)
        assertNotNull(CardDirectoryParser.parse(source))
    }
}

class CardDirectoryReaderTest {
    private class Transport(val bytes: ByteArray = DirectoryFixtures.bytes(), val chunk: Int = 20,
        val endStatus: Int = 0x6B00, val endData: ByteArray = byteArrayOf(),
        val selectFails: Boolean = false, val failure: Exception? = null,
        val bodyReply: ByteArray? = null) : CardReaderTransport {
        val commands = mutableListOf<ByteArray>()
        override suspend fun availability() = SourceAvailability.AVAILABLE
        override suspend fun open() = ReaderState.Open
        override suspend fun powerOn() = byteArrayOf(0x3B, 0)
        override suspend fun powerOff() = Unit
        override fun close() = Unit
        override suspend fun exchange(apdu: ByteArray): ByteArray {
            commands += apdu.copyOf()
            if (apdu[1].toInt() and 255 == 0xA4)
                return if (selectFails) byteArrayOf(0x6A, 0x82.toByte()) else byteArrayOf(0x90.toByte(), 0)
            failure?.let { throw it }
            val offset = (apdu[2].toInt() and 255) shl 8 or (apdu[3].toInt() and 255)
            if (offset == 20) return endData + byteArrayOf((endStatus ushr 8).toByte(), endStatus.toByte())
            bodyReply?.let { return it }
            if (offset >= bytes.size) return byteArrayOf(0x6B, 0)
            val length = minOf(apdu[4].toInt() and 255, chunk, bytes.size - offset)
            return bytes.copyOfRange(offset, offset + length) + byteArrayOf(0x90.toByte(), 0)
        }
    }
    private suspend fun read(fake: Transport) = CardDirectoryReader.read(ApduSession(fake))
    @Test fun everyChunkSizeAndRecognizedEmptyEofProvesTheFullTwentyByteSource() = runBlocking {
        for (chunk in 1..20) for (status in listOf(0x6700, 0x6B00, 0x6282)) {
            val fake = Transport(chunk = chunk, endStatus = status)
            assertArrayEquals(DirectoryFixtures.bytes(), read(fake))
            assertArrayEquals(CardCommands.selectFile(0x2F00).encode(), fake.commands.first())
            assertArrayEquals(CardCommands.readBinary(20, 1).encode(), fake.commands.last())
            assertTrue(fake.commands.size <= 22)
            assertTrue(fake.commands.all { it[1].toInt() and 255 in setOf(0xA4, 0xB0) })
        }
    }
    @Test fun aTruncatedBodyCannotBecomeACompleteDirectory() = runBlocking {
        for (size in 0..19) {
            try { read(Transport(bytes = DirectoryFixtures.bytes().copyOf(size))); fail("size$size") }
            catch (_: ReaderException) { }
        }
    }
    @Test fun extraEndDataAndSuccessfulEmptyOrWrongLengthEofAreRejectedWithoutCorrection() = runBlocking {
        for (status in listOf(0x9000, 0x6700, 0x6B00, 0x6282, 0x6C00, 0x6C01, 0x6982))
            for (data in listOf(byteArrayOf(), byteArrayOf(1))) {
                if (data.isEmpty() && status in setOf(0x6700, 0x6B00, 0x6282)) continue
                val fake = Transport(endStatus = status, endData = data)
                try { read(fake); fail("status$status/data${data.size}") } catch (_: ReaderException) { }
                assertEquals(3, fake.commands.size)
                assertArrayEquals(CardCommands.readBinary(20, 1).encode(), fake.commands.last())
            }
    }
    @Test fun emptyOversizedAndErrorBodyRepliesCannotEscapeTheFixedReadBound() = runBlocking {
        for (reply in listOf(byteArrayOf(0x90.toByte(), 0), ByteArray(21) + byteArrayOf(0x90.toByte(), 0),
            byteArrayOf(1, 0x62, 0x82.toByte()), byteArrayOf(0x69, 0x82.toByte()))) {
            val fake = Transport(bodyReply = reply)
            try { read(fake); fail() } catch (_: ReaderException) { }
            assertEquals(2, fake.commands.size)
        }
    }
    @Test fun failedSelectionTimeoutAndCancellationNeverReturnPartialBytes() = runBlocking {
        val missing = Transport(selectFails = true)
        try { read(missing); fail() } catch (_: ReaderException) { }
        assertEquals(1, missing.commands.size)
        try { read(Transport(failure = ReaderException(ReaderFailure.TIMEOUT))); fail() }
        catch (error: ReaderException) { assertEquals(ReaderFailure.TIMEOUT, error.failure) }
        try { read(Transport(failure = CancellationException("synthetic"))); fail() }
        catch (_: CancellationException) { }
    }
}

